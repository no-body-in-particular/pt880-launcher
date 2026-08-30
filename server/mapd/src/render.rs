//! Drawing a tile.
//!
//! An 8-bit palette PNG, 256x256, matching what lib.php produces closely
//! enough that a card holding tiles from both does not show a seam. Ground
//! cover underneath, then buildings, then roads brightening with importance.

use crate::mercator::{lat_to_tile, lon_to_tile, tile_bbox, TILE_PX};
use crate::palette::{class_spec, PALETTE};
use crate::store::Country;

pub struct Canvas {
    px: Vec<u8>,
}

impl Canvas {
    pub fn new() -> Canvas {
        Canvas {
            px: vec![0u8; (TILE_PX * TILE_PX) as usize],
        }
    }

    #[inline]
    fn set(&mut self, x: i32, y: i32, c: u8) {
        if x >= 0 && y >= 0 && x < TILE_PX && y < TILE_PX {
            self.px[(y * TILE_PX + x) as usize] = c;
        }
    }

    /// A line of the given thickness, as a square brush walked along it -
    /// which is what GD does, and matching it matters more than elegance.
    fn line(&mut self, x0: i32, y0: i32, x1: i32, y1: i32, c: u8, thick: i32) {
        let dx = (x1 - x0).abs();
        let dy = -(y1 - y0).abs();
        let sx = if x0 < x1 { 1 } else { -1 };
        let sy = if y0 < y1 { 1 } else { -1 };
        let mut err = dx + dy;
        let (mut x, mut y) = (x0, y0);
        let half = thick / 2;
        loop {
            if thick <= 1 {
                self.set(x, y, c);
            } else {
                for ox in -half..(thick - half) {
                    for oy in -half..(thick - half) {
                        self.set(x + ox, y + oy, c);
                    }
                }
            }
            if x == x1 && y == y1 {
                break;
            }
            let e2 = 2 * err;
            if e2 >= dy {
                err += dy;
                x += sx;
            }
            if e2 <= dx {
                err += dx;
                y += sy;
            }
        }
    }

    fn rect(&mut self, x0: i32, y0: i32, x1: i32, y1: i32, c: u8) {
        for y in y0.max(0)..=y1.min(TILE_PX - 1) {
            for x in x0.max(0)..=x1.min(TILE_PX - 1) {
                self.px[(y * TILE_PX + x) as usize] = c;
            }
        }
    }

    /// Even-odd scanline fill, as imagefilledpolygon does.
    fn polygon(&mut self, pts: &[(i32, i32)], c: u8) {
        if pts.len() < 3 {
            return;
        }
        let (mut top, mut bot) = (i32::MAX, i32::MIN);
        for p in pts {
            top = top.min(p.1);
            bot = bot.max(p.1);
        }
        top = top.max(0);
        bot = bot.min(TILE_PX - 1);
        let mut xs: Vec<i32> = Vec::with_capacity(16);
        for y in top..=bot {
            xs.clear();
            let mut j = pts.len() - 1;
            for i in 0..pts.len() {
                let (xi, yi) = pts[i];
                let (xj, yj) = pts[j];
                if (yi <= y && yj > y) || (yj <= y && yi > y) {
                    let t = (y - yi) as f64 / (yj - yi) as f64;
                    xs.push(xi + (t * (xj - xi) as f64) as i32);
                }
                j = i;
            }
            xs.sort_unstable();
            let mut k = 0;
            while k + 1 < xs.len() {
                self.rect(xs[k], y, xs[k + 1], y, c);
                k += 2;
            }
        }
    }

