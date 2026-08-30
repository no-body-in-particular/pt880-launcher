//! The road, ground-cover and building data, read from the SQLite stores the
//! importers build.
//!
//! The point of this being a service rather than a script is here: a decoded
//! cell stays decoded. Under php-cgi every request began with an empty
//! process, so a block of 256 tiles decoded the same cell a dozen times and
//! threw the lot away at the end. Cells are shared across every request the
//! service ever handles, and a country's connection is opened once.

use rusqlite::{Connection, OpenFlags};
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, RwLock};

use crate::mercator::CELL_DEG;

pub type Pt = (f64, f64);

pub struct Segment {
    pub cls: i32,
    /// The zoom at which this way starts being worth drawing. Kept rather
    /// than folded into the class: a cell is cached once and served at any
    /// zoom, so the filter has to happen when the tile is drawn. Folding it
    /// at a fixed 15 meant a zoomed-out tile was drawn with every footpath
    /// and service road in it.
    pub minzoom: i32,
    pub pts: Vec<Pt>,
}

pub struct Area {
    pub cls: i32,
    pub pts: Vec<Pt>,
}

/// west, south, east, north
#[derive(Clone, Copy)]
pub struct Bbox(pub f64, pub f64, pub f64, pub f64);

pub struct Country {
    pub name: String,
    pub bbox: Bbox,
    pub ways: i64,
    db: Mutex<Connection>,
    has_areas: bool,
    has_bldg: bool,
    // Arc rather than a borrow out of the map: a HashMap moves its values
    // when it rehashes, so a reference handed out earlier would dangle. The
    // Arc costs one atomic per cell lookup and is simply correct.
    segs: RwLock<HashMap<i64, Arc<Vec<Segment>>>>,
    areas: RwLock<HashMap<i64, Arc<Vec<Area>>>>,
    bldgs: RwLock<HashMap<i64, Arc<Vec<[f64; 4]>>>>,
}

/// Geometry is delta encoded and little-endian - unlike the tile and graph
/// formats, which are big-endian because the watch reads them directly. The
/// first point is two int32 at 1e7 degrees, then int16 deltas at 1e6.
fn unpack_geom(b: &[u8]) -> Vec<Pt> {
    if b.len() < 8 {
        return Vec::new();
    }
    let mut x = i32::from_le_bytes([b[0], b[1], b[2], b[3]]) as f64 / 1e7;
    let mut y = i32::from_le_bytes([b[4], b[5], b[6], b[7]]) as f64 / 1e7;
    let mut pts = Vec::with_capacity(b.len() / 4);
    pts.push((x, y));
    let mut o = 8;
    while o + 3 < b.len() {
        x += i16::from_le_bytes([b[o], b[o + 1]]) as f64 / 1e6;
        y += i16::from_le_bytes([b[o + 2], b[o + 3]]) as f64 / 1e6;
        pts.push((x, y));
        o += 4;
    }
    pts
}

/// Cells kept decoded. Beyond this the map is emptied rather than evicted one
/// at a time: it refills from disk in milliseconds, and a country has far
/// more cells than any journey visits.
const MAX_CELLS: usize = 20000;

fn cell_key(cx: i32, cy: i32) -> i64 {
    ((cx as i64) << 32) ^ (cy as i64 & 0xFFFF_FFFF)
}

pub fn cell_of(v: f64) -> i32 {
    (v / CELL_DEG).floor() as i32
}

