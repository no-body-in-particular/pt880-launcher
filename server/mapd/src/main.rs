//! mapd - map tiles, road vectors and routing graphs for the PT880 watch.
//!
//! Replaces the PHP under /map/, behind hiawatha rather than beside it: the
//! watch keeps talking to https://coredump.ws/map/ and hiawatha reverse
//! proxies to this. That matters because the watch's TLS is BouncyCastle
//! speaking to hiawatha's exact configuration, which took long enough to get
//! working on a 2013 device that it is not worth risking to save a hop.
//!
//! The endpoints and their answers are unchanged, byte for byte where it
//! counts. What changes is that a block of 256 tiles is rendered across every
//! core instead of one after another in a process that exits afterwards, and
//! that decoded map data stays decoded between requests.

mod graph;
mod alerts;
mod route_encode;
mod mercator;
mod palette;
mod render;
mod store;

use flate2::write::GzEncoder;
use flate2::Compression;
use rayon::prelude::*;
use std::collections::HashMap;
use std::io::Write;
use std::path::PathBuf;
use std::sync::Arc;
use tiny_http::{Header, Request, Response, Server};

pub struct App {
    pub stores: store::Stores,
    pub tiles: PathBuf,
    pub data: PathBuf,
    /// Whether assembled blocks are written to disk. Off by default - see
    /// block_bytes.
    pub disk_cache: bool,
    /// Base URL of the OSRM used by /route.php, without a trailing slash.
    pub osrm: String,
    /// How many route requests are waiting on that OSRM right now.
    ///
    /// Every one of them is holding a worker. There are only as many workers
    /// as cores, so without a cap eight requests to a routing service that
    /// has gone quiet stop this one answering anything at all - measured at
    /// 19 seconds for a tile that takes under two. The watch downloading a
    /// country simply stalls, and nothing in the log says why.
    pub routing: std::sync::atomic::AtomicUsize,
    /// A few recent blocks, for the watch retrying one it failed to read.
    /// Held behind an Arc so serving one costs a refcount rather than a copy
    /// of up to a megabyte and a half.
    pub recent: std::sync::Mutex<Vec<(String, Arc<Vec<u8>>)>>,
}

/// How much of the recent blocks to keep in memory.
///
/// Bounded by bytes rather than by count, because a block is anywhere from a
/// hundred bytes over open sea to a megabyte and a half over a city - twenty
/// of the former is nothing and twenty of the latter is thirty megabytes.
/// This covers the one repeat that actually happens, the watch retrying a
/// block it failed to read; it does not ask twice otherwise, because it
/// stores what it downloads.
const RECENT_BYTES: usize = 24 * 1024 * 1024;

/// The most tiles one request may ask to have rendered.
const MAX_TILES: i32 = 400;

fn query(url: &str) -> HashMap<String, String> {
    let mut out = HashMap::new();
    if let Some(q) = url.split('?').nth(1) {
        for pair in q.split('&') {
            let mut it = pair.splitn(2, '=');
            let k = it.next().unwrap_or("").to_string();
            let v = it.next().unwrap_or("").to_string();
            out.insert(k, urldecode(&v));
        }
    }
    out
}

fn urldecode(s: &str) -> String {
    let b = s.as_bytes();
    let mut out = Vec::with_capacity(b.len());
    let mut i = 0;
    while i < b.len() {
        match b[i] {
            b'%' if i + 2 < b.len() => {
                let hex = std::str::from_utf8(&b[i + 1..i + 3]).unwrap_or("");
                match u8::from_str_radix(hex, 16) {
                    Ok(v) => {
                        out.push(v);
                        i += 3;
                    }
                    Err(_) => {
                        out.push(b[i]);
                        i += 1;
                    }
                }
            }
            b'+' => {
                out.push(b' ');
                i += 1;
            }
            c => {
                out.push(c);
                i += 1;
            }
        }
    }
    String::from_utf8_lossy(&out).into_owned()
}

fn num<T: std::str::FromStr>(q: &HashMap<String, String>, k: &str, d: T) -> T {
    q.get(k).and_then(|v| v.parse::<T>().ok()).unwrap_or(d)
}

/// A coordinate that is actually on the planet.
///
/// "inf" parses as a float and is not NaN, so a check for NaN alone lets it
/// through - and it then goes into a URL sent to the routing service, or into
/// a bounding box where every comparison against it is false and the clamping
/// quietly falls through to the whole country. Neither is dangerous, but both
/// are work done on nonsense, and a request that means nothing should be
/// refused rather than answered at length.
pub fn sane_lat(v: f64) -> bool { v.is_finite() && v >= -90.0 && v <= 90.0 }
pub fn sane_lon(v: f64) -> bool { v.is_finite() && v >= -180.0 && v <= 180.0 }