    /// A palette PNG at the smallest bit depth the tile actually needs.
    ///
    /// The palette has thirty-two entries but a tile uses far fewer: measured
    /// over a real block, 251 of 256 tiles use sixteen colours or under and
    /// six use four or under. Written at eight bits a pixel that is half the
    /// pixel data wasted, on files that go to a watch over wifi.
    ///
    /// So the tile is given its own palette containing only what it uses, and
    /// the depth that fits. PNG allows a palette per image, so this costs a
    /// few bytes of PLTE and buys half the raster. The picture is identical -
    /// the same colours in the same places - which is why tiles written
    /// before this change need not be re-fetched.
    pub fn to_png(&self) -> Vec<u8> {
        // Which entries this tile actually uses, and where each lands in the
        // compact palette.
        let mut used = [false; 256];
        for &p in self.px.iter() {
            used[p as usize] = true;
        }
        let mut remap = [0u8; 256];
        let mut plte: Vec<u8> = Vec::with_capacity(96);
        let mut count = 0usize;
        for i in 0..PALETTE.len() {
            if used[i] {
                remap[i] = count as u8;
                let c = PALETTE[i];
                plte.extend_from_slice(&c);
                count += 1;
            }
        }
        // Anything outside the palette should not exist, but a tile is not
        // worth losing over it: map it to the background.
        for i in PALETTE.len()..256 {
            if used[i] {
                remap[i] = 0;
            }
        }
        if count == 0 {
            plte.extend_from_slice(&PALETTE[0]);
            count = 1;
        }

        // Packing below eight bits is not always a win. A run of identical
        // pixels compresses as a run of identical bytes at eight bits; at
        // four, two unrelated pixels share a byte and deflate sees 256
        // possible symbols where it saw sixteen. On a detailed tile that
        // costs more than the packing saves. Measured per tile below, and
        // the smaller of the two is kept.
        let depth = if count <= 2 {
            png::BitDepth::One
        } else if count <= 4 {
            png::BitDepth::Two
        } else if count <= 16 {
            png::BitDepth::Four
        } else {
            png::BitDepth::Eight
        };
        let bits = match depth {
            png::BitDepth::One => 1usize,
            png::BitDepth::Two => 2,
            png::BitDepth::Four => 4,
            _ => 8,
        };

        let w = TILE_PX as usize;
        let stride = (w * bits + 7) / 8;
        let mut packed = vec![0u8; stride * TILE_PX as usize];
        let per_byte = 8 / bits;
        for y in 0..TILE_PX as usize {
            let row = &self.px[y * w..(y + 1) * w];
            let out = &mut packed[y * stride..(y + 1) * stride];
            for (x, &p) in row.iter().enumerate() {
                let v = remap[p as usize];
                if bits == 8 {
                    out[x] = v;
                } else {
                    // Most significant bits first, as PNG requires.
                    let shift = 8 - bits * (x % per_byte + 1);
                    out[x / per_byte] |= v << shift;
                }
            }
        }

        let packed_png = encode(&packed, depth, &plte);
        if bits == 8 {
            return packed_png;
        }
        // The same tile at eight bits, for comparison. Encoding twice costs
        // about a millisecond and the answer is worth having: whichever is
        // smaller is what the watch downloads.
        let mut flat = vec![0u8; w * TILE_PX as usize];
        for (i, &p) in self.px.iter().enumerate() {
            flat[i] = remap[p as usize];
        }
        let flat_png = encode(&flat, png::BitDepth::Eight, &plte);
        if flat_png.len() < packed_png.len() { flat_png } else { packed_png }
    }
}

/// One palette PNG, written without filtering.
///
/// Filtering helps a photograph, where neighbouring bytes are nearly equal,
/// and hurts a palette image, where they are unrelated indices. Left on the
/// encoder's default this produced tiles nearly twice the size GD manages.
fn encode(data: &[u8], depth: png::BitDepth, plte: &[u8]) -> Vec<u8> {
    let mut out = Vec::new();
    {
        let mut enc = png::Encoder::new(&mut out, TILE_PX as u32, TILE_PX as u32);
        enc.set_color(png::ColorType::Indexed);
        enc.set_depth(depth);
        enc.set_palette(plte.to_vec());
            // Filtering helps a photograph, where neighbouring bytes are
            // nearly equal, and hurts a palette image, where they are
            // unrelated indices. Left on the encoder's default this produced
            // tiles nearly twice the size GD manages.
        enc.set_compression(png::Compression::Best);
        enc.set_filter(png::FilterType::NoFilter);
        enc.set_adaptive_filter(png::AdaptiveFilterType::NonAdaptive);
        let mut wr = enc.write_header().expect("png header");
        wr.write_image_data(data).expect("png data");
    }
    out
}

