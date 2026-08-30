//! Routing graphs, and the routing service the watch falls back to.
//!
//! The whole-country graph is served as a file. A box out of it is cut on
//! demand: nodes are numbered in grid-cell order, so a rectangle of cells is
//! a handful of contiguous runs of ids and the cut is a copy and a
//! renumbering rather than a search.

use crate::mercator::CELL_DEG;
use crate::store::Bbox;
use crate::App;
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::atomic::Ordering;
use tiny_http::Request;

use crate::{header, num, send_bytes, send_status, send_text};

fn be32(b: &[u8], o: usize) -> u32 {
    u32::from_be_bytes([b[o], b[o + 1], b[o + 2], b[o + 3]])
}

fn be64f(b: &[u8], o: usize) -> f64 {
    f64::from_be_bytes([
        b[o], b[o + 1], b[o + 2], b[o + 3], b[o + 4], b[o + 5], b[o + 6], b[o + 7],
    ])
}

/// Cut a WGR2 graph down to a bounding box, producing a WGR2 graph.
pub fn subgraph(raw: &[u8], w: f64, s: f64, e: f64, n: f64) -> Option<Vec<u8>> {
    if raw.len() < 56 || &raw[0..4] != b"WGR2" {
        return None;
    }
    let nodes = be32(raw, 8) as usize;
    let arcs = be32(raw, 12) as usize;
    let cols = be32(raw, 16) as i64;
    let rows = be32(raw, 20) as i64;
    let minx = be64f(raw, 24);
    let miny = be64f(raw, 32);

    let nodes_at = 56usize;
    let adj_at = nodes_at + nodes * 8;
    let arcs_at = adj_at + (nodes + 1) * 4;
    let grid_at = arcs_at + arcs * 6;
    if raw.len() < grid_at + ((cols * rows) as usize + 1) * 4 {
        return None;
    }
    let cell = |c: i64| -> usize { be32(raw, grid_at + c as usize * 4) as usize };

    let clamp = |v: i64, hi: i64| v.max(0).min(hi - 1);
    let x0 = clamp(((w - minx) / CELL_DEG).floor() as i64, cols);
    let x1 = clamp(((e - minx) / CELL_DEG).floor() as i64, cols);
    let y0 = clamp(((s - miny) / CELL_DEG).floor() as i64, rows);
    let y1 = clamp(((n - miny) / CELL_DEG).floor() as i64, rows);
    if x1 < x0 || y1 < y0 {
        return None;
    }

    let ncols = (x1 - x0 + 1) as usize;
    let nrows = (y1 - y0 + 1) as usize;

    // One run of ids per row of cells, in the order they will be written.
    let mut runs: Vec<(usize, usize, usize)> = Vec::with_capacity(nrows);
    let mut kept = 0usize;
    for y in y0..=y1 {
        let from = cell(y * cols + x0);
        let to = cell(y * cols + x1 + 1);
        runs.push((from, to.max(from), kept));
        kept += to.saturating_sub(from);
    }
    if kept == 0 {
        return None;
    }

    let new_of = |id: usize| -> Option<usize> {
        let (mut lo, mut hi) = (0isize, runs.len() as isize - 1);
        while lo <= hi {
            let mid = ((lo + hi) / 2) as usize;
            let (from, to, base) = runs[mid];
            if id < from {
                hi = mid as isize - 1;
            } else if id >= to {
                lo = mid as isize + 1;
            } else {
                return Some(base + (id - from));
            }
        }
        None
    };

    let mut out_nodes = Vec::with_capacity(kept * 8);
    for &(from, to, _) in runs.iter() {
        out_nodes.extend_from_slice(&raw[nodes_at + from * 8..nodes_at + to * 8]);
    }

    let mut adj = Vec::with_capacity((kept + 1) * 4);
    let mut out_arcs = Vec::with_capacity(kept * 6);
    let mut at = 0u32;
    for &(from, to, _) in runs.iter() {
        for id in from..to {
            adj.extend_from_slice(&at.to_be_bytes());
            let a0 = be32(raw, adj_at + id * 4) as usize;
            let a1 = be32(raw, adj_at + (id + 1) * 4) as usize;
            for k in a0..a1 {
                let tgt = be32(raw, arcs_at + k * 6) as usize;
                if let Some(nt) = new_of(tgt) {
                    out_arcs.extend_from_slice(&(nt as u32).to_be_bytes());
                    out_arcs.extend_from_slice(&raw[arcs_at + k * 6 + 4..arcs_at + k * 6 + 6]);
                    at += 1;
                }
            }
        }
    }
    adj.extend_from_slice(&at.to_be_bytes());

    // Node order is already (row, column), which is the new cell order.
    let mut counts = vec![0u32; ncols * nrows + 1];
    for y in y0..=y1 {
        for x in x0..=x1 {
            let a = cell(y * cols + x);
            let b = cell(y * cols + x + 1);
            counts[((y - y0) as usize * ncols + (x - x0) as usize) + 1] =
                b.saturating_sub(a) as u32;
        }
    }
    for c in 1..=ncols * nrows {
        counts[c] += counts[c - 1];
    }
    let mut grid = Vec::with_capacity(counts.len() * 4);
    for c in counts {
        grid.extend_from_slice(&c.to_be_bytes());
    }

    let ox = minx + x0 as f64 * CELL_DEG;
    let oy = miny + y0 as f64 * CELL_DEG;

    let mut out = Vec::with_capacity(56 + out_nodes.len() + adj.len() + out_arcs.len() + grid.len());
    out.extend_from_slice(b"WGR2");
    // Version, a spare byte, and the fastest way in the graph - carried over
    // from the country it was cut out of rather than left at zero.
    //
    // A* is only correct if its estimate of what is left never exceeds the
    // truth, and that estimate is the straight line divided by this. Zero
    // means "the file does not say", which sends the router to its
    // conservative fallback of 140 km/h - a loose bound for a car and an
    // absurd one for a bicycle, whose real answer is 18. The boxed graph is
    // what the watch actually routes on, so it was the one place the figure
    // mattered most and the only place it was being dropped.
    let maxkmh = ((raw[6] as u16) << 8) | raw[7] as u16;
    out.extend_from_slice(&[2u8, 0]);
    out.extend_from_slice(&maxkmh.to_be_bytes());
    out.extend_from_slice(&(kept as u32).to_be_bytes());
    out.extend_from_slice(&at.to_be_bytes());
    out.extend_from_slice(&(ncols as u32).to_be_bytes());
    out.extend_from_slice(&(nrows as u32).to_be_bytes());
    out.extend_from_slice(&ox.to_be_bytes());
    out.extend_from_slice(&oy.to_be_bytes());
    out.extend_from_slice(&(ox + ncols as f64 * CELL_DEG).to_be_bytes());
    out.extend_from_slice(&(oy + nrows as f64 * CELL_DEG).to_be_bytes());
    out.extend_from_slice(&out_nodes);
    out.extend_from_slice(&adj);
    out.extend_from_slice(&out_arcs);
    out.extend_from_slice(&grid);
    Some(out)
}

