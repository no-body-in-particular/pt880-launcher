#!/usr/bin/env python3
"""
Every point in a WAL1 file, looked up the way Alerts.java looks one up.

The writer is Rust and the reader is Java and they agree only by convention -
which is how the grid came to be bucketed at one size and read at another. So
this walks the file, takes each point's own coordinates, and asks the reader's
arithmetic to find it. A point that cannot find itself is a point the watch
would never warn about.
"""
import struct, math, sys, urllib.request

def perlat(l):
    p = math.radians(l)
    return 111132.954 - 559.822*math.cos(2*p) + 1.175*math.cos(4*p)

def perlon(l):
    p = math.radians(l)
    return 111412.84*math.cos(p) - 93.5*math.cos(3*p) + 0.118*math.cos(5*p)

def load(url):
    return urllib.request.urlopen(url, timeout=40).read()

def check(d, label):
    assert d[:4] == b'WAL1', "not a WAL1 file"
    nnames = struct.unpack('>H', d[6:8])[0]
    count  = struct.unpack('>I', d[8:12])[0]
    minx, miny, maxx, maxy = struct.unpack('>dddd', d[12:44])
    cols, rows = struct.unpack('>II', d[44:52])
    cells_at   = 52
    points_at  = cells_at + (cols*rows + 1)*4
    names_at   = points_at + count*11
    assert names_at <= len(d), "file shorter than the header claims"

    off = struct.unpack(f'>{cols*rows+1}I', d[cells_at:points_at])
    # the reader's own arithmetic, transcribed from Alerts.java
    cell_x = (maxx - minx) / cols
    cell_y = (maxy - miny) / rows

    pts = []
    for i in range(count):
        a = points_at + i*11
        la, lo = struct.unpack('>ii', d[a:a+8])
        pts.append((la/1e7, lo/1e7, d[a+8], struct.unpack('>H', d[a+9:a+11])[0]))

    # 1. the index must be monotonic and cover exactly the points
    bad_off = sum(1 for i in range(len(off)-1) if off[i] > off[i+1])
    assert off[-1] == count, f"index ends at {off[-1]}, count is {count}"

    # 2. every point must lie in the cell its offset range says it does
    misplaced = 0
    for c in range(cols*rows):
        cx, cy = c % cols, c // cols
        for i in range(off[c], off[c+1]):
            la, lo, kind, ni = pts[i]
            gx = min(cols-1, int((lo - minx)/cell_x))
            gy = min(rows-1, int((la - miny)/cell_y))
            if (gx, gy) != (cx, cy):
                misplaced += 1

    # 3. every point must be findable by the reader's window search
    RADIUS = 400.0
    lost = 0
    for la, lo, kind, ni in pts:
        spanx = max(1, math.ceil((RADIUS/perlon(la))/cell_x))
        spany = max(1, math.ceil((RADIUS/perlat(la))/cell_y))
        cx = int((lo - minx)/cell_x); cy = int((la - miny)/cell_y)
        found = False
        for y in range(cy-spany, cy+spany+1):
            if not (0 <= y < rows): continue
            for x in range(cx-spanx, cx+spanx+1):
                if not (0 <= x < cols): continue
                c = y*cols + x
                for i in range(off[c], off[c+1]):
                    if abs(pts[i][0]-la) < 1e-9 and abs(pts[i][1]-lo) < 1e-9:
                        found = True; break
                if found: break
            if found: break
        if not found: lost += 1

    # 4. the name table must be walkable to the highest index used
    hi = max((ni for _,_,_,ni in pts if ni != 0xFFFF), default=-1)
    at = names_at; walked = 0
    while walked <= hi and at < len(d):
        at += 1 + d[at]; walked += 1
    names_ok = walked > hi and at <= len(d)

    print(f"  {label}")
    print(f"     {count} points, {cols}x{rows} cells, {nnames} names")
    print(f"     cell size read back: x {cell_x:.9f}  y {cell_y:.9f}")
    print(f"     index monotonic: {'yes' if bad_off==0 else 'NO ('+str(bad_off)+' inversions)'}")
    print(f"     points in the cell they are indexed under: {count-misplaced}/{count}")
    print(f"     points findable by the reader's search: {count-lost}/{count}")
    print(f"     name table walkable to index {hi}: {'yes' if names_ok else 'NO'}")
    return misplaced == 0 and lost == 0 and bad_off == 0 and names_ok

base = "http://127.0.0.1:8088/alerts.php?c=netherlands"
ok = True
ok &= check(load(base), "whole country")
ok &= check(load(base + "&w=4.0&s=51.4&e=6.2&n=52.8"), "a 100 km box")
ok &= check(load(base + "&w=5.10&s=52.08&e=5.12&n=52.10"), "one town")
ok &= check(load("http://127.0.0.1:8088/alerts.php?c=luxembourg"), "luxembourg")
print("  " + ("all consistent" if ok else "INCONSISTENT"))
sys.exit(0 if ok else 1)
