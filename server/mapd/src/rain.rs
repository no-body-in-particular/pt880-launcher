//! Tomorrow's rain, from Buienradar, packed for the watch.
//!
//! Buienradar's radar page extrapolates the last few frames and is good for
//! two hours. What the watch wants is a different question - "will I get wet
//! on the way back" - and that is answered by their `rain48hour` product,
//! which is a weather model rather than an extrapolation and runs out to two
//! days. This takes the first 24 hours of it.
//!
//! ## Where the numbers come from
//!
//! Three undocumented endpoints, all public and all unauthenticated:
//!
//! ```text
//! image-lite.buienradar.nl/3.0/metadata/radarMapRain1hNL?...   which frames exist
//! processing-cdn.buienradar.nl/processing/nl/rain48hour/...    one hourly frame
//! ```
//!
//! The metadata is a small JSON naming the model run and giving the URL and
//! valid time of every frame in it. Scraping the HTML page works too and was
//! how this started, but the page is 180 kB of markup around the same 4 kB of
//! JSON, and the JSON is what their own front end fetches.
//!
//! Timestamps in it are **UTC**, which is worth stating because nothing in the
//! response says so and their point API next door answers in Amsterdam local
//! time. Confirmed by sampling: the map agreed with the point series only at a
//! two-hour shift, which was the offset in force when this was written.
//!
//! Runs are hourly, not the six-hourly their FAQ claims - measured by asking
//! for successive run directories, where the 09:00 run was already there at
//! 09:55 and 10:00 appeared shortly after. The first frame of a run is run+2h.
//!
//! ## Georeference
//!
//! The frames carry no georeference at all. Their front end draws them as a
//! Leaflet image overlay stretched into a fixed box, and that box is in their
//! own configuration:
//!
//! ```text
//! nlRadarWebMercator: { bounds: [[54.8, 0], [49.5, 10]], isWebMercator: true }
//! ```
//!
//! So: longitude linear across the width from 0 to 10 E, and Web Mercator y
//! linear down the height from 54.8 N to 49.5 N. The image's own aspect ratio
//! does not match that box and is not meant to - it is stretched, not fitted,
//! so the pixel grid is simply the model's and carries no meaning of its own.
//!
//! Checked rather than assumed. Rain pixels were projected back to lat/lon and
//! the same coordinates asked of Buienradar's point nowcast: of eight pixels
//! picked where the map said dry, the point series said dry at all eight, and
//! of eighteen where the map said wet it said wet at thirteen - the five it
//! disagreed about were all the very faintest class, which is under a tenth of
//! a millimetre an hour and which the two products round differently. A
//! projection that was wrong by even a few kilometres would not produce that.
//!
//! ## Colour is the only channel
//!
//! The frames are palette PNGs with no data behind the colours, so intensity
//! has to be read back out of the ramp. The ramp is two segments: a light
//! lavender that darkens through blue, and then a purple that brightens
//! through magenta to red. Green falls monotonically along the first and sits
//! near-constant along the second, which is what `ramp` below keys on.
//!
//! Fitted against the point nowcast, which reports a raw byte with a
//! documented `mm/h = 10^((v-109)/32)`, the ramp position t comes out at
//! `v = 97.2 + 55.4 t` over 37 paired samples, with a residual of 10.6 raw
//! units - a third of a decade, so **roughly a factor of two**. That is a
//! display scale and is not to be presented as a measurement. It is enough to
//! tell drizzle from a downpour, which is the whole of what the watch needs.

use std::io::Read;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use rayon::prelude::*;

/// The box the frames are stretched into, from Buienradar's own map config:
/// north, west, south, east.
const BOX_N: f64 = 54.8;
const BOX_W: f64 = 0.0;
const BOX_S: f64 = 49.5;
const BOX_E: f64 = 10.0;

/// How many hourly frames to hold. The ask is a day ahead; the product runs to
/// 48 and the second day costs another 23 MB of memory to answer a question
/// nobody asks on a watch.
const FRAMES: usize = 24;

/// How long a run is served before the metadata is asked about again. Runs are
/// hourly and arrive at no fixed minute, so this is a poll rather than a
/// schedule; ten minutes costs 4 kB an hour and bounds the staleness at ten
/// minutes past whenever the new run lands.
const RUN_MAX_AGE: Duration = Duration::from_secs(600);