fn graph_file(app: &App, q: &HashMap<String, String>) -> Option<(String, PathBuf)> {
    let named = q.get("c").map(|s| s.as_str()).unwrap_or("");
    let name = if !named.is_empty() {
        named.to_string()
    } else if q.contains_key("w") && q.contains_key("n") {
        let lon = (num::<f64>(q, "w", 0.0) + num::<f64>(q, "e", 0.0)) / 2.0;
        let lat = (num::<f64>(q, "s", 0.0) + num::<f64>(q, "n", 0.0)) / 2.0;
        app.stores.at(lon, lat)?.name.clone()
    } else {
        return None;
    };
    if !name.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_') {
        return None;
    }
    // Two networks per country: what a car may drive and what a bicycle may
    // ride. They barely overlap - a motorway is in one and 225,516 cycleways
    // are in the other - so they are separate files, and the watch asks for
    // whichever mode it is in.
    let name = match q.get("mode").map(|s| s.as_str()) {
        Some("bike") => format!("{}-bike", name),
        _ => name,
    };
    let p = app.data.join(format!("{}.graph", name));
    if p.is_file() {
        Some((name, p))
    } else {
        None
    }
}

pub fn handle(app: &App, r: Request, q: &HashMap<String, String>, gz: bool) {
    let (name, path) = match graph_file(app, q) {
        Some(v) => v,
        None => return send_status(r, 404, "no graph"),
    };

    if q.contains_key("w") && q.contains_key("s") && q.contains_key("e") && q.contains_key("n") {
        let (w, s, e, n) = (
            num::<f64>(q, "w", f64::NAN),
            num::<f64>(q, "s", f64::NAN),
            num::<f64>(q, "e", f64::NAN),
            num::<f64>(q, "n", f64::NAN),
        );
        if !crate::sane_lon(w) || !crate::sane_lon(e)
                || !crate::sane_lat(s) || !crate::sane_lat(n) || w > e || s > n {
            return send_status(r, 400, "bad box");
        }
        // Cut fresh every time, and not written to disk.
        //
        // It used to be cached per bounding box, which means one file per
        // distinct box - and the box comes from the request. Five crafted
        // requests made five files; there is no number at which that stops.
        // Cutting a box out of the Netherlands takes a moment and the watch
        // asks for one perhaps once a country, so the cache was buying very
        // little and offering anyone on the internet a way to fill the disk.
        /*
         * Mapped, not read.
         *
         * Cutting a box needs random access right across the file - the nodes
         * for a cell are contiguous but the arcs point anywhere - so this
         * cannot be streamed the way the whole-country reply can. Reading it
         * instead allocated a copy of the country per request: six concurrent
         * boxes out of England took the process from 4 MB to 821 MB, measured.
         *
         * A mapping costs address space rather than memory, and several
         * requests for the same country share the same pages through the page
         * cache, which is where the file already was.
         */
        let file = match std::fs::File::open(&path) {
            Ok(f) => f,
            Err(_) => return send_status(r, 404, "no graph"),
        };
        let raw = match unsafe { memmap2::Mmap::map(&file) } {
            Ok(m) => m,
            Err(_) => return send_status(r, 500, "cannot map the graph"),
        };
        let body = match subgraph(&raw, w, s, e, n) {
            Some(c) => c,
            None => return send_status(r, 404, "empty box"),
        };

        if q.contains_key("info") {
            return send_text(r, &format!("{}\n", body.len()));
        }
        return send_bytes(r, body, "application/octet-stream", gz);
    }

    if q.contains_key("info") {
        let len = std::fs::metadata(&path).map(|m| m.len()).unwrap_or(0);
        return send_text(r, &format!("{} 0\n", len));
    }

    // The whole country. The compressed copy is made by build_graph.php, not
    // here: thirty-six megabytes of deflate inside a request is a timeout
    // waiting to happen.
    /*
     * Streamed off the disk, not read into memory first.
     *
     * These are the largest things this service hands out - England's graph is
     * 157 MB - and std::fs::read allocated the whole of one per request. Six
     * concurrent requests took the process from 15 MB to 956 MB, measured, and
     * the worker pool is the size of the machine, so eight is the ceiling
     * rather than six. Nobody has to be malicious for it to happen either:
     * the watch asks for this once per country, and a retry while the first
     * attempt is still running is two.
     *
     * tiny_http reads from the handle as it writes to the socket, so what is
     * held is a buffer rather than a country.
     */
    let gzpath = app.data.join(format!("{}.graph.gz", name));
    if gz && gzpath.is_file() {
        if let Ok(f) = std::fs::File::open(&gzpath) {
            let len = f.metadata().map(|m| m.len() as usize).ok();
            let _ = r.respond(tiny_http::Response::new(
                tiny_http::StatusCode(200),
                vec![
                    header("Content-Type", "application/octet-stream"),
                    header("Content-Encoding", "gzip"),
                    header("Vary", "Accept-Encoding"),
                    header("Cache-Control", "public, max-age=2592000"),
                ],
                f,
                len,
                None,
            ));
            return;
        }
    }
    match std::fs::File::open(&path) {
        Ok(f) => {
            let len = f.metadata().map(|m| m.len() as usize).ok();
            let _ = r.respond(tiny_http::Response::new(
                tiny_http::StatusCode(200),
                vec![
                    header("Content-Type", "application/octet-stream"),
                    header("Cache-Control", "public, max-age=2592000"),
                ],
                f,
                len,
                None,
            ));
        }
        Err(_) => send_status(r, 404, "no graph"),
    }
}

