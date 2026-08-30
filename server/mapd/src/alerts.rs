//! The things beside the road, packed for the watch.
//!
//! Speed cameras to warn about, motorway junctions to name, filling stations
//! to find. None of them change how a route is chosen, so they are their own
//! small file rather than part of the graph, and a watch without one simply
//! says nothing.
//!
//! Built here, per request, from the store. It began as a precomputed file and
//! a build step, which is the wrong shape twice over: the artifact goes stale
//! against the store it came from, and a country is all a caller can ask for.
//! A continent's cameras are not a sensible download for a watch that only
//! ever needs the ones around it, and the store is already indexed by cell, so
//! answering for a box costs a box.
//!
//! ## Format: WAL1
//!
//! ```text
//! "WAL1"  u8 version  u8 kinds  u16 names   u32 count
//! f64 minx, miny, maxx, maxy      the area covered
//! u32 cols, rows                  cell grid over that area
//! u32 cell[cols*rows + 1]         first point of each cell
//! per point: i32 lat, i32 lon (x1e7), u8 kind, u16 name
//! then the names: u8 length, then that many bytes, `names` times
//! ```
//!
//! A name of 0xFFFF is none. The table is shared, so the several hundred
//! filling stations called Shell cost one string between them. Points are
//! ordered by cell so a reader takes one range rather than scanning.

use std::collections::HashMap;

use crate::store::{Bbox, Point};

/// Rather coarser than the store's cells: there are four orders of magnitude
/// fewer points than ways, so a fine grid would be mostly empty offsets.
const CELL_DEG: f64 = 0.05;

/// Longer than this is not a name on a sign, and the length is one byte.
const MAX_NAME: usize = 60;

pub fn encode(pts: &[Point], area: Bbox) -> Vec<u8> {
    // An empty answer is still a valid file: the watch takes it as "nothing
    // here", which is different from "no layer" and much better than an error
    // it has to decide what to do about.
    // Snapped out to whole cells before anything else is computed.
    //
    // The reader has no cell size of its own: it works one out by dividing the
    // area by the number of cells, which is only the size used here if the
    // area is a whole number of them. It was not, and the two disagreed by
    // 0.3% - enough to put a lookup in the neighbouring cell near the edges of
    // a country. Nothing broke, because a lookup reads a ring of cells around
    // the one it wants and that absorbed the error, but it was one radius
    // change away from breaking and would have looked like missing data rather
    // than arithmetic.
    //
    // Snapping makes the file self-describing: whatever constant either side
    // uses, (maxx - minx) / cols is exactly the size the points were bucketed
    // with.
    let minx = (area.0 / CELL_DEG).floor() * CELL_DEG;
    let miny = (area.1 / CELL_DEG).floor() * CELL_DEG;
    let cols = ((((area.2 - minx) / CELL_DEG).ceil()) as usize).max(1);
    let rows = ((((area.3 - miny) / CELL_DEG).ceil()) as usize).max(1);
    let maxx = minx + cols as f64 * CELL_DEG;
    let maxy = miny + rows as f64 * CELL_DEG;

    let mut buckets: Vec<Vec<usize>> = vec![Vec::new(); cols * rows];
    for (i, p) in pts.iter().enumerate() {
        let cx = (((p.lon - minx) / CELL_DEG) as usize).min(cols - 1);
        let cy = (((p.lat - miny) / CELL_DEG) as usize).min(rows - 1);
        buckets[cy * cols + cx].push(i);
    }

    // One entry per distinct name, in first-seen order.
    let mut names: Vec<&str> = Vec::new();
    let mut index: HashMap<&str, u16> = HashMap::new();

    let mut order: Vec<usize> = Vec::with_capacity(pts.len());
    let mut offsets: Vec<u32> = Vec::with_capacity(cols * rows + 1);
    for b in buckets.iter() {
        offsets.push(order.len() as u32);
        order.extend_from_slice(b);
    }
    offsets.push(order.len() as u32);

    let mut body = Vec::with_capacity(order.len() * 11);
    for &i in &order {
        let p = &pts[i];
        body.extend_from_slice(&(((p.lat * 1e7).round() as i32)).to_be_bytes());
        body.extend_from_slice(&(((p.lon * 1e7).round() as i32)).to_be_bytes());
        body.push(p.kind);
        let ni = match p.name.as_deref() {
            Some(n) if !n.is_empty() && n.len() <= MAX_NAME => {
                match index.get(n) {
                    Some(&k) => k,
                    None if names.len() < 0xFFFF => {
                        let k = names.len() as u16;
                        names.push(n);
                        index.insert(n, k);
                        k
                    }
                    None => 0xFFFF,
                }
            }
            _ => 0xFFFF,
        };
        body.extend_from_slice(&ni.to_be_bytes());
    }

    let mut out = Vec::with_capacity(52 + offsets.len() * 4 + body.len());
    out.extend_from_slice(b"WAL1");
    out.push(1);                                   // version
    out.push(3);                                   // kinds
    out.extend_from_slice(&(names.len() as u16).to_be_bytes());
    out.extend_from_slice(&(order.len() as u32).to_be_bytes());
    for v in [minx, miny, maxx, maxy] {
        out.extend_from_slice(&v.to_be_bytes());
    }
    out.extend_from_slice(&(cols as u32).to_be_bytes());
    out.extend_from_slice(&(rows as u32).to_be_bytes());
    for o in &offsets {
        out.extend_from_slice(&o.to_be_bytes());
    }
    out.extend_from_slice(&body);
    for n in &names {
        out.push(n.len() as u8);
        out.extend_from_slice(n.as_bytes());
    }
    out
}