impl Country {
    pub fn open(path: &Path) -> Option<Country> {
        let db = Connection::open_with_flags(
            path,
            OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_NO_MUTEX,
        )
        .ok()?;
        db.execute_batch("PRAGMA query_only=1; PRAGMA cache_size=-40000;").ok();

        let mut meta = HashMap::new();
        {
            let mut st = db.prepare("SELECT k, v FROM meta").ok()?;
            let rows = st
                .query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?)))
                .ok()?;
            for row in rows.flatten() {
                meta.insert(row.0, row.1);
            }
        }
        let g = |k: &str| meta.get(k).and_then(|v| v.parse::<f64>().ok()).unwrap_or(0.0);
        let has = |t: &str| {
            db.query_row(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?1",
                [t],
                |_| Ok(()),
            )
            .is_ok()
        };

        Some(Country {
            name: meta.get("country").cloned().unwrap_or_default(),
            bbox: Bbox(g("minx"), g("miny"), g("maxx"), g("maxy")),
            ways: meta.get("ways").and_then(|v| v.parse().ok()).unwrap_or(0),
            has_areas: has("area"),
            has_bldg: has("bldg"),
            db: Mutex::new(db),
            segs: RwLock::new(HashMap::new()),
            areas: RwLock::new(HashMap::new()),
            bldgs: RwLock::new(HashMap::new()),
        })
    }

    pub fn covers(&self, lon: f64, lat: f64) -> bool {
        lon >= self.bbox.0 && lon <= self.bbox.2 && lat >= self.bbox.1 && lat <= self.bbox.3
    }

    pub fn area_sq(&self) -> f64 {
        (self.bbox.2 - self.bbox.0) * (self.bbox.3 - self.bbox.1)
    }

    /// Road, rail and waterway segments overlapping a box, at or below a zoom.
    pub fn segments(&self, w: f64, s: f64, e: f64, n: f64, z: u8) -> Vec<Arc<Vec<Segment>>> {
        let (cx0, cx1) = (cell_of(w), cell_of(e));
        let (cy0, cy1) = (cell_of(s), cell_of(n));
        let mut out: Vec<Arc<Vec<Segment>>> = Vec::new();
        for cx in cx0..=cx1 {
            for cy in cy0..=cy1 {
                out.push(self.seg_cell(cx, cy));
            }
        }
        let _ = z;
        out
    }

    fn seg_cell(&self, cx: i32, cy: i32) -> Arc<Vec<Segment>> {
        let key = cell_key(cx, cy);
        {
            let map = self.segs.read().unwrap();
            if let Some(v) = map.get(&key) {
                return v.clone();
            }
        }
        let loaded = Arc::new(self.load_segs(cx, cy));
        let mut map = self.segs.write().unwrap();
        if map.len() > MAX_CELLS {
            map.clear();
        }
        map.entry(key).or_insert(loaded).clone()
    }

    fn load_segs(&self, cx: i32, cy: i32) -> Vec<Segment> {
        let db = self.db.lock().unwrap();
        let mut out = Vec::new();
        if let Ok(mut st) =
            db.prepare_cached("SELECT cls, minzoom, geom FROM seg WHERE cx=?1 AND cy=?2")
        {
            if let Ok(rows) = st.query_map([cx, cy], |r| {
                Ok((
                    r.get::<_, i32>(0)?,
                    r.get::<_, i32>(1)?,
                    r.get::<_, Vec<u8>>(2)?,
                ))
            }) {
                for (cls, minzoom, geom) in rows.flatten() {
                    let pts = unpack_geom(&geom);
                    if pts.len() >= 2 {
                        out.push(Segment { cls, minzoom, pts });
                    }
                }
            }
        }
        out
    }

    /// Ground cover overlapping a box, water last so a lake inside a wood
    /// draws as a lake.
    /// The cells of ground cover a box touches, plus the oversized polygons.
    ///
    /// Handed back as the cached Arcs rather than as copies. Cloning the
    /// geometry per tile cost more than drawing it: a block is 256 tiles over
    /// the same handful of cells, so the copying was done hundreds of times
    /// for data that had not changed.
    pub fn area_cells(&self, w: f64, s: f64, e: f64, n: f64)
        -> (Vec<Arc<Vec<Area>>>, Arc<Vec<Area>>) {
        if !self.has_areas {
            return (Vec::new(), Arc::new(Vec::new()));
        }
        let (cx0, cx1) = (cell_of(w), cell_of(e));
        let (cy0, cy1) = (cell_of(s), cell_of(n));
        let mut out = Vec::new();
        for cx in cx0..=cx1 {
            for cy in cy0..=cy1 {
                out.push(self.area_cell(cx, cy));
            }
        }
        (out, self.big_areas())
    }

    fn area_cell(&self, cx: i32, cy: i32) -> Arc<Vec<Area>> {
        let key = cell_key(cx, cy);
        {
            let map = self.areas.read().unwrap();
            if let Some(v) = map.get(&key) {
                return v.clone();
            }
        }
        let db = self.db.lock().unwrap();
        let mut loaded = Vec::new();
        if let Ok(mut st) = db.prepare_cached("SELECT cls, geom FROM area WHERE cx=?1 AND cy=?2") {
            if let Ok(rows) =
                st.query_map([cx, cy], |r| Ok((r.get::<_, i32>(0)?, r.get::<_, Vec<u8>>(1)?)))
            {
                for (cls, geom) in rows.flatten() {
                    let pts = unpack_geom(&geom);
                    if pts.len() >= 3 {
                        loaded.push(Area { cls, pts });
                    }
                }
            }
        }
        drop(db);
        let mut map = self.areas.write().unwrap();
        if map.len() > MAX_CELLS {
            map.clear();
        }
        map.entry(key).or_insert(Arc::new(loaded)).clone()
    }

    /// The few polygons too large to file by cell - a big lake, a national
    /// park - held once and found by bounding box. There are a couple of
    /// hundred of them, so they are loaded whole and kept.
    fn big_areas(&self) -> Arc<Vec<Area>> {
        let key = i64::MIN;
        {
            let map = self.areas.read().unwrap();
            if let Some(v) = map.get(&key) {
                return v.clone();
            }
        }
        let db = self.db.lock().unwrap();
        let mut loaded = Vec::new();
        if let Ok(mut st) = db.prepare_cached("SELECT cls, geom FROM bigarea") {
            if let Ok(rows) =
                st.query_map([], |r| Ok((r.get::<_, i32>(0)?, r.get::<_, Vec<u8>>(1)?)))
            {
                for (cls, geom) in rows.flatten() {
                    let pts = unpack_geom(&geom);
                    if pts.len() >= 3 {
                        loaded.push(Area { cls, pts });
                    }
                }
            }
        }
        drop(db);
        let mut map = self.areas.write().unwrap();
        map.entry(key).or_insert(Arc::new(loaded)).clone()
    }

    /// Building boxes overlapping a box. Stored packed by cell, eight bytes
    /// each, as sixteen-bit fractions of the cell.
    /// The cells of building boxes a box touches, uncopied for the same
    /// reason as the ground cover.
    pub fn bldg_cells(&self, w: f64, s: f64, e: f64, n: f64) -> Vec<Arc<Vec<[f64; 4]>>> {
        if !self.has_bldg {
            return Vec::new();
        }
        let (cx0, cx1) = (cell_of(w), cell_of(e));
        let (cy0, cy1) = (cell_of(s), cell_of(n));
        let mut out = Vec::new();
        for cx in cx0..=cx1 {
            for cy in cy0..=cy1 {
                out.push(self.bldg_cell(cx, cy));
            }
        }
        out
    }

    fn bldg_cell(&self, cx: i32, cy: i32) -> Arc<Vec<[f64; 4]>> {
        let key = cell_key(cx, cy);
        {
            let map = self.bldgs.read().unwrap();
            if let Some(v) = map.get(&key) {
                return v.clone();
            }
        }
        let db = self.db.lock().unwrap();
        let mut loaded = Vec::new();
        if let Ok(mut st) = db.prepare_cached("SELECT boxes FROM bldg WHERE cx=?1 AND cy=?2") {
            if let Ok(blob) = st.query_row([cx, cy], |r| r.get::<_, Vec<u8>>(0)) {
                let ox = cx as f64 * CELL_DEG;
                let oy = cy as f64 * CELL_DEG;
                let scale = CELL_DEG / 65536.0;
                let mut i = 0;
                while i + 8 <= blob.len() {
                    let q = |o: usize| u16::from_le_bytes([blob[i + o], blob[i + o + 1]]) as f64;
                    loaded.push([
                        ox + q(0) * scale,
                        oy + q(2) * scale,
                        ox + q(4) * scale,
                        oy + q(6) * scale,
                    ]);
                    i += 8;
                }
            }
        }
        drop(db);
        let mut map = self.bldgs.write().unwrap();
        if map.len() > MAX_CELLS {
            map.clear();
        }
        map.entry(key).or_insert(Arc::new(loaded)).clone()
    }
}