/// The speed cameras, motorway exits and filling stations for an area.
///
///     /alerts.php?c=netherlands                    the whole country
///     /alerts.php?c=netherlands&w=..&s=..&e=..&n=..  a box of it
///
/// Built from the store on the way out rather than read from a file. The
/// store is indexed by cell, so a box costs a box - which is the point: the
/// Netherlands packs into 111 kB and a continent would not, and a watch only
/// ever needs what is around it.
///
/// A country whose store predates the point import has no `pt` table and
/// answers with an empty layer rather than an error. "Nothing here" is a
/// thing the watch knows how to handle; a 500 is not.
pub fn alerts(app: &App, r: Request, q: &HashMap<String, String>) {
    let name = match q.get("c") {
        Some(n) if !n.is_empty() => n.as_str(),
        _ => return send_status(r, 400, "no country"),
    };
    let country = match app.stores.get(name) {
        Some(c) => c,
        None => return send_status(r, 404, "no such country"),
    };

    let full = country.bbox;
    let box_ = if q.contains_key("w") || q.contains_key("n") {
        let w = num::<f64>(q, "w", f64::NAN);
        let s = num::<f64>(q, "s", f64::NAN);
        let e = num::<f64>(q, "e", f64::NAN);
        let n = num::<f64>(q, "n", f64::NAN);
        if !crate::sane_lon(w) || !crate::sane_lon(e)
            || !crate::sane_lat(s) || !crate::sane_lat(n)
            || e <= w || n <= s
        {
            return send_status(r, 400, "bad box");
        }
        // Clipped to the country: a box reaching past it would otherwise make
        // a grid of empty cells stretching to wherever the caller asked.
        Bbox(w.max(full.0), s.max(full.1), e.min(full.2), n.min(full.3))
    } else {
        full
    };
    if box_.2 <= box_.0 || box_.3 <= box_.1 {
        // Asked for somewhere this country is not.
        return send_bytes(r, crate::alerts::encode(&[], full), "application/octet-stream", false);
    }

    let pts = country.points(box_);
    let bin = crate::alerts::encode(&pts, box_);
    send_bytes(r, bin, "application/octet-stream", false)
}