/// One hourly frame, as the source's own palette indices.
///
/// Indices rather than resolved colours: one byte a pixel instead of four, on
/// twenty-four frames of very nearly a megapixel, and the palette is 256
/// entries whichever way round it is kept.
struct Frame {
    /// Valid time, epoch seconds UTC.
    valid: i64,
    px: Vec<u8>,
    /// Palette index to the colour the source drew, straight-alpha.
    rgba: Vec<[u8; 4]>,
}

/// A model run, decoded and held. Twenty-four frames of 1058x915 is 23 MB,
/// which is the price of answering any area from memory; the alternative is
/// re-fetching and re-decoding 400 kB of PNG per request.
pub struct Run {
    /// Run time, epoch seconds UTC.
    run: i64,
    w: usize,
    h: usize,
    frames: Vec<Frame>,
    fetched: Instant,
}

/// What the service keeps between requests.
pub struct Cache {
    run: Mutex<Option<Arc<Run>>>,
    /// Whether a refresh is already in flight. A run is 24 fetches, and eight
    /// workers all deciding at once that the cache is stale would make that
    /// 192 - to Buienradar, from one address, for one run.
    refreshing: AtomicBool,
}

impl Cache {
    pub fn new() -> Cache {
        Cache {
            run: Mutex::new(None),
            refreshing: AtomicBool::new(false),
        }
    }
}

fn merc_y(lat: f64) -> f64 {
    (std::f64::consts::PI / 4.0 + lat.to_radians() / 2.0)
        .tan()
        .ln()
}

fn merc_lat(y: f64) -> f64 {
    (2.0 * y.exp().atan() - std::f64::consts::PI / 2.0).to_degrees()
}

/// Where a coordinate falls in the source grid, in pixels.
fn source_px(lat: f64, lon: f64, w: usize, h: usize) -> (f64, f64) {
    let yn = merc_y(BOX_N);
    let ys = merc_y(BOX_S);
    (
        (lon - BOX_W) / (BOX_E - BOX_W) * w as f64,
        (yn - merc_y(lat)) / (yn - ys) * h as f64,
    )
}

fn get(url: &str, secs: u64) -> Option<Vec<u8>> {
    // Their CDN serves these to a browser on their own site; say so rather
    // than arriving as an anonymous client with no referer.
    let res = ureq::get(url)
        .set("User-Agent", "mapd/1.0 (+https://coredump.ws/map/)")
        .set("Referer", "https://www.buienradar.nl/")
        .timeout(Duration::from_secs(secs))
        .call()
        .ok()?;
    let mut buf = Vec::new();
    res.into_reader().take(4 << 20).read_to_end(&mut buf).ok()?;
    Some(buf)
}

/// "2026-09-01T10:00:00" to epoch seconds. UTC - see the module comment.
///
/// Written out rather than pulled in, because a date library is a large
/// dependency for one fixed-width format that is never localised and never
/// has an offset on it.
fn parse_time(s: &str) -> Option<i64> {
    let b = s.as_bytes();
    if b.len() < 19 {
        return None;
    }
    let n = |a: usize, z: usize| s.get(a..z)?.parse::<i64>().ok();
    let (y, mo, d) = (n(0, 4)?, n(5, 7)?, n(8, 10)?);
    let (hh, mm, ss) = (n(11, 13)?, n(14, 16)?, n(17, 19)?);
    if !(1..=12).contains(&mo) || !(1..=31).contains(&d) {
        return None;
    }
    Some(days_from_civil(y, mo, d) * 86400 + hh * 3600 + mm * 60 + ss)
}

/// Days since 1970-01-01, by Howard Hinnant's algorithm.
fn days_from_civil(y: i64, mo: i64, d: i64) -> i64 {
    let y = if mo <= 2 { y - 1 } else { y };
    let era = if y >= 0 { y } else { y - 399 } / 400;
    let yoe = y - era * 400;
    let mp = (mo + 9) % 12;
    let doy = (153 * mp + 2) / 5 + d - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146097 + doe - 719468
}