pub fn overlaps(pts: &[Pt], w: f64, s: f64, e: f64, n: f64) -> bool {
    let (mut x0, mut y0, mut x1, mut y1) = (f64::MAX, f64::MAX, f64::MIN, f64::MIN);
    for p in pts {
        x0 = x0.min(p.0);
        x1 = x1.max(p.0);
        y0 = y0.min(p.1);
        y1 = y1.max(p.1);
    }
    !(x1 < w || x0 > e || y1 < s || y0 > n)
}

/// Every country database present, opened once and kept.
pub struct Stores {
    pub dir: PathBuf,
    countries: RwLock<HashMap<String, &'static Country>>,
    names: RwLock<Option<Vec<String>>>,
}

impl Stores {
    pub fn new(dir: PathBuf) -> Stores {
        Stores {
            dir,
            countries: RwLock::new(HashMap::new()),
            names: RwLock::new(None),
        }
    }

    /// The countries present.
    ///
    /// Cached: this used to read the directory on every request that did not
    /// name a country, which is a syscall per request to answer a question
    /// whose answer changes when someone builds a new country - about once a
    /// week, and never while a request is in flight.
    pub fn names(&self) -> Vec<String> {
        {
            let c = self.names.read().unwrap();
            if let Some(v) = c.as_ref() {
                return v.clone();
            }
        }
        let out = self.scan_names();
        *self.names.write().unwrap() = Some(out.clone());
        out
    }

