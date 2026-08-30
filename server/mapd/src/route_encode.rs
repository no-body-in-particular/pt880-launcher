//! The watch's route format, encoded from what the routing service answers.
//!
//! Kept byte-identical to route.php, because the watch parses it directly and
//! a route from either source has to read the same.
//!
//!     "WRT2"  u32 metres  u16 steps  u16 points
//!     per step:  u8 turn  u8 exit  u16 metres  i32 lat  i32 lon      (x1e7)
//!     then the line: the first point as two i32, the rest as i16 deltas at 1e6
//!
//! The exit byte is which exit to take at a roundabout, and 0 everywhere else. OSRM has always
//! reported it and this dropped it, so the watch could only say "at the roundabout" - true of
//! every exit from it, and not the part a driver needs. At a roundabout the number is the whole
//! instruction, because the direction says nothing on its own.
//!
//! WRT1 was this without that byte, and the watch still reads it: a route cached before the
//! change, or a server that has not been updated, keeps working and simply says less.

use serde_json::Value;

const DEPART: u8 = 0;
const STRAIGHT: u8 = 1;
const SLIGHT_LEFT: u8 = 2;
const LEFT: u8 = 3;
const SHARP_LEFT: u8 = 4;
const SLIGHT_RIGHT: u8 = 5;
const RIGHT: u8 = 6;
const SHARP_RIGHT: u8 = 7;
const UTURN: u8 = 8;
const ROUNDABOUT: u8 = 9;
const ARRIVE: u8 = 10;

fn turn_code(kind: &str, modifier: &str) -> u8 {
    match kind {
        "depart" => return DEPART,
        "arrive" => return ARRIVE,
        "rotary" | "roundabout" | "exit rotary" | "exit roundabout" => return ROUNDABOUT,
        _ => {}
    }
    match modifier {
        "left" => LEFT,
        "slight left" => SLIGHT_LEFT,
        "sharp left" => SHARP_LEFT,
        "right" => RIGHT,
        "slight right" => SLIGHT_RIGHT,
        "sharp right" => SHARP_RIGHT,
        "uturn" => UTURN,
        _ => STRAIGHT,
    }
}

fn enc32(deg: f64) -> u32 {
    ((deg * 1e7).round() as i64 as i32) as u32
}

pub fn encode(json: &str) -> Option<Vec<u8>> {
    let j: Value = serde_json::from_str(json).ok()?;
    if j.get("code")?.as_str()? != "Ok" {
        return None;
    }
    let route = j.get("routes")?.as_array()?.first()?;
    let coords = route.get("geometry")?.get("coordinates")?.as_array()?;
    if coords.len() < 2 {
        return None;
    }

    let mut steps: Vec<(u8, u8, u16, f64, f64)> = Vec::new();
    if let Some(legs) = route.get("legs").and_then(|l| l.as_array()) {
        if let Some(first) = legs.first() {
            if let Some(list) = first.get("steps").and_then(|s| s.as_array()) {
                for s in list {
                    let m = match s.get("maneuver") {
                        Some(m) => m,
                        None => continue,
                    };
                    let loc = match m.get("location").and_then(|l| l.as_array()) {
                        Some(l) if l.len() >= 2 => l,
                        _ => continue,
                    };
                    let kind = m.get("type").and_then(|v| v.as_str()).unwrap_or("");
                    let modifier = m.get("modifier").and_then(|v| v.as_str()).unwrap_or("");
                    let dist = s.get("distance").and_then(|v| v.as_f64()).unwrap_or(0.0);
                    // OSRM puts the exit number on roundabout and rotary manoeuvres, counting
                    // from the entry. Taken whenever it is offered rather than only for the
                    // types mapped to ROUNDABOUT, because the two need not agree: a rotary that
                    // came through as a plain left is still a rotary and the number still holds.
                    let exit = m
                        .get("exit")
                        .and_then(|v| v.as_u64())
                        .unwrap_or(0)
                        .min(255) as u8;
                    steps.push((
                        turn_code(kind, modifier),
                        exit,
                        dist.round().clamp(0.0, 65535.0) as u16,
                        loc[1].as_f64().unwrap_or(0.0),
                        loc[0].as_f64().unwrap_or(0.0),
                    ));
                    if steps.len() >= 250 {
                        break;
                    }
                }
            }
        }
    }

    let count = coords.len().min(65535);
    let metres = route.get("distance").and_then(|v| v.as_f64()).unwrap_or(0.0);

    let mut out = Vec::with_capacity(16 + steps.len() * 12 + count * 4);
    out.extend_from_slice(b"WRT2");
    out.extend_from_slice(&(metres.round() as u32).to_be_bytes());
    out.extend_from_slice(&(steps.len() as u16).to_be_bytes());
    out.extend_from_slice(&(count as u16).to_be_bytes());

    for (turn, exit, dist, lat, lon) in steps.iter() {
        out.push(*turn);
        out.push(*exit);
        out.extend_from_slice(&dist.to_be_bytes());
        out.extend_from_slice(&enc32(*lat).to_be_bytes());
        out.extend_from_slice(&enc32(*lon).to_be_bytes());
    }

    let at = |i: usize, k: usize| coords[i].as_array().and_then(|a| a.get(k)?.as_f64()).unwrap_or(0.0);
    let (mut plat, mut plon) = (at(0, 1), at(0, 0));
    out.extend_from_slice(&enc32(plat).to_be_bytes());
    out.extend_from_slice(&enc32(plon).to_be_bytes());

    for i in 1..count {
        // Clamped rather than dropped: a route is one continuous line and a
        // missing vertex would put a straight cut across it.
        let dlat = (((at(i, 1) - plat) * 1e6).round() as i64).clamp(-32768, 32767) as i16;
        let dlon = (((at(i, 0) - plon) * 1e6).round() as i64).clamp(-32768, 32767) as i16;
        out.extend_from_slice(&dlat.to_be_bytes());
        out.extend_from_slice(&dlon.to_be_bytes());
        plat += dlat as f64 / 1e6;
        plon += dlon as f64 / 1e6;
    }
    Some(out)
}