pub fn render_tile(c: &Country, z: u8, x: i32, y: i32) -> Vec<u8> {
    let mut cv = Canvas::new();
    let (w, s, e, n) = tile_bbox(z, x, y);

    // A margin, so a road crossing the edge is drawn up to it rather than
    // stopping short and leaving a seam between tiles.
    let mw = (e - w) * 0.15;
    let mh = (n - s) * 0.15;

    let scale = (1u32 << z) as f64 * TILE_PX as f64;
    let px_of = |lon: f64| ((lon_to_tile(lon, z) - x as f64) * TILE_PX as f64) as i32;
    let py_of = |lat: f64| ((lat_to_tile(lat, z) - y as f64) * TILE_PX as f64) as i32;
    let _ = scale;

    // Ground cover, underneath everything. Context, never competing with the
    // route drawn on top of it.
    let mut poly: Vec<(i32, i32)> = Vec::with_capacity(64);
    if z >= 12 {
        let (cells, big) = c.area_cells(w - mw, s - mh, e + mw, n + mh);
        // Water last, so a lake inside a wood draws as a lake rather than
        // whichever came back from the database first.
        for pass in 0..2 {
            for cell in cells.iter() {
                for a in cell.iter() {
                    let water = a.cls == 1 || a.cls == 2;
                    if water != (pass == 1) {
                        continue;
                    }
                    poly.clear();
                    poly.extend(a.pts.iter().map(|p| (px_of(p.0), py_of(p.1))));
                    cv.polygon(&poly, a.cls.clamp(1, 15) as u8);
                }
            }
            for a in big.iter() {
                let water = a.cls == 1 || a.cls == 2;
                if water != (pass == 1) || !crate::store::overlaps(&a.pts, w - mw, s - mh, e + mw, n + mh) {
                    continue;
                }
                poly.clear();
                poly.extend(a.pts.iter().map(|p| (px_of(p.0), py_of(p.1))));
                cv.polygon(&poly, a.cls.clamp(1, 15) as u8);
            }
        }
    }

    // Buildings. imagefilledrectangle covers both endpoints, so a box drawn
    // from px0 to px1 is a pixel wider than it is - which at three pixels
    // across turned a city tile into one solid block of fill.
    if z >= 14 {
        for cell in c.bldg_cells(w, s, e, n).iter() {
        for b in cell.iter() {
            if b[2] < w || b[0] > e || b[3] < s || b[1] > n {
                continue;
            }
            let px0 = px_of(b[0]);
            let py0 = py_of(b[3]);
            let mut px1 = px_of(b[2]);
            let mut py1 = py_of(b[1]);
            if px1 > px0 {
                px1 -= 1;
            }
            if py1 > py0 {
                py1 -= 1;
            }
            cv.rect(px0, py0, px1, py1, 3);
        }
        }
    }

    // Roads, least important first, so a motorway is never buried under the
    // service road that crosses it.
    let cells = c.segments(w - mw, s - mh, e + mw, n + mh, z);
    let mut segs: Vec<(i32, &Vec<(f64, f64)>)> = Vec::new();
    for cell in cells.iter() {
        for seg in cell.iter() {
            // Drawn only once the zoom is close enough for it to mean
            // something: a footpath belongs on a tile you could walk from,
            // not on one showing half a province.
            if seg.minzoom <= z as i32 {
                segs.push((seg.cls, &seg.pts));
            }
        }
    }
    segs.sort_by(|a, b| b.0.cmp(&a.0));

    for (cls, pts) in segs {
        let (thick, colour) = class_spec(cls);
        let mut prev: Option<(i32, i32)> = None;
        for p in pts.iter() {
            let cur = (px_of(p.0), py_of(p.1));
            if let Some(q) = prev {
                cv.line(q.0, q.1, cur.0, cur.1, colour, thick);
            }
            prev = Some(cur);
        }
    }

    cv.to_png()
}