    fn scan_names(&self) -> Vec<String> {
        let mut out = Vec::new();
        if let Ok(rd) = std::fs::read_dir(&self.dir) {
            for e in rd.flatten() {
                let p = e.path();
                if p.extension().map(|x| x == "db").unwrap_or(false) {
                    if let Some(stem) = p.file_stem().and_then(|s| s.to_str()) {
                        out.push(stem.to_string());
                    }
                }
            }
        }
        out.sort();
        out
    }

    pub fn get(&self, name: &str) -> Option<&'static Country> {
        if name.is_empty() || !name.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_') {
            return None;
        }
        {
            let map = self.countries.read().unwrap();
            if let Some(c) = map.get(name) {
                return Some(*c);
            }
        }
        let path = self.dir.join(format!("{}.db", name));
        if !path.is_file() {
            return None;
        }
        let c = Country::open(&path)?;
        // Leaked on purpose: a country store lives as long as the service, and
        // this hands out plain references without an Arc on every tile.
        let c: &'static Country = Box::leak(Box::new(c));
        self.countries.write().unwrap().insert(name.to_string(), c);
        Some(c)
    }

    /// Which country's data covers a point - the smallest, where extracts
    /// overlap, because the smaller one is the more local.
    pub fn at(&self, lon: f64, lat: f64) -> Option<&'static Country> {
        let mut best: Option<&'static Country> = None;
        for n in self.names() {
            if let Some(c) = self.get(&n) {
                if c.covers(lon, lat)
                    && best.map(|b| c.area_sq() < b.area_sq()).unwrap_or(true)
                {
                    best = Some(c);
                }
            }
        }
        best
    }
}

/// One of the things beside the road: a camera, a motorway exit, a filling
/// station.
pub struct Point {
    pub kind: u8,
    pub lat: f64,
    pub lon: f64,
    pub name: Option<String>,
}

impl Country {
    /// Points inside a box, or all of them if the box covers the country.
    ///
    /// Read through the cell index the same way ways and areas are, so asking
    /// for a town's worth costs a town's worth rather than a country's. That
    /// is the whole reason this is a query rather than a file: the Netherlands
    /// packs into 111 kB and a continent would not, and a watch only ever
    /// needs what is around it.
    pub fn points(&self, b: Bbox) -> Vec<Point> {
        let cx0 = (b.0 / CELL_DEG).floor() as i64;
        let cx1 = (b.2 / CELL_DEG).floor() as i64;
        let cy0 = (b.1 / CELL_DEG).floor() as i64;
        let cy1 = (b.3 / CELL_DEG).floor() as i64;

        let db = match self.db.lock() {
            Ok(d) => d,
            Err(_) => return Vec::new(),
        };
        let mut st = match db.prepare(
            "SELECT kind, lat, lon, name FROM pt
              WHERE cx BETWEEN ?1 AND ?2 AND cy BETWEEN ?3 AND ?4",
        ) {
            Ok(s) => s,
            // No pt table: a store built before points were imported. Not an
            // error - the watch does without.
            Err(_) => return Vec::new(),
        };
        let rows = st.query_map([cx0, cx1, cy0, cy1], |r| {
            Ok(Point {
                kind: r.get::<_, i64>(0)? as u8,
                lat: r.get(1)?,
                lon: r.get(2)?,
                name: r.get::<_, Option<String>>(3)?,
            })
        });
        let mut out = Vec::new();
        if let Ok(rows) = rows {
            for p in rows.flatten() {
                // The cell index is coarse; the box is what was asked for.
                if p.lat >= b.1 && p.lat <= b.3 && p.lon >= b.0 && p.lon <= b.2 {
                    out.push(p);
                }
            }
        }
        out
    }
}