/// Pull one frame's PNG apart into intensity levels.
///
/// The frames are 8-bit palette PNGs with per-index alpha, where the whole
/// background is one fully transparent entry - so alpha, not colour, is what
/// says dry. Read at identity: converting to RGBA would allocate four bytes a
/// pixel to recover what the palette already says in one.
fn decode(png_bytes: &[u8]) -> Option<(usize, usize, Vec<u8>, Vec<[u8; 4]>)> {
    let dec = png::Decoder::new(std::io::Cursor::new(png_bytes));
    let mut reader = dec.read_info().ok()?;
    let mut buf = vec![0u8; reader.output_buffer_size()];
    let info = reader.next_frame(&mut buf).ok()?;
    let (w, h) = (info.width as usize, info.height as usize);

    if info.color_type != png::ColorType::Indexed || info.bit_depth != png::BitDepth::Eight {
        return None;
    }
    let inf = reader.info();
    let pal = inf.palette.as_ref()?.to_vec();
    let trns = inf.trns.as_ref().map(|t| t.to_vec()).unwrap_or_default();

    // One lookup per palette entry rather than per pixel: there are at most
    // 256 of the former and 968,070 of the latter.
    let mut rgba = vec![[0u8; 4]; 256];
    for i in 0..(pal.len() / 3).min(256) {
        rgba[i] = [
            pal[i * 3],
            pal[i * 3 + 1],
            pal[i * 3 + 2],
            *trns.get(i).unwrap_or(&255),
        ];
    }

    buf.truncate(w * h);
    Some((w, h, buf, rgba))
}

/// Ask which frames exist, then fetch them.
///
/// Returns None on any failure rather than a partial run: a run with holes in
/// it would be drawn as dry hours, which is the one wrong answer that matters.
fn fetch_run() -> Option<Run> {
    let meta = get(
        "https://image-lite.buienradar.nl/3.0/metadata/radarMapRain1hNL\
         ?size=full&history=0&forecast=24",
        15,
    )?;
    let meta: serde_json::Value = serde_json::from_slice(&meta).ok()?;
    let run = parse_time(meta.get("timestamp")?.as_str()?)?;

    let mut want: Vec<(i64, String)> = Vec::new();
    for t in meta.get("times")?.as_array()?.iter().take(FRAMES) {
        let at = parse_time(t.get("timestamp")?.as_str()?)?;
        let url = t.get("url")?.as_str()?.to_string();
        // Their CDN and nowhere else. The URL is taken from a response, and a
        // response that has been tampered with should not turn this into a
        // fetcher for whatever it names.
        if !url.starts_with("https://processing-cdn.buienradar.nl/") {
            return None;
        }
        want.push((at, url));
    }
    if want.is_empty() {
        return None;
    }

    // Twenty-four independent fetches of about 16 kB. In series over a warm
    // connection this took several seconds, which is several seconds of a
    // worker thread; the machine has cores and they are otherwise idle here.
    let got: Vec<Option<(usize, usize, Vec<u8>, Vec<[u8; 4]>, i64)>> = want
        .par_iter()
        .map(|(at, url)| {
            // Twice before giving up. A run is all or nothing - a frame that
            // failed would otherwise be drawn as an hour of clear sky - so one
            // flaky fetch out of twenty-four used to cost the whole day, which
            // is exactly what happened the first time this ran.
            let b = match get(url, 20) {
                Some(b) => b,
                None => get(url, 20)?,
            };
            let (w, h, px, rgba) = decode(&b)?;
            Some((w, h, px, rgba, *at))
        })
        .collect();

    let mut frames = Vec::with_capacity(got.len());
    let (mut w, mut h) = (0usize, 0usize);
    for g in got {
        let (fw, fh, px, rgba, at) = g?;
        if w == 0 {
            (w, h) = (fw, fh);
        } else if fw != w || fh != h {
            // The grid is fixed for a run; frames of two sizes would mean the
            // product changed under us and the georeference with it.
            return None;
        }
        frames.push(Frame { valid: at, px, rgba });
    }
    frames.sort_by_key(|f| f.valid);

    Some(Run {
        run,
        w,
        h,
        frames,
        fetched: Instant::now(),
    })
}

/// The run to answer from, refreshing in the background when it is stale.
///
/// A stale run is still a good answer - it is at worst an hour old and the
/// frames it holds run a day forward - so a request never waits for a refresh
/// unless there is nothing at all to serve yet. That first request does wait,
/// because the alternative is telling the watch there is no weather.
fn current(cache: &'static Cache) -> Option<Arc<Run>> {
    let have = cache.run.lock().unwrap().clone();

    let stale = match &have {
        Some(r) => r.fetched.elapsed() > RUN_MAX_AGE,
        None => true,
    };
    if stale && !cache.refreshing.swap(true, Ordering::SeqCst) {
        if have.is_some() {
            std::thread::spawn(move || {
                if let Some(r) = fetch_run() {
                    *cache.run.lock().unwrap() = Some(Arc::new(r));
                }
                cache.refreshing.store(false, Ordering::SeqCst);
            });
        } else {
            let fresh = fetch_run().map(Arc::new);
            if fresh.is_some() {
                *cache.run.lock().unwrap() = fresh.clone();
            }
            cache.refreshing.store(false, Ordering::SeqCst);
            return fresh;
        }
    }
    if have.is_none() {
        // Nothing to serve and somebody else is already fetching it. Waiting
        // is right where failing is not: this is the first request after a
        // restart, the fetch in flight is the one that would answer it, and
        // two browser tabs opened together should not have one of them told
        // there is no weather.
        for _ in 0..80 {
            std::thread::sleep(Duration::from_millis(250));
            let now = cache.run.lock().unwrap().clone();
            if now.is_some() {
                return now;
            }
            if !cache.refreshing.load(Ordering::SeqCst) {
                return None;
            }
        }
    }
    have
}

