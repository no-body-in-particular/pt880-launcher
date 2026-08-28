#!/usr/bin/env python3
"""
Two byte-level fixes to com.ic.work's odex, for a watch whose vendor tracker is gone.

    ./patch_ic_work.py ICL02WorkService.odex --verify         report only, write nothing
    ./patch_ic_work.py ICL02WorkService.odex -o patched.odex  apply both
    ./patch_ic_work.py ICL02WorkService.odex --wear -o out     apply one

Both patches are idempotent: run them on an already-patched file and they say so
and change nothing. Both refuse to write if the bytes are not what they expect.

Nothing is inserted or removed - every patch replaces an instruction with one of
the same width, so no branch target, try/catch bound or debug offset moves. That
is the whole reason these are byte patches and not a decompile-and-rebuild.

Nothing here is found by file offset. The classes are obfuscated to a(), b(),
e/b, g/c, and offsets shift between firmware builds, so each site is located by
the service's own log strings and then checked instruction by instruction.

--------------------------------------------------------------------------------
PATCH 1: --wear    stop the wear detector zeroing every measurement
--------------------------------------------------------------------------------

BloodInfoHelper (Lcom/ic/work/e/b;) decides "is this on a wrist" from one axis of
one sensor sample, in onSensorChanged:

    0081  aget v0, v10, v1          sensor values[1]
    0083  double-to-int v0, v0
    0084  if-le v0, v6, 0087        below the threshold? leave v7 as it was (0)
    0086  const/4 v7, 1             above it? worn
    0087  iput-quick v7, v9, @56    isWear = v7

That field is written here and nowhere else, and read in exactly two places, both
in the same class's a(): once to log it, once to branch on it. So forcing the
write forces every reader, and the service's own log line becomes the check - it
starts printing "isWear == > true".

    0084  if-le v0, v6, +3  ->  nop; nop

if-le is 22t, two code units; nop is 10x, one. Two nops replace it exactly, and
control falls into the const/4 that was the "worn" arm.

Why this site and not the branch in a(): a() reads the flag twice, and patching
readers means finding all of them, now and after every firmware update. There is
one writer.

What it costs: the watch always believes it is worn. Measurements taken off the
wrist are attempted rather than skipped, and the PPG returns whatever it returns
with nothing against the sensor - usually zeros. This does not invent readings;
it stops a false negative from suppressing real ones. The caller still has to
reject implausible results, which is where a heart rate of 0 belongs.

Observed cause: the detector reported isWear == false continuously, through whole
21 s measurement windows, with the watch worn. Every measurement then completed
and reported high/low/heart/oxygen all zero.

--------------------------------------------------------------------------------
PATCH 2: --queue   stop one stuck measurement deadlocking the queue for ever
--------------------------------------------------------------------------------

SensorDataService runs heart rate and temperature through a single work queue
with one worker. Each item carries a creation timestamp, and that timestamp is
the only currentTimeMillis() in the service: written, never compared. So there is
no timeout. A measurement whose sensor callback never arrives holds the head of
the queue permanently; from then on every request is refused, the optical sensor
stays powered, and nothing is returned. Only restarting the process clears it.

A real timeout means comparing that stored timestamp against now and expiring the
item. That is new bytecode in the middle of a method: every instruction after it
shifts, and so does every branch target, try/catch bound and debug offset in the
class. Not something to attempt by hand on a system odex.

What can be done without moving anything is to remove the mutex the stuck item
holds. The queue method decides three things, in order:

    006d: if-eqz v0, 007d     is something running? if so, log
                              "current task running..." and return - the request
                              is dropped on the floor
    00a5: if-nez v0, 00b9     - if either is set, go to "new task waiting" and
    00ad: if-nez v0, 00b9     - only queue the item
    00af: iput-quick          otherwise mark running
    00b5: invoke-virtual      ...and start it now

Patching the first gate alone is not enough, which is worth saying because it
looks like it would be: the request then survives to the second pair, which still
sees the stale flag and queues the item behind the one that will never finish.
All three have to go.

    006d  if-eqz v0, +16  ->  goto/16 +16     0x38 -> 0x29, register byte to 0
    00a5  if-nez v0, +20  ->  nop; nop        four zero bytes
    00ad  if-nez v0, +12  ->  nop; nop        four zero bytes

if-eqz is 21t and goto/16 is 20t: same four bytes, same 16 bit offset in the same
place, and 20t's second byte is unused and must be zero.

What it costs: the service no longer serialises its own measurements, so two can
overlap inside com.ic.work. That is acceptable only because the vendor tracker is
gone and the launcher is effectively the only caller, which puts the burden of
serialising on the launcher. On a stock watch this would be the wrong trade.

That burden was not met when this patch was first installed, and the watch said
so. Heart rate and temperature are separate binder calls but the same queue, and
the launcher's vitals, oxygen and temperature timers are all seeded from the same
instant, so they came around together:

    10:44:14.650  create new task == > TestTaskEvent{sensorType=TEMPERATURE ...}
    10:44:14.650  is running =-== > false  queue size == > 0
    10:44:14.650  create new task == > TestTaskEvent{sensorType=HEART_RATE ...}
    10:44:14.650  is running =-== > true   queue size == > 0

Seven milliseconds apart, and the second is exactly the request the vendor's
mutex used to refuse. Both then ran, and the first to finish reaches "disable
ppg" while the other is still measuring. TrackerService.measuring now covers the
temperature path as well, which is what makes the trade above hold. If that gate
is ever removed, this patch has to come out with it.

Note when reading logs afterwards: this patch removes the two branches that log
"current task running..." and "new task waiting". Their absence from logcat after
patching is guaranteed by construction and is not evidence that the queue is
healthy. To test whether the wedge is real, run --verify on an unpatched copy and
watch the original.

Reversible: keep the backup and push it back.
"""