fn header(k: &str, v: &str) -> Header {
    Header::from_bytes(k.as_bytes(), v.as_bytes()).unwrap()
}

fn wants_gzip(r: &Request) -> bool {
    r.headers().iter().any(|h| {
        h.field.equiv("Accept-Encoding") && h.value.as_str().contains("gzip")
    })
}

fn send_bytes(r: Request, body: Vec<u8>, mime: &str, gzip: bool) {
    let mut headers = vec![
        header("Content-Type", mime),
        header("Cache-Control", "public, max-age=2592000"),
    ];
    let body = if gzip && body.len() > 512 {
        let mut e = GzEncoder::new(Vec::new(), Compression::new(6));
        let _ = e.write_all(&body);
        match e.finish() {
            Ok(z) if z.len() < body.len() => {
                headers.push(header("Content-Encoding", "gzip"));
                headers.push(header("Vary", "Accept-Encoding"));
                z
            }
            _ => body,
        }
    } else {
        body
    };
    let len = body.len();
    let _ = r.respond(Response::new(
        tiny_http::StatusCode(200),
        headers,
        std::io::Cursor::new(body),
        Some(len),
        None,
    ));
}

fn send_text(r: Request, s: &str) {
    let body = s.as_bytes().to_vec();
    let len = body.len();
    let _ = r.respond(Response::new(
        tiny_http::StatusCode(200),
        vec![header("Content-Type", "text/plain")],
        std::io::Cursor::new(body),
        Some(len),
        None,
    ));
}

fn send_status(r: Request, code: u16, msg: &str) {
    let body = msg.as_bytes().to_vec();
    let len = body.len();
    let _ = r.respond(Response::new(
        tiny_http::StatusCode(code),
        vec![header("Content-Type", "text/plain")],
        std::io::Cursor::new(body),
        Some(len),
        None,
    ));
}

/// The country a request is about: named, or worked out from where it is
/// asking about. Tile numbers are global, so the watch has no business
/// knowing which database a tile comes out of.
fn pick<'a>(
    app: &'a App,
    q: &HashMap<String, String>,
    lon: f64,
    lat: f64,
) -> Option<&'static store::Country> {
    let named = q.get("c").map(|s| s.as_str()).unwrap_or("");
    if !named.is_empty() {
        if let Some(c) = app.stores.get(named) {
            return Some(c);
        }
    }
    app.stores.at(lon, lat)
}

fn block_path(app: &App, country: &str, z: u8, bx: i32, by: i32) -> PathBuf {
    app.tiles
        .join(country)
        .join(format!("b{}", z))
        .join(format!("{}_{}.wpk", bx, by))
}