/// A five by seven font, enough for a clock and a day of the week.
///
/// Drawn here rather than pulled in: a font crate is a large dependency and a
/// large runtime for eleven glyphs, and the alternative - leaving the panels
/// unlabelled - makes the sheet useless, because twenty-four pictures of rain
/// are only a forecast if you can tell which hour each one is.
fn glyph(ch: u8) -> [u8; 7] {
    match ch {
        b'0' => [0x1E, 0x11, 0x13, 0x15, 0x19, 0x11, 0x0E],
        b'1' => [0x04, 0x0C, 0x04, 0x04, 0x04, 0x04, 0x0E],
        b'2' => [0x0E, 0x11, 0x01, 0x02, 0x04, 0x08, 0x1F],
        b'3' => [0x1F, 0x02, 0x04, 0x02, 0x01, 0x11, 0x0E],
        b'4' => [0x02, 0x06, 0x0A, 0x12, 0x1F, 0x02, 0x02],
        b'5' => [0x1F, 0x10, 0x1E, 0x01, 0x01, 0x11, 0x0E],
        b'6' => [0x06, 0x08, 0x10, 0x1E, 0x11, 0x11, 0x0E],
        b'7' => [0x1F, 0x01, 0x02, 0x04, 0x08, 0x08, 0x08],
        b'8' => [0x0E, 0x11, 0x11, 0x0E, 0x11, 0x11, 0x0E],
        b'9' => [0x0E, 0x11, 0x11, 0x0F, 0x01, 0x02, 0x0C],
        b':' => [0x00, 0x04, 0x04, 0x00, 0x04, 0x04, 0x00],
        b'+' => [0x00, 0x04, 0x04, 0x1F, 0x04, 0x04, 0x00],
        b'-' => [0x00, 0x00, 0x00, 0x1F, 0x00, 0x00, 0x00],
        b'h' => [0x10, 0x10, 0x16, 0x19, 0x11, 0x11, 0x11],
        b'm' => [0x00, 0x00, 0x1A, 0x15, 0x15, 0x15, 0x15],
        b'/' => [0x01, 0x01, 0x02, 0x04, 0x08, 0x10, 0x10],
        b'Z' => [0x1F, 0x01, 0x02, 0x04, 0x08, 0x10, 0x1F],
        b'.' => [0x00, 0x00, 0x00, 0x00, 0x00, 0x0C, 0x0C],
        b'n' => [0x00, 0x00, 0x16, 0x19, 0x11, 0x11, 0x11],
        b'o' => [0x00, 0x00, 0x0E, 0x11, 0x11, 0x11, 0x0E],
        b'w' => [0x00, 0x00, 0x11, 0x11, 0x15, 0x15, 0x0A],
        b'r' => [0x00, 0x00, 0x16, 0x19, 0x10, 0x10, 0x10],
        b'a' => [0x00, 0x00, 0x0E, 0x01, 0x0F, 0x11, 0x0F],
        b'i' => [0x04, 0x00, 0x0C, 0x04, 0x04, 0x04, 0x0E],
        b'd' => [0x01, 0x01, 0x0D, 0x13, 0x11, 0x11, 0x0F],
        b'y' => [0x00, 0x00, 0x11, 0x11, 0x0F, 0x01, 0x0E],
        b'b' => [0x10, 0x10, 0x16, 0x19, 0x11, 0x11, 0x1E],
        b'u' => [0x00, 0x00, 0x11, 0x11, 0x11, 0x13, 0x0D],
        b'e' => [0x00, 0x00, 0x0E, 0x11, 0x1F, 0x10, 0x0E],
        b'l' => [0x0C, 0x04, 0x04, 0x04, 0x04, 0x04, 0x0E],
        b's' => [0x00, 0x00, 0x0F, 0x10, 0x0E, 0x01, 0x1E],
        b't' => [0x04, 0x04, 0x1F, 0x04, 0x04, 0x05, 0x02],
        _ => [0; 7],
    }
}