import argparse
import hashlib
import struct
import sys
import zlib

CLASS_SENSOR = "Lcom/ic/work/SensorDataService;"
MARK_QUEUE = "current task running..."
MARK_QUEUE_ALT = "task == start == > "
MARK_WEAR = "isWear == > "

OP_NOP = 0x00
OP_CONST_4 = 0x12
OP_IF_EQZ = 0x38
OP_IF_NEZ = 0x39
OP_GOTO_16 = 0x29
OP_CONST_STRING = 0x1A
OP_IGET_QUICK = 0xF2
OP_IPUT_QUICK = 0xF5

IF_TEST = range(0x32, 0x38)      # if-eq/ne/lt/ge/gt/le   22t
IF_TESTZ = range(0x38, 0x3E)     # if-eqz/nez/...z        21t

GATE_DROP = 0x006D
GATE_QUEUE = (0x00A5, 0x00AD)

ODEX_MAGIC = b"dey\n"
DEX_MAGIC = b"dex\n"

# Instruction width in 16-bit code units, indexed by opcode. This is an odex, so
# the quick opcodes in 0xe3-0xff are live; treating them as one unit desyncs the
# walk and everything found after that point is invented.
SZ = [None] * 256


def _w(lo, hi, n):
    for o in range(lo, hi + 1):
        SZ[o] = n


for _o, _n in {0x00: 1, 0x01: 1, 0x02: 2, 0x03: 3, 0x04: 1, 0x05: 2, 0x06: 3,
               0x07: 1, 0x08: 2, 0x09: 3, 0x0a: 1, 0x0b: 1, 0x0c: 1, 0x0d: 1,
               0x0e: 1, 0x0f: 1, 0x10: 1, 0x11: 1, 0x12: 1, 0x13: 2, 0x14: 3,
               0x15: 2, 0x16: 2, 0x17: 3, 0x18: 5, 0x19: 2, 0x1a: 2, 0x1b: 3,
               0x1c: 2, 0x1d: 1, 0x1e: 1, 0x1f: 2, 0x20: 2, 0x21: 1, 0x22: 2,
               0x23: 2, 0x24: 3, 0x25: 3, 0x26: 3, 0x27: 1, 0x28: 1, 0x29: 2,
               0x2a: 3, 0x2b: 3, 0x2c: 3}.items():
    SZ[_o] = _n