/// One 16x16 block, assembled and cached. The tiles are rendered in parallel:
/// they are independent, there are 256 of them, and the machine has cores
/// that were sitting idle while php-cgi worked through them one at a time.
/// One 16x16 block, assembled.
///
/// The tiles are rendered in parallel: they are independent, there are 256 of
/// them, and the machine has cores that sat idle while php-cgi worked through
/// them one at a time.
///
/// Not written to disk by default. Measured on this data, an average block
/// renders in 120ms and reads back from disk in 40ms, while transferring it to
/// the watch over wifi takes 2.2 seconds - so the cache saved four per cent of
/// a download and cost 215MB per country, which for Europe and America would
/// be tens of gigabytes. It also had to be wiped by hand every time the
/// rendering changed, and twice this week that was noticed only after the
/// watch had downloaded the stale version. Rendering afresh is always correct
/// and nearly always fast enough. Set MAP_DISK_CACHE=1 to put it back.
fn block_bytes(app: &App, c: &'static store::Country, z: u8, bx: i32, by: i32) -> Vec<u8> {
    let key = format!("{}/{}/{}_{}", c.name, z, bx, by);

    if app.disk_cache {
        if let Ok(b) = std::fs::read(block_path(app, &c.name, z, bx, by)) {
            if b.len() > 9 {
                return b;
            }
        }
    }
    {
        let recent = app.recent.lock().unwrap();
        if let Some((_, b)) = recent.iter().find(|(k, _)| *k == key) {
            return (**b).clone();
        }
    }

    let span = 1i32 << z;
    let x0 = bx << 4;
    let y0 = by << 4;
    let mut want: Vec<(i32, i32)> = Vec::with_capacity(256);
    for i in 0..16 {
        for j in 0..16 {
            let (tx, ty) = (x0 + i, y0 + j);
            if tx >= 0 && ty >= 0 && tx < span && ty < span {
                want.push((tx, ty));
            }
        }
    }

    let tiles: Vec<(i32, i32, Vec<u8>)> = want
        .par_iter()
        .map(|&(tx, ty)| (tx, ty, render::render_tile(c, z, tx, ty)))
        .collect();

    let mut body = Vec::with_capacity(64 * 1024);
    body.extend_from_slice(b"WPK1");
    body.push(z);
    body.extend_from_slice(&(tiles.len() as u32).to_be_bytes());
    for (tx, ty, png) in tiles.iter() {
        body.extend_from_slice(&(*tx as u32).to_be_bytes());
        body.extend_from_slice(&(*ty as u32).to_be_bytes());
        body.extend_from_slice(&(png.len() as u32).to_be_bytes());
        body.extend_from_slice(png);
    }

    if app.disk_cache {
        let path = block_path(app, &c.name, z, bx, by);
        if let Some(dir) = path.parent() {
            let _ = std::fs::create_dir_all(dir);
        }
        // Written under a temporary name and moved, so a request arriving
        // while this one is still writing cannot read a half block.
        let tmp = path.with_extension(format!("wpk.{}", std::process::id()));
        if std::fs::write(&tmp, &body).is_ok() {
            let _ = std::fs::rename(&tmp, &path);
        } else {
            let _ = std::fs::remove_file(&tmp);
        }
    }

    let shared = Arc::new(body);
    {
        let mut recent = app.recent.lock().unwrap();
        recent.push((key, shared.clone()));
        let mut held: usize = recent.iter().map(|(_, b)| b.len()).sum();
        while held > RECENT_BYTES && recent.len() > 1 {
            held -= recent[0].1.len();
            recent.remove(0);
        }
    }
    (*shared).clone()
}

fn handle(app: &App, r: Request) {
    let url = r.url().to_string();
    let path = url.split('?').next().unwrap_or("").to_string();
    let q = query(&url);
    let gz = wants_gzip(&r);

    let leaf = path.rsplit('/').next().unwrap_or("");
    match leaf {
        "pack.php" => {
            let z: u8 = num(&q, "z", 15);
            let x: i32 = num(&q, "x", -1);
            let y: i32 = num(&q, "y", -1);
            let w: i32 = num::<i32>(&q, "w", 16).clamp(1, 32);
            let h: i32 = num::<i32>(&q, "h", 16).clamp(1, 32);
            if z < 1 || z > 18 || x < 0 || y < 0 {
                return send_status(r, 400, "bad request");
            }
            // Enough to amortise the round trip, small enough that one
            // request cannot occupy the machine. The PHP had this limit and
            // the port lost it: clamping w and h to 32 each allows 1024
            // tiles, which measured 7.4 seconds of cpu and four megabytes -
            // per unauthenticated request, from the internet. A handful at
            // once would take every core.
            if w * h > MAX_TILES {
                return send_status(r, 400, "too many tiles");
            }
            let mid = mercator::tile_bbox(z, x + w / 2, y + h / 2);
            let c = match pick(app, &q, (mid.0 + mid.2) / 2.0, (mid.1 + mid.3) / 2.0) {
                Some(c) => c,
                None => return send_status(r, 404, "no map here"),
            };
            let aligned = w == 16 && h == 16 && x % 16 == 0 && y % 16 == 0;
            if aligned {
                let body = block_bytes(app, c, z, x >> 4, y >> 4);
                return send_bytes(r, body, "application/octet-stream", gz);
            }
            // An unaligned request - by hand, or from an older build. Rendered
            // but not cached, since it is not a block anything stores.
            let span = 1i32 << z;
            let mut body = Vec::new();
            body.extend_from_slice(b"WPK1");
            body.push(z);
            let mut count = 0u32;
            let mut parts = Vec::new();
            for i in 0..w {
                for j in 0..h {
                    let (tx, ty) = (x + i, y + j);
                    if tx < 0 || ty < 0 || tx >= span || ty >= span {
                        continue;
                    }
                    let png = render::render_tile(c, z, tx, ty);
                    parts.extend_from_slice(&(tx as u32).to_be_bytes());
                    parts.extend_from_slice(&(ty as u32).to_be_bytes());
                    parts.extend_from_slice(&(png.len() as u32).to_be_bytes());
                    parts.extend_from_slice(&png);
                    count += 1;
                }
            }
            body.extend_from_slice(&count.to_be_bytes());
            body.extend_from_slice(&parts);
            send_bytes(r, body, "application/octet-stream", gz)
        }

        "tile.php" => {
            let z: u8 = num(&q, "z", 15);
            let x: i32 = num(&q, "x", -1);
            let y: i32 = num(&q, "y", -1);
            if z < 1 || z > 18 || x < 0 || y < 0 {
                return send_status(r, 400, "bad request");
            }
            let bb = mercator::tile_bbox(z, x, y);
            match pick(app, &q, (bb.0 + bb.2) / 2.0, (bb.1 + bb.3) / 2.0) {
                Some(c) => send_bytes(r, render::render_tile(c, z, x, y), "image/png", false),
                None => send_status(r, 404, "no map here"),
            }
        }

        "country.php" => {
            // With a position: which country covers it, and its bounds. Without
            // one: every country present, which is how the watch finds out what
            // it could download.
            if q.contains_key("lat") && q.contains_key("lon") {
                let lat: f64 = num(&q, "lat", 0.0);
                let lon: f64 = num(&q, "lon", 0.0);
                return match app.stores.at(lon, lat) {
                    Some(c) => send_text(
                        r,
                        &format!(
                            "{},{:.5},{:.5},{:.5},{:.5},{}\n",
                            c.name, c.bbox.0, c.bbox.1, c.bbox.2, c.bbox.3, c.ways
                        ),
                    ),
                    None => send_text(r, ""),
                };
            }
            let mut out = String::new();
            for n in app.stores.names() {
                if let Some(c) = app.stores.get(&n) {
                    out.push_str(&format!(
                        "{},{:.5},{:.5},{:.5},{:.5},{}\n",
                        c.name, c.bbox.0, c.bbox.1, c.bbox.2, c.bbox.3, c.ways
                    ));
                }
            }
            send_text(r, &out)
        }

        "graph.php" => graph::handle(app, r, &q, gz),

        "route.php" => graph::route(app, r, &q),

        "alerts.php" => graph::alerts(app, r, &q),

        "health" => send_text(r, "ok\n"),

        _ => send_status(r, 404, "no such endpoint"),
    }
}