fn text(buf: &mut [u8], w: usize, h: usize, x0: usize, y0: usize, s: &str, c: [u8; 3]) {
    let mut x = x0;
    for &ch in s.as_bytes() {
        if ch == b' ' {
            x += 4;
            continue;
        }
        let g = glyph(ch);
        for (row, bits) in g.iter().enumerate() {
            for col in 0..5 {
                if bits & (1 << (4 - col)) == 0 {
                    continue;
                }
                let (px, py) = (x + col, y0 + row);
                if px < w && py < h {
                    let o = (py * w + px) * 3;
                    buf[o] = c[0];
                    buf[o + 1] = c[1];
                    buf[o + 2] = c[2];
                }
            }
        }
        x += 6;
    }
}

/// The map under the rain, drawn once and used by every panel.
///
/// Rendered as tiles because that is what the renderer makes, then sampled
/// down into the panel. The country is the same in all twenty-four pictures,
/// so rendering it once and compositing onto copies is the difference between
/// one map and twenty-four of them.
fn base_map(app: &crate::App, bx: (f64, f64, f64, f64), w: usize, h: usize) -> Vec<u8> {
    let (minlon, minlat, maxlon, maxlat) = bx;
    let mut out = vec![0u8; w * h * 3];

    let c = match app.stores.at((minlon + maxlon) / 2.0, (minlat + maxlat) / 2.0) {
        Some(c) => c,
        // No map for this country is not a reason to refuse the rain: the
        // panels simply come out on black, which still shows where it falls
        // relative to everything else in the picture.
        None => return out,
    };

    // The lowest zoom whose tiles are still bigger than a panel pixel, capped
    // so a large box cannot ask for thousands of them.
    let mut z = 12u8;
    let (mut x0, mut x1, mut y0, mut y1);
    loop {
        x0 = crate::mercator::lon_to_tile(minlon, z).floor() as i32;
        x1 = crate::mercator::lon_to_tile(maxlon, z).floor() as i32;
        y0 = crate::mercator::lat_to_tile(maxlat, z).floor() as i32;
        y1 = crate::mercator::lat_to_tile(minlat, z).floor() as i32;
        let n = (x1 - x0 + 1) as i64 * (y1 - y0 + 1) as i64;
        if n <= 400 || z <= 6 {
            break;
        }
        z -= 1;
    }

    let want: Vec<(i32, i32)> = (x0..=x1).flat_map(|x| (y0..=y1).map(move |y| (x, y))).collect();
    // Ground cover from twelve whatever the zoom drawn at - see render_px_at.
    let tiles: Vec<((i32, i32), Vec<u8>)> = want
        .par_iter()
        .map(|&(x, y)| ((x, y), crate::render::render_px_at(c, z, x, y, 12).pixels().to_vec()))
        .collect();

    let tpx = crate::mercator::TILE_PX as f64;
    let yn = merc_y(maxlat);
    let ys = merc_y(minlat);
    for py in 0..h {
        let lat = merc_lat(yn - (yn - ys) * (py as f64 + 0.5) / h as f64);
        let ty = crate::mercator::lat_to_tile(lat, z);
        for px in 0..w {
            let lon = minlon + (maxlon - minlon) * (px as f64 + 0.5) / w as f64;
            let tx = crate::mercator::lon_to_tile(lon, z);
            let (ix, iy) = (tx.floor() as i32, ty.floor() as i32);
            let t = match tiles.iter().find(|((a, b), _)| *a == ix && *b == iy) {
                Some((_, t)) => t,
                None => continue,
            };
            let sx = (((tx - ix as f64) * tpx) as usize).min(255);
            let sy = (((ty - iy as f64) * tpx) as usize).min(255);
            let idx = t[sy * 256 + sx] as usize;
            let rgb = crate::palette::PALETTE[idx.min(31)];
            let o = (py * w + px) * 3;
            // Dimmed: the map is context for the rain, and at full strength the
            // motorways compete with the lightest drizzle for attention.
            out[o] = (rgb[0] as u32 * 3 / 4) as u8;
            out[o + 1] = (rgb[1] as u32 * 3 / 4) as u8;
            out[o + 2] = (rgb[2] as u32 * 3 / 4) as u8;
        }
    }
    out
}