_w(0x2d, 0x31, 2)          # cmpl / cmpg / cmp-long        23x
_w(0x32, 0x37, 2)          # if-<test>                     22t
_w(0x38, 0x3d, 2)          # if-<test>z                    21t
_w(0x44, 0x51, 2)          # aget / aput                   23x
_w(0x52, 0x5f, 2)          # iget / iput                   22c
_w(0x60, 0x6d, 2)          # sget / sput                   21c
_w(0x6e, 0x72, 3)          # invoke-<kind>                 35c
_w(0x74, 0x78, 3)          # invoke-<kind>/range           3rc
_w(0x7b, 0x8f, 1)          # unop                          12x
_w(0x90, 0xaf, 2)          # binop                         23x
_w(0xb0, 0xcf, 1)          # binop/2addr                   12x
_w(0xd0, 0xd7, 2)          # binop/lit16                   22s
_w(0xd8, 0xe2, 2)          # binop/lit8                    22b
_w(0xe3, 0xeb, 2)          # +iget/+iput/+sget/+sput volatile
SZ[0xec] = 1               # ^breakpoint
SZ[0xed] = 2               # ^throw-verification-error
_w(0xee, 0xef, 3)          # +execute-inline[/range]
SZ[0xf0] = 3               # +invoke-object-init/range
SZ[0xf1] = 1               # +return-void-barrier
_w(0xf2, 0xf7, 2)          # +iget-quick / +iput-quick family
_w(0xf8, 0xfb, 3)          # +invoke-virtual/super-quick[/range]
_w(0xfc, 0xfe, 2)          # +iput-object / +sget-object / +sput-object volatile


def uleb(b, o):
    r = s = 0
    while True:
        c = b[o]
        o += 1
        r |= (c & 0x7F) << s
        if not c & 0x80:
            return r, o
        s += 7


class Dex:
    """Just enough dex to find a method by the strings it prints."""

    def __init__(self, buf, base):
        self.b = buf
        self.base = base
        if bytes(buf[base:base + 4]) != DEX_MAGIC:
            raise ValueError("no dex header at offset %d" % base)
        self.str_n, self.str_off = struct.unpack_from("<II", buf, base + 0x38)
        self.type_off = struct.unpack_from("<I", buf, base + 0x44)[0]
        self.mth_off = struct.unpack_from("<I", buf, base + 0x5C)[0]
        self.cls_n, self.cls_off = struct.unpack_from("<II", buf, base + 0x60)
        self._sc = {}

    def a(self, off):
        return self.base + off

    def string(self, i):
        if not (0 <= i < self.str_n):
            return None
        if i in self._sc:
            return self._sc[i]
        off, = struct.unpack_from("<I", self.b, self.a(self.str_off) + i * 4)
        n, p = uleb(self.b, self.a(off))
        raw = bytes(self.b[p:p + n * 3])
        end = raw.find(b"\x00")
        if end >= 0:
            raw = raw[:end]
        s = raw.decode("utf-8", "replace")[:n]
        self._sc[i] = s
        return s

    def type_name(self, i):
        idx, = struct.unpack_from("<I", self.b, self.a(self.type_off) + i * 4)
        return self.string(idx)

    def method_name(self, i):
        n, = struct.unpack_from("<I", self.b, self.a(self.mth_off) + i * 8 + 4)
        return self.string(n)

    def methods(self):
        """(class, method, absolute code offset) for every method with code."""
        for c in range(self.cls_n):
            cd = self.a(self.cls_off) + c * 32
            cls_idx, = struct.unpack_from("<I", self.b, cd)
            data, = struct.unpack_from("<I", self.b, cd + 24)
            if not data:
                continue
            cname = self.type_name(cls_idx)
            o = self.a(data)
            sf, o = uleb(self.b, o)
            inf, o = uleb(self.b, o)
            dm, o = uleb(self.b, o)
            vm, o = uleb(self.b, o)
            for _ in range(sf + inf):
                _, o = uleb(self.b, o)
                _, o = uleb(self.b, o)
            for group in (dm, vm):
                idx = 0
                for _ in range(group):
                    diff, o = uleb(self.b, o)
                    _, o = uleb(self.b, o)
                    code, o = uleb(self.b, o)
                    idx += diff
                    if code:
                        yield cname, self.method_name(idx), self.a(code)

    def walk(self, code):
        """(unit, file offset, opcode, width) over one method's instructions.

        Stops rather than guessing if it meets an opcode it does not know: a
        desynced walk finds branches that are not there."""
        insns, = struct.unpack_from("<I", self.b, code + 12)
        p, unit = code + 16, 0
        while unit < insns:
            op = self.b[p]
            n = SZ[op]
            if not n:
                return
            if op == OP_NOP and unit + 1 < insns:
                ident, = struct.unpack_from("<H", self.b, p)
                if ident == 0x0100:                      # packed-switch payload
                    sz, = struct.unpack_from("<H", self.b, p + 2)
                    n = 4 + sz * 2
                elif ident == 0x0200:                    # sparse-switch payload
                    sz, = struct.unpack_from("<H", self.b, p + 2)
                    n = 2 + sz * 4
                elif ident == 0x0300:                    # fill-array-data payload
                    w, = struct.unpack_from("<H", self.b, p + 2)
                    sz, = struct.unpack_from("<I", self.b, p + 4)
                    n = (w * sz + 1) // 2 + 4
            yield unit, p, op, n
            p += n * 2
            unit += n

    def find_by_string(self, marker, cls=None):
        """The method that mentions `marker` - these classes have no useful names."""
        for cname, mname, code in self.methods():
            if cls and cname != cls:
                continue
            for unit, p, op, _n in self.walk(code):
                if op != OP_CONST_STRING:
                    continue
                if self.string(struct.unpack_from("<H", self.b, p + 2)[0]) == marker:
                    return cname, mname, code, unit
        return None, None, None, None