/// At most this many route requests may be waiting on the upstream router at
/// once, out of a worker pool the size of the machine's cores.
const MAX_INFLIGHT_ROUTES: usize = 2;

/// The most cached routes to keep. A route is a few kilobytes and the cache
/// key is four coordinates to five decimal places, so there is no natural
/// limit to how many distinct ones can be asked for - without this the
/// directory grows until the disk is full, which takes a bored script an
/// afternoon.
const MAX_CACHED_ROUTES: usize = 4000;

/// Decrement on the way out, whichever way that is - an early return for a
/// full queue, an upstream error, or success.
struct Release<'a>(&'a std::sync::atomic::AtomicUsize);
impl Drop for Release<'_> {
    fn drop(&mut self) {
        self.0.fetch_sub(1, Ordering::SeqCst);
    }
}

/// Keep the route cache under MAX_CACHED_ROUTES, oldest evicted first.
///
/// Only runs when a route is about to be written, which is only on a miss, so
/// the directory listing costs nothing in the common case. It counts first
/// and sorts only when over, because sorting four thousand entries to delete
/// none of them is the sort of thing that looks free until the disk is slow.
fn trim_routes(dir: &std::path::Path) {
    let mut files: Vec<_> = match std::fs::read_dir(dir) {
        Ok(rd) => rd.filter_map(|e| e.ok()).collect(),
        Err(_) => return,
    };
    if files.len() <= MAX_CACHED_ROUTES {
        return;
    }
    files.sort_by_key(|e| {
        e.metadata()
            .and_then(|m| m.modified())
            .unwrap_or(std::time::SystemTime::UNIX_EPOCH)
    });
    // Down to nine tenths, so this does not run again on the very next write.
    let drop_to = MAX_CACHED_ROUTES * 9 / 10;
    for e in files.iter().take(files.len().saturating_sub(drop_to)) {
        let _ = std::fs::remove_file(e.path());
    }
}