/// One frame's rain, composited onto a copy of the map.
///
/// The source's own colours, at the source's own alpha. Buienradar already
/// decided what a millimetre an hour looks like and drew it; re-deriving that
/// from the fifteen levels packed for the watch would be a worse copy of a
/// picture that is already in hand.
fn panel(run: &Run, f: &Frame, base: &[u8], bx: (f64, f64, f64, f64), w: usize, h: usize) -> Vec<u8> {
    let (minlon, minlat, maxlon, maxlat) = bx;
    let mut out = base.to_vec();
    let yn = merc_y(maxlat);
    let ys = merc_y(minlat);
    for py in 0..h {
        let lat = merc_lat(yn - (yn - ys) * (py as f64 + 0.5) / h as f64);
        for px in 0..w {
            let lon = minlon + (maxlon - minlon) * (px as f64 + 0.5) / w as f64;
            // Sampled from the source grid rather than from the packed cells:
            // this is a picture on a screen, not a byte budget over the air, so
            // there is no reason to look at it through the watch's grid.
            let (sx, sy) = source_px(lat, lon, run.w, run.h);
            if sx < 0.0 || sy < 0.0 {
                continue;
            }
            let (sx, sy) = (sx as usize, sy as usize);
            if sx >= run.w || sy >= run.h {
                continue;
            }
            let c = f.rgba[f.px[sy * run.w + sx] as usize];
            if c[3] == 0 {
                continue;
            }
            let a = c[3] as u32;
            let o = (py * w + px) * 3;
            for k in 0..3 {
                out[o + k] = ((c[k] as u32 * a + out[o + k] as u32 * (255 - a)) / 255) as u8;
            }
        }
    }
    out
}

/// How tall the base is for a given width, from the shape of the ground.
fn base_h(bx: (f64, f64, f64, f64), w: usize) -> usize {
    let aspect = (merc_y(bx.3) - merc_y(bx.1)) / (bx.2 - bx.0).to_radians();
    ((w as f64 * aspect).round() as usize).clamp(40, 4000)
}

/// Wide enough that a panel is never scaled up, which is the only thing that
/// would show that this is a stored picture rather than a drawn one.
const BASE_W: usize = 600;

/// The country, drawn once and compiled in.
///
/// Rendering it from the store costs thirteen seconds, because a country at
/// this zoom is a couple of hundred tiles and each asks for the ground cover
/// across twenty kilometres, overlapping its neighbours heavily. That is a
/// long time to make somebody wait, it has to be paid again every time the
/// service restarts, and it needs the road store to be present at all.
///
/// None of which buys anything: the coastline of the Netherlands does not
/// change between deploys. So it is drawn by hand with `--make-base`, checked
/// in, and read from the binary.
const BASE_PNG: &[u8] = include_bytes!("netherlands-base.png");

/// The box `BASE_PNG` was drawn for, printed by `--make-base`. These two go
/// together: an image and the coordinates it was drawn for that disagree is a
/// map that is wrong everywhere by a little, and nothing about the picture
/// would look wrong.
const BASE_BOX: (f64, f64, f64, f64) = (3.32863, 50.74230, 7.26713, 53.53580);

/// The stored country, decoded once.
fn base_image() -> &'static (Vec<u8>, usize, usize) {
    static BASE: std::sync::OnceLock<(Vec<u8>, usize, usize)> = std::sync::OnceLock::new();
    BASE.get_or_init(|| {
        let dec = png::Decoder::new(std::io::Cursor::new(BASE_PNG));
        let mut reader = dec.read_info().expect("base png");
        let mut buf = vec![0u8; reader.output_buffer_size()];
        let info = reader.next_frame(&mut buf).expect("base frame");
        buf.truncate(info.buffer_size());
        (buf, info.width as usize, info.height as usize)
    })
}

