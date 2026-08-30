//! The thirty-two colours a tile may use, and what draws in each.
//!
//! Kept identical to lib.php: the two renderers have to produce the same
//! picture or a card holding tiles from both shows the seam.
//!
//! Ground cover occupies 1..15 and stays dark, because it is context and must
//! never compete with the route. Roads take 16..27, brightening with
//! importance, all in the cool half of the wheel so the amber route line the
//! watch draws on top stays unmistakable.

pub const PALETTE: [[u8; 3]; 32] = [
    [0x08, 0x0B, 0x10], // 0  background
    [0x0D, 0x1A, 0x26], // 1  water
    [0x11, 0x22, 0x30], // 2  river, canal, stream
    [0x16, 0x1A, 0x20], // 3  building
    [0x0D, 0x21, 0x14], // 4  forest
    [0x12, 0x29, 0x1A], // 5  park, nature reserve, recreation
    [0x17, 0x31, 0x1F], // 6  grass, meadow, heath, scrub
    [0x1E, 0x24, 0x13], // 7  farmland, orchard, allotment
    [0x1C, 0x1C, 0x22], // 8  industrial, commercial, retail
    [0x13, 0x15, 0x1A], // 9  residential landuse
    [0x1A, 0x23, 0x18], // 10 cemetery
    [0x2A, 0x28, 0x1E], // 11 beach, sand, dune
    [0x0F, 0x22, 0x26], // 12 wetland, marsh
    [0x24, 0x1E, 0x24], // 13 quarry, military, landfill
    [0x36, 0x3B, 0x45], // 14 railway
    [0x20, 0x20, 0x20], // 15 spare
    [0x3F, 0x4A, 0x44], // 16 footway, path, steps
    [0x4A, 0x51, 0x58], // 17 service, track
    [0x4E, 0x6B, 0x52], // 18 cycleway, pedestrian
    [0x66, 0x6E, 0x78], // 19 living street
    [0x7E, 0x87, 0x94], // 20 residential, unclassified
    [0x8E, 0x99, 0xA6], // 21 tertiary link
    [0x9E, 0xAA, 0xB8], // 22 tertiary, secondary link
    [0xB0, 0xBE, 0xCE], // 23 secondary
    [0x7F, 0xA8, 0xD8], // 24 trunk link
    [0x93, 0xBE, 0xEA], // 25 primary, motorway link
    [0xA9, 0xD2, 0xF5], // 26 trunk
    [0xC8, 0xE8, 0xFF], // 27 motorway
    [0x30, 0x30, 0x30], // 28 spare
    [0x30, 0x30, 0x30],
    [0x30, 0x30, 0x30],
    [0x30, 0x30, 0x30],
];

/// class code -> (width, colour index). Codes above ten are railways and
/// waterways, which are drawn first and so end up underneath the roads.
pub fn class_spec(code: i32) -> (i32, u8) {
    match code {
        1 => (3, 27),  // motorway
        2 => (3, 26),  // trunk
        3 => (2, 25),  // primary
        4 => (2, 23),  // secondary
        5 => (2, 22),  // tertiary
        6 => (1, 20),  // unclassified, residential
        7 => (1, 18),  // pedestrian
        8 => (1, 17),  // service, track
        9 => (1, 18),  // cycleway
        10 => (1, 16), // footway, path
        11 => (1, 14), // railway
        12 => (2, 2),  // river, canal
        _ => (1, 18),
    }
}
