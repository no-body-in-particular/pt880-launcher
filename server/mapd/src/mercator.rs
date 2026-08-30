//! Web Mercator, matching lib.php exactly.
//!
//! Tile numbers are global: a z15 tile has the same coordinates whichever
//! country it falls in, which is what lets the watch keep one tile cache for
//! the whole world.

pub const TILE_PX: i32 = 256;
pub const CELL_DEG: f64 = 0.01;

pub fn lon_to_tile(lon: f64, z: u8) -> f64 {
    (lon + 180.0) / 360.0 * (1u32 << z) as f64
}

pub fn lat_to_tile(lat: f64, z: u8) -> f64 {
    let r = lat.to_radians();
    (1.0 - (r.tan() + 1.0 / r.cos()).ln() / std::f64::consts::PI) / 2.0 * (1u32 << z) as f64
}

pub fn tile_to_lon(x: f64, z: u8) -> f64 {
    x / (1u32 << z) as f64 * 360.0 - 180.0
}

pub fn tile_to_lat(y: f64, z: u8) -> f64 {
    let n = std::f64::consts::PI * (1.0 - 2.0 * y / (1u32 << z) as f64);
    n.sinh().atan().to_degrees()
}

/// west, south, east, north
pub fn tile_bbox(z: u8, x: i32, y: i32) -> (f64, f64, f64, f64) {
    (
        tile_to_lon(x as f64, z),
        tile_to_lat((y + 1) as f64, z),
        tile_to_lon((x + 1) as f64, z),
        tile_to_lat(y as f64, z),
    )
}