/// The stored country at the size a panel wants it.
///
/// Averaged rather than sampled: this is always a reduction - 600 wide down to
/// a couple of hundred - and taking one pixel in three of a road network one
/// pixel wide drops most of it and makes the rest crawl as the size changes.
fn base_scaled(w: usize, h: usize) -> Vec<u8> {
    let (src, sw, sh) = base_image();
    let mut out = vec![0u8; w * h * 3];
    for y in 0..h {
        let y0 = y * sh / h;
        let y1 = (((y + 1) * sh + h - 1) / h).min(*sh).max(y0 + 1);
        for x in 0..w {
            let x0 = x * sw / w;
            let x1 = (((x + 1) * sw + w - 1) / w).min(*sw).max(x0 + 1);
            let (mut r, mut g, mut b, mut n) = (0u32, 0u32, 0u32, 0u32);
            for sy in y0..y1 {
                for sx in x0..x1 {
                    let o = (sy * sw + sx) * 3;
                    r += src[o] as u32;
                    g += src[o + 1] as u32;
                    b += src[o + 2] as u32;
                    n += 1;
                }
            }
            let o = (y * w + x) * 3;
            out[o] = (r / n.max(1)) as u8;
            out[o + 1] = (g / n.max(1)) as u8;
            out[o + 2] = (b / n.max(1)) as u8;
        }
    }
    out
}

fn hhmm(t: i64) -> String {
    let s = t.rem_euclid(86400);
    format!("{:02}:{:02}", s / 3600, s % 3600 / 60)
}

/// The whole day as one animation.
///
/// A browser asked for a picture of the weather, and the weather moves. This
/// began as a grid of twenty-four stills, which made the reader do the
/// animating and was dropped once this worked: two renderings of one forecast
/// is a second thing to keep in step, and the loop answers "when does it
/// start" as well as the grid did.
///
/// The palette is built from the frames rather than fixed. A GIF has 256
/// colours and these images use rather few: the map is 32, and the rain is
/// whatever Buienradar's ramp put in this particular run. Counting them and
/// keeping the commonest is exact for nearly every pixel, where a fixed
/// palette would band the ramp.
fn animation(run: &Run, pw: usize, now: i64) -> Vec<u8> {
    let bx = BASE_BOX;
    let ph = base_h(bx, pw);
    let base = base_scaled(pw, ph);

    let mut frames: Vec<Vec<u8>> = run
        .frames
        .par_iter()
        .map(|f| panel(run, f, &base, bx, pw, ph))
        .collect();

    // The hour, on the picture. Without it the loop says when it rains only
    // by how far round it has got, which is not something anybody can read.
    for (i, img) in frames.iter_mut().enumerate() {
        let ahead = (run.frames[i].valid - now + 3599).div_euclid(3600).max(0);
        let lab = format!("{}Z  +{}h", hhmm(run.frames[i].valid), ahead);
        // A dark bar behind it, so a white cloud underneath cannot swallow it.
        for y in ph.saturating_sub(13)..ph {
            for x in 0..pw {
                let o = (y * pw + x) * 3;
                img[o] = img[o] / 4;
                img[o + 1] = img[o + 1] / 4;
                img[o + 2] = img[o + 2] / 4;
            }
        }
        text(img, pw, ph, 3, ph.saturating_sub(11), &lab, [225, 231, 245]);
        // Which model run this is. A forecast with no age on it cannot be
        // told from one that stopped updating three days ago.
        text(img, pw, ph, 3, 3, &format!("run {}Z", hhmm(run.run)), [120, 128, 145]);
        // Their terms for the free data ask for the source to be credited.
        // There is no page around this any more, so it goes on the frames -
        // where it also cannot be separated from the data by being saved and
        // sent on, which a line of HTML could.
        let src = "buienradar.nl";
        if pw > src.len() * 6 + 40 {
            text(img, pw, ph, pw - src.len() * 6 - 3, ph.saturating_sub(11), src,
                 [150, 158, 175]);
        }
    }

    // A GIF has 256 colours and these frames want more, so they have to be
    // chosen. Counting them and keeping the commonest does not work here: the
    // map is scaled down by averaging, which turns 32 flat colours into
    // thousands of near-identical dark ones, and those are far and away the
    // most numerous - they take the whole palette and the rain arrives as
    // grey. So the choosing is left to a quantiser, which weighs a colour by
    // how far it is from the others rather than by how often it occurs, and
    // keeps the reds that only ever cover a few dozen pixels.
    //
    // Index 255 is kept back for "same as the frame before" - see below.
    const CLEAR: u8 = 255;
    let mut sample: Vec<u8> = Vec::with_capacity(frames.len() * pw * ph * 4 / 4);
    for img in frames.iter() {
        for px in img.chunks_exact(3) {
            sample.extend_from_slice(&[px[0], px[1], px[2], 255]);
        }
    }
    let nq = color_quant::NeuQuant::new(10, 255, &sample);
    let mut pal = nq.color_map_rgb();
    while pal.len() < 768 {
        pal.push(0);
    }
    drop(sample);

    // Colour to index, worked out once per distinct colour rather than once
    // per pixel: there are a few thousand of the former and millions of the
    // latter.
    let mut index: std::collections::HashMap<u32, u8> = std::collections::HashMap::new();

    let mut out = Vec::new();
    {
        let mut enc = gif::Encoder::new(&mut out, pw as u16, ph as u16, &pal).expect("gif");
        enc.set_repeat(gif::Repeat::Infinite).ok();
        let mut prev: Option<Vec<u8>> = None;
        for img in frames.iter() {
            let mut buf = vec![0u8; pw * ph];
            for (o, px) in img.chunks_exact(3).enumerate() {
                let c = (px[0] as u32) << 16 | (px[1] as u32) << 8 | px[2] as u32;
                buf[o] = match index.get(&c) {
                    Some(&i) => i,
                    None => {
                        let i = nq.index_of(&[px[0], px[1], px[2], 255]).min(254) as u8;
                        index.insert(c, i);
                        i
                    }
                };
            }
            // Everything unchanged since the last frame is left transparent
            // and the previous frame shows through. The country is the same in
            // all twenty-four, so this is nearly the whole picture: it takes
            // the file from 1.7 MB to a fraction of it, and LZW compresses a
            // long run of one index far better than the map it replaces.
            let mut send = buf.clone();
            if let Some(p) = &prev {
                for i in 0..send.len() {
                    if send[i] == p[i] {
                        send[i] = CLEAR;
                    }
                }
            }
            prev = Some(buf);

            let mut fr = gif::Frame::from_indexed_pixels(pw as u16, ph as u16, send, None);
            fr.transparent = Some(CLEAR);
            fr.dispose = gif::DisposalMethod::Keep;
            // A second an hour, so the day takes about twenty-four to run.
            // At two fifths of a second it was over before a shower could be
            // followed across the country, which is the only reason to watch
            // it move rather than read the hours off a list.
            fr.delay = 100;
            enc.write_frame(&fr).ok();
        }
    }
    out
}