# ---------------------------------------------------------------------------
# patch 1: the wear detector
# ---------------------------------------------------------------------------

def patch_wear(dx, apply_it):
    notes = []
    cname, mname, code, unit = dx.find_by_string(MARK_WEAR)
    if code is None:
        return False, ["  could not find %r anywhere" % MARK_WEAR]
    notes.append("  %s.%s logs the flag at unit %04x" % (cname, mname, unit))

    # The field is whatever that log line reads back, an instruction or two on.
    field = None
    for u, p, op, _n in dx.walk(code):
        if u > unit and op == OP_IGET_QUICK:
            field = struct.unpack_from("<H", dx.b, p + 2)[0]
            break
    if field is None:
        return False, notes + ["  no iget-quick after the log string; refusing"]
    notes.append("  wear flag is field offset %d" % field)

    # One writer, in the same class. More than one and the reasoning above does
    # not hold, so stop rather than patch half of them.
    site = None
    for cn2, mn2, code2 in dx.methods():
        if cn2 != cname:
            continue
        for u, p, op, _n in dx.walk(code2):
            if op == OP_IPUT_QUICK and struct.unpack_from("<H", dx.b, p + 2)[0] == field:
                if site:
                    return False, notes + ["  more than one writer; refusing"]
                site = (mn2, code2, u, p, dx.b[p + 1] & 0x0F)
    if not site:
        return False, notes + ["  no writer for field %d; refusing" % field]
    wmn, wcode, wunit, _wp, src = site
    notes.append("  %s.%s writes it at unit %04x from v%d" % (cname, wmn, wunit, src))

    def preceding(unit_of):
        for u, p, op, n in dx.walk(wcode):
            if u + n == unit_of:
                return u, p, op
        return None

    # The guard is the branch that skips the "worn" arm and lands on the write.
    guard = None
    for u, p, op, n in dx.walk(wcode):
        if op in IF_TEST or op in IF_TESTZ:
            if u + struct.unpack_from("<h", dx.b, p + 2)[0] == wunit:
                guard = (u, p, op, n)

    if guard is None:
        # Already patched: the arm now sits immediately before the write, with
        # the nops that replaced the guard immediately before that.
        arm = preceding(wunit)
        if arm and arm[2] == OP_CONST_4 and (dx.b[arm[1] + 1] & 0x0F) == src \
                and (dx.b[arm[1] + 1] >> 4) == 1:
            back = preceding(arm[0])
            if back and back[2] == OP_NOP:
                notes.append("  %04x  already nop; nop" % back[0])
                return True, notes
        return False, notes + ["  no branch lands on the write; refusing"]

    gu, gp, gop, gn = guard
    if gn != 2:
        return False, notes + ["  guard is %d code units, cannot pad with nops" % gn]

    arm = None
    for u, p, op, _n in dx.walk(wcode):
        if u == gu + gn:
            arm = (p, op)
            break
    if not arm or arm[1] != OP_CONST_4 or (dx.b[arm[0] + 1] & 0x0F) != src \
            or (dx.b[arm[0] + 1] >> 4) != 1:
        return False, notes + ["  the skipped arm is not 'const/4 v%d, 1'; refusing" % src]

    t = struct.unpack_from("<h", dx.b, gp + 2)[0]
    notes.append("  %04x  if-%s %+d  ->  nop; nop   (falls into const/4 v%d, 1)"
                 % (gu, "test" if gop in IF_TEST else "testz", t, src))
    if apply_it:
        dx.b[gp:gp + 4] = b"\x00\x00\x00\x00"
    return True, notes