/// Routing, proxied to an OSRM instance and cached for a day.
///
/// The watch routes on its own graph when it has one, which is nearly always:
/// this endpoint is the fallback for a country whose graph is not on the
/// card. That matters for what it is allowed to depend on - a fallback that
/// is down leaves the watch with no route at all in exactly the case where it
/// could not compute one itself.
///
/// It defaults to the public demo server, which is convenient and is
/// explicitly not offered for production use: it rate-limits, it is not
/// guaranteed to be up, and it is a third party learning every destination
/// this watch is sent to. Set MAP_OSRM to a host you run to avoid all three.
pub fn route(app: &App, r: Request, q: &HashMap<String, String>) {
    let flat: f64 = num(q, "flat", f64::NAN);
    let flon: f64 = num(q, "flon", f64::NAN);
    let tlat: f64 = num(q, "tlat", f64::NAN);
    let tlon: f64 = num(q, "tlon", f64::NAN);
    if !crate::sane_lat(flat) || !crate::sane_lon(flon)
            || !crate::sane_lat(tlat) || !crate::sane_lon(tlon) {
        return send_status(r, 400, "bad request");
    }

    let key = format!("{:.5}_{:.5}_{:.5}_{:.5}", flat, flon, tlat, tlon);
    // The version is part of the name, not decoration: without it the day of WRT1 files already
    // on disk would go on being served and the roundabout exits would appear tomorrow rather
    // than now.
    let cached = app.tiles.join("routes").join(format!("{}.v2.bin", key));
    if let Ok(md) = std::fs::metadata(&cached) {
        let fresh = md
            .modified()
            .ok()
            .and_then(|t| t.elapsed().ok())
            .map(|e| e.as_secs() < 86400)
            .unwrap_or(false);
        if fresh {
            if let Ok(b) = std::fs::read(&cached) {
                return send_bytes(r, b, "application/octet-stream", false);
            }
        }
    }

    // Only so many of these may be in flight at once. Past that, say so
    // immediately rather than holding a worker: a watch told "busy" asks
    // again, a watch told nothing for twenty seconds has already given up,
    // and every other endpoint stays answerable meanwhile.
    let inflight = app.routing.fetch_add(1, Ordering::SeqCst);
    let _release = Release(&app.routing);
    if inflight >= MAX_INFLIGHT_ROUTES {
        return send_status(r, 503, "routing busy");
    }

    let url = format!(
        "{}/route/v1/driving/{:.6},{:.6};{:.6},{:.6}\
         ?overview=full&geometries=geojson&steps=true",
        app.osrm, flon, flat, tlon, tlat
    );
    // Well under the watch's own patience. A routing service that has not
    // answered in eight seconds is not about to.
    let body = match ureq::get(&url).timeout(std::time::Duration::from_secs(8)).call() {
        Ok(res) => res.into_string().unwrap_or_default(),
        Err(_) => return send_status(r, 502, "no route"),
    };

    match crate::graph::encode_route(&body) {
        Some(bin) => {
            if let Some(d) = cached.parent() {
                let _ = std::fs::create_dir_all(d);
                trim_routes(d);
            }
            let _ = std::fs::write(&cached, &bin);
            send_bytes(r, bin, "application/octet-stream", false)
        }
        None => send_status(r, 502, "no route"),
    }
}

/// The watch's route format. Kept identical to route.php so a route from
/// either source reads the same on the device.
pub fn encode_route(json: &str) -> Option<Vec<u8>> {
    crate::route_encode::encode(json)
}