fn main() {
    let root = std::env::var("MAP_ROOT").unwrap_or_else(|_| "/var/www/hiawatha/map".into());
    let addr = std::env::var("MAP_ADDR").unwrap_or_else(|_| "127.0.0.1:8088".into());
    let root = PathBuf::from(root);

    let disk_cache = std::env::var("MAP_DISK_CACHE").map(|v| v == "1").unwrap_or(false);
    // Where /route.php goes when the watch could not route for itself. The
    // default is the public demo server, which is not meant to be depended
    // on; point this at your own OSRM in production.
    let osrm = std::env::var("MAP_OSRM")
        .unwrap_or_else(|_| "https://router.project-osrm.org".into());
    let osrm = osrm.trim_end_matches('/').to_string();
    let app = Arc::new(App {
        stores: store::Stores::new(root.join("data")),
        tiles: root.join("tiles"),
        data: root.join("data"),
        disk_cache,
        osrm,
        routing: std::sync::atomic::AtomicUsize::new(0),
        recent: std::sync::Mutex::new(Vec::new()),
    });

    let server = Server::http(&addr).expect("bind");
    let countries = app.stores.names();
    eprintln!(
        "mapd listening on {}, tile disk cache {}, {} countries: {}",
        addr,
        if disk_cache { "on" } else { "off" },
        countries.len(),
        countries.join(", ")
    );

    // A pool of workers, each taking whole requests. Rayon parallelises the
    // tiles inside one block; this parallelises the blocks.
    let workers: usize = std::thread::available_parallelism()
        .map(|n| n.get())
        .unwrap_or(4);
    let server = Arc::new(server);
    let mut handles = Vec::new();
    for _ in 0..workers {
        let server = server.clone();
        let app = app.clone();
        handles.push(std::thread::spawn(move || loop {
            match server.recv() {
                Ok(r) => {
                    // A bad request must not take the service down with it.
                    let app = app.clone();
                    if std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                        handle(&app, r)
                    }))
                    .is_err()
                    {
                        eprintln!("request panicked");
                    }
                }
                Err(_) => break,
            }
        }));
    }
    for h in handles {
        let _ = h.join();
    }
}