/// Draw the country once, for compiling in. See `--make-base` in main.rs.
pub fn make_base(app: &crate::App) -> Option<(Vec<u8>, (f64, f64, f64, f64), usize, usize)> {
    let c = app.stores.get("netherlands")?;
    let bx = (c.bbox.0, c.bbox.1, c.bbox.2, c.bbox.3);
    let w = BASE_W;
    let h = base_h(bx, w);
    let rgb = base_map(app, bx, w, h);
    let mut out = Vec::new();
    {
        let mut enc = png::Encoder::new(&mut out, w as u32, h as u32);
        enc.set_color(png::ColorType::Rgb);
        enc.set_depth(png::BitDepth::Eight);
        enc.set_compression(png::Compression::Best);
        let mut wr = enc.write_header().ok()?;
        wr.write_image_data(&rgb).ok()?;
    }
    Some((out, bx, w, h))
}

/// One endpoint, one answer: the next 24 hours over the Netherlands, as an
/// animation.
///
/// It used to serve four things - a packed grid for the watch, a contact sheet,
/// a text listing and an HTML page around them - selected by `fmt`. All of it
/// went, and the watch shows this same GIF. That is a real loss of precision:
/// nothing here now knows whether it is raining at a particular point, so the
/// watch can no longer say "rain in 3 h" for where you are standing, only show
/// you the country and let you look. It is a great deal less to keep working,
/// and it is what the picture was wanted for.
///
/// `px` sets the width; the height follows the shape of the country. The watch
/// asks for a small one and a browser gets the default.
pub fn handle(
    cache: &'static Cache,
    r: tiny_http::Request,
    q: &std::collections::HashMap<String, String>,
) {
    let run = match current(cache) {
        Some(run) => run,
        None => return crate::send_status(r, 503, "no rain data"),
    };
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0);

    let pw = q
        .get("px")
        .and_then(|v| v.parse::<usize>().ok())
        .unwrap_or(340)
        .clamp(60, 700);

    crate::send_gif(r, animation(&run, pw, now))
}

/// The one cache, for the lifetime of the service.
///
/// Not a field on `App` because there is exactly one Buienradar and the run it
/// is serving has nothing to do with which country a request is about - unlike
/// every other thing this service holds, which is per country and belongs
/// there.
pub fn cache() -> &'static Cache {
    static RAIN: std::sync::OnceLock<Cache> = std::sync::OnceLock::new();
    RAIN.get_or_init(Cache::new)
}