# ---------------------------------------------------------------------------
# patch 2: the work queue mutex
# ---------------------------------------------------------------------------

def patch_queue(dx, apply_it):
    notes, ok = [], True
    cname, mname, code, _u = dx.find_by_string(MARK_QUEUE, cls=CLASS_SENSOR)
    if code is None:
        # This patch removes the only branch that reaches that string, so a
        # patched file no longer references it. Fall back to a string on the
        # path that survives.
        cname, mname, code, _u = dx.find_by_string(MARK_QUEUE_ALT, cls=CLASS_SENSOR)
        if code is None:
            return False, ["  could not find the queue method in %s" % CLASS_SENSOR]
    notes.append("  %s.%s  code at 0x%x" % (cname, mname, code))
    insns, = struct.unpack_from("<I", dx.b, code + 12)
    base = code + 16

    p = base + GATE_DROP * 2
    if GATE_DROP < insns and dx.b[p] == OP_IF_EQZ:
        t = struct.unpack_from("<h", dx.b, p + 2)[0]
        notes.append("  %04x  if-eqz v%d, %+d  ->  goto/16 %+d"
                     % (GATE_DROP, dx.b[p + 1], t, t))
        if apply_it:
            dx.b[p] = OP_GOTO_16
            dx.b[p + 1] = 0
    elif dx.b[p] == OP_GOTO_16:
        notes.append("  %04x  already goto/16" % GATE_DROP)
    else:
        notes.append("  %04x  NOT if-eqz (found %02x) -- refusing" % (GATE_DROP, dx.b[p]))
        ok = False

    for unit in GATE_QUEUE:
        p = base + unit * 2
        if unit < insns and dx.b[p] == OP_IF_NEZ:
            t = struct.unpack_from("<h", dx.b, p + 2)[0]
            notes.append("  %04x  if-nez v%d, %+d  ->  nop; nop" % (unit, dx.b[p + 1], t))
            if apply_it:
                dx.b[p:p + 4] = b"\x00\x00\x00\x00"
        elif bytes(dx.b[p:p + 4]) == b"\x00\x00\x00\x00":
            notes.append("  %04x  already nop; nop" % unit)
        else:
            notes.append("  %04x  NOT if-nez (found %02x) -- refusing" % (unit, dx.b[p]))
            ok = False
    return ok, notes


def reseal(buf, base):
    """dex carries its own sha1 and adler32, and dalvik checks both on load."""
    sha = hashlib.sha1(bytes(buf[base + 32:])).digest()
    buf[base + 12:base + 32] = sha
    csum = zlib.adler32(bytes(buf[base + 12:])) & 0xFFFFFFFF
    buf[base + 8:base + 12] = csum.to_bytes(4, "little")


def main():
    ap = argparse.ArgumentParser(
        description="Patch com.ic.work's odex: wear detector and work queue mutex.")
    ap.add_argument("odex")
    ap.add_argument("-o", "--out")
    ap.add_argument("--verify", action="store_true", help="report only, write nothing")
    ap.add_argument("--wear", action="store_true", help="only the wear detector")
    ap.add_argument("--queue", action="store_true", help="only the queue mutex")
    args = ap.parse_args()

    chosen = args.wear or args.queue
    want_wear = args.wear or not chosen
    want_queue = args.queue or not chosen

    buf = bytearray(open(args.odex, "rb").read())
    if bytes(buf[:4]) == ODEX_MAGIC:
        base = struct.unpack_from("<I", buf, 8)[0]
    elif bytes(buf[:4]) == DEX_MAGIC:
        base = 0
    else:
        print("not a dex or odex", file=sys.stderr)
        return 2

    dx = Dex(buf, base)
    apply_it = not args.verify
    ok = True

    if want_wear:
        print("wear detector:")
        good, notes = patch_wear(dx, apply_it)
        for n in notes:
            print(n)
        ok &= good
        print()

    if want_queue:
        print("work queue mutex:")
        good, notes = patch_queue(dx, apply_it)
        for n in notes:
            print(n)
        ok &= good
        print()

    if not ok:
        print("the bytes are not what this expects; nothing written", file=sys.stderr)
        return 1
    if args.verify:
        print("verify only; nothing written")
        return 0

    reseal(buf, base)
    out = args.out or (args.odex + ".patched")
    with open(out, "wb") as f:
        f.write(bytes(buf))
    print("wrote %s" % out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
