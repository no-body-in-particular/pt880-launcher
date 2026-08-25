#!/usr/bin/env python3
"""Draw the launcher icon.

Not a framework drawable: @android:drawable references resolve against the
compile-time platform, and the one that looked right (ic_menu_compass) is not
public in recent android.jars even though it exists on the device. Shipping the
icon removes the dependency entirely.

The icon is the app's own vocabulary -- black ground, one blue accent, the same
palette every screen uses -- with the four apps as quadrant marks around a
watch body, which is what the launcher actually is.

    python3 tools/make_icon.py
"""

import os
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, os.pardir, "res")

BG = (0, 0, 0, 255)
ACCENT = (127, 179, 255, 255)
DIM = (90, 126, 180, 255)

# The densities Android looks in. This screen is ldpi; the rest are there so a
# package manager on any other device still has something to scale from.
SIZES = {"drawable-ldpi": 36, "drawable-mdpi": 48, "drawable-hdpi": 72}

# Drawn at 4x and downsampled: PIL has no anti-aliased primitives, and a 36px
# icon with hard edges looks like a mistake rather than a choice.
SS = 4


def draw(size):
    n = size * SS
    im = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)

    r = n * 0.18
    d.rounded_rectangle([0, 0, n - 1, n - 1], radius=r, fill=BG)

    # The watch body: a rounded square, lugs top and bottom, the same shape the
    # device is.
    pad = n * 0.26
    stroke = max(1, int(n * 0.055))
    d.rounded_rectangle([pad, pad, n - pad, n - pad],
                        radius=n * 0.09, outline=ACCENT, width=stroke)

    lug_w = n * 0.10
    lug_h = n * 0.09
    cx = n / 2
    d.rounded_rectangle([cx - lug_w, pad - lug_h, cx + lug_w, pad],
                        radius=lug_w * 0.4, fill=DIM)
    d.rounded_rectangle([cx - lug_w, n - pad, cx + lug_w, n - pad + lug_h],
                        radius=lug_w * 0.4, fill=DIM)

    # Two hands, reading roughly ten past ten -- the angle every watch is
    # photographed at, and the only one where both hands stay clear of the lugs.
    d.line([cx, cx, cx - n * 0.12, cx - n * 0.09], fill=ACCENT, width=stroke)
    d.line([cx, cx, cx + n * 0.10, cx - n * 0.13], fill=ACCENT, width=stroke)

    return im.resize((size, size), Image.LANCZOS)


def main():
    for folder, size in SIZES.items():
        out_dir = os.path.join(RES, folder)
        os.makedirs(out_dir, exist_ok=True)
        path = os.path.join(out_dir, "ic_launcher.png")
        draw(size).save(path, "PNG", optimize=True)
        print("%-18s %2dx%-2d  %s" % (folder, size, size, path))


if __name__ == "__main__":
    main()
