#!/usr/bin/env python3
"""Build assets/oui.db -- the vendor lookup the Bluetooth screen uses to name
a device that will not name itself.

The watch shows a bare MAC for anything that does not answer a name request,
which on a scan full of earbuds and phones is most of them. IEEE publishes who
owns each address prefix, so the prefix alone is enough to say "Sony" instead
of "AC:9B:0A:11:22:33".

Four registries are merged, and they are not all the same width:

    MA-L  24-bit prefix   the classic OUI, ~40k entries
    MA-M  28-bit prefix   ~6.5k
    MA-S  36-bit prefix   ~7k    blocks sold out of one IEEE-held OUI
    IAB   36-bit prefix   ~4.5k  the retired predecessor of MA-S

The narrower registries sit *inside* MA-L entries owned by "IEEE Registration
Authority", so a 24-bit hit on its own is often wrong. Lookup therefore tries
36, then 28, then 24 bits, and the file keeps one sorted section per width.

Records are fixed width so the watch can binary-search the file on disk
instead of parsing 58k rows into a heap it does not have:

    header   64 bytes
    record   32 bytes = 5-byte prefix | 1-byte prefix length | 26-byte name

Usage:
    python3 build_oui_db.py                 # download from IEEE
    python3 build_oui_db.py --from DIR      # use CSVs already in DIR
"""

import argparse
import csv
import io
import os
import re
import struct
import sys
import unicodedata
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, os.pardir, "assets", "oui.db")

# (filename, url, prefix length in bits). Order is irrelevant; the sections are
# written narrowest-prefix-last regardless.
SOURCES = [
    ("oui.csv", "https://standards-oui.ieee.org/oui/oui.csv", 24),
    ("mam.csv", "https://standards-oui.ieee.org/oui28/mam.csv", 28),
    ("mas.csv", "https://standards-oui.ieee.org/oui36/oui36.csv", 36),
    ("iab.csv", "https://standards-oui.ieee.org/iab/iab.csv", 36),
]

MAGIC = b"WOUI1\0\0\0"
NAME_LEN = 26
REC_LEN = 32
HEADER_LEN = 64

# Stripped off the end of a name, repeatedly, longest first. The screen is
# 240px wide: "Sony" fits and "Sony Corporation" does not, and the trailing
# corporate form carries no information a human reading a scan list wants.
SUFFIXES = [
    "co., ltd.", "co.,ltd.", "co., ltd", "co.,ltd", "co ltd", "coltd",
    "company limited", "company ltd", "company",
    "incorporated", "corporation", "corporate", "limited",
    "inc.", "inc", "corp.", "corp", "ltd.", "ltd", "llc.", "llc",
    "l.l.c.", "plc.", "plc", "gmbh", "mbh", "ag", "a/s", "aps", "ab", "oy",
    "b.v.", "bv", "n.v.", "nv", "s.a.", "sa", "s.a.s.", "sas", "sarl",
    "s.r.l.", "srl", "s.p.a.", "spa", "s.r.o.", "sro", "pty", "pte",
    "d.o.o.", "kft", "as", "oyj", "kk", "k.k.", "kg", "& co", "sdn bhd",
    "bhd", "jsc", "ooo", "zao", "pvt", "private", "de c.v.", "s. de r.l.",
    "co.", "co",
    "technologies", "technology", "technologie", "technolgy", "tech",
    "electronics", "electronic", "electric",
    "international", "industries", "industrial", "industry",
    "communications", "communication", "telecommunications",
    "solutions", "solution", "systems", "system", "holdings", "holding",
    "group", "networks", "network", "innovations", "innovation",
    "enterprises", "enterprise", "manufacturing", "products", "product",
    "trading", "information",
]

# Kept in capitals when the whole name arrives shouting, because these are how
# the brand is actually written. Anything else all-caps gets title-cased --
# "SYNERGY SYSTEMS AND SOLUTIONS" is a name, not an initialism.
ACRONYMS = {
    "ASUS", "AKG", "AMD", "ARM", "AVM", "BBK", "BLU", "BMW", "CSR", "DJI",
    "EPOS", "GN", "HMD", "HP", "HTC", "IBM", "JBL", "JVC", "KEF", "LG",
    "LGE", "MSI", "NEC", "NXP", "OKI", "OPPO", "QCOM", "RIM", "SMC", "TCL",
    "TDK", "TP-LINK", "D-LINK", "UBNT", "VIVO", "ZTE", "ZYXEL", "HUAWEI",
}

# Never leave one of these standing alone as the whole vendor name. They are
# the half of a two-word name that carries none of the identity: stripping
# "Electric" off "General Electric" or "Instruments" off "Texas Instruments"
# is right by the rule and wrong by the result.
KEEP_PAIRED = {
    "the", "a", "of", "and", "&", "general", "national", "international",
    "western", "eastern", "northern", "southern", "american", "european",
    "pacific", "atlantic", "united", "advanced", "global", "universal",
    "standard", "central", "first", "new", "smart", "micro", "nano", "open",
    "next", "true", "real", "prime", "core", "data", "sound", "audio",
    "video", "power", "light", "blue", "red", "green", "silver", "gold",
}


def fetch(name, url, cache_dir):
    """Download unless the CSV is already sitting in cache_dir."""
    path = os.path.join(cache_dir, name)
    if os.path.exists(path) and os.path.getsize(path) > 1024:
        return open(path, "rb").read()
    sys.stderr.write("fetching %s\n" % url)
    with urllib.request.urlopen(url, timeout=120) as r:
        data = r.read()
    if not data.startswith(b"Registry,"):
        raise SystemExit("%s did not return a registry CSV" % url)
    with open(path, "wb") as f:
        f.write(data)
    return data


def ascii_only(s):
    """The watch's font has no coverage past latin-1 and the record is fixed
    width in bytes, so fold accents away rather than truncate mid-character."""
    s = unicodedata.normalize("NFKD", s)
    return "".join(c for c in s if ord(c) < 128)


def norm_token(tok):
    """Fold a word to the form the suffix table is written in, so that S.A.,
    S.A and SA are one thing and "Co.,"'s punctuation does not hide it."""
    return "".join(c for c in tok.lower() if c not in ".,'\"()")


# Suffixes as token runs, longest first -- matching on tokens rather than on
# the raw tail is what lets "Co.," and "CO" hit the same rule.
SUFFIX_TOKENS = sorted(
    ([norm_token(t) for t in s.split()] for s in SUFFIXES),
    key=len, reverse=True,
)


def clean(name):
    name = ascii_only(name)
    name = name.replace('"', " ").replace("\t", " ")
    # Parentheticals are a division or a region -- "LG Electronics (Mobile
    # Communications)" -- and only ever get truncated mid-phrase.
    name = re.sub(r"\s*\([^)]*\)?", " ", name)
    # Everything after a comma is nearly always the corporate form. Guarded on
    # length so "Sony, Ltd" loses the tail but an entry that opens with a
    # two-letter fragment is left whole.
    head = name.split(",")[0]
    if len(head) >= 3:
        name = head
    name = " ".join(name.split())

    if name.isupper() and len(name) > 4:
        out = []
        for w in name.split():
            out.append(w if w in ACRONYMS or len(w) <= 3 else w.capitalize())
        name = " ".join(out)

    words = name.split()
    if words and norm_token(words[0]) == "the":
        words = words[1:]

    # Shed trailing corporate forms until nothing more comes off: "Foo
    # Technology Co., Ltd." sheds three of them, one pass each.
    changed = True
    while changed and len(words) > 1:
        changed = False
        tail = [norm_token(w) for w in words]
        for suf in SUFFIX_TOKENS:
            n = len(suf)
            if n < len(words) and tail[-n:] == suf:
                rest = words[:-n]
                # A one-word remainder that is only the generic half of the
                # name is worse than leaving the suffix on.
                if len(rest) == 1 and norm_token(rest[0]) in KEEP_PAIRED:
                    break
                words = rest
                changed = True
                break

    name = " ".join(words).strip(" .,-&")
    if len(name) > NAME_LEN:
        # Cut on a word boundary where one is close enough to the limit,
        # rather than leaving a word sliced in half.
        cut = name[:NAME_LEN]
        space = cut.rfind(" ")
        if space >= NAME_LEN - 10:
            cut = cut[:space]
        name = cut.rstrip(" .,-&")
    return name


def parse(data, bits):
    """Yield (prefix int, name) from one registry CSV."""
    text = io.StringIO(data.decode("utf-8", "replace"))
    hexdigits = bits // 4
    for row in csv.reader(text):
        if len(row) < 3 or row[0] == "Registry":
            continue
        assign = row[1].strip().upper()
        if len(assign) != hexdigits:
            continue
        try:
            value = int(assign, 16)
        except ValueError:
            continue
        name = clean(row[2])
        if not name:
            continue
        # These are placeholders for the narrower registries, not vendors.
        if name.lower().startswith("ieee registration"):
            continue
        yield value, name


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--from", dest="src", default=None,
                    help="directory holding the CSVs already; skips download")
    ap.add_argument("-o", "--out", default=OUT)
    args = ap.parse_args()

    cache = args.src or os.path.join(HERE, "_oui_cache")
    os.makedirs(cache, exist_ok=True)

    # bits -> {prefix: name}. Merged first so MA-S and IAB, which share a
    # width, land in one sorted section.
    by_bits = {}
    for name, url, bits in SOURCES:
        data = fetch(name, url, cache)
        table = by_bits.setdefault(bits, {})
        n = 0
        for value, vendor in parse(data, bits):
            table[value] = vendor
            n += 1
        sys.stderr.write("%-8s %2d-bit  %6d entries\n" % (name, bits, n))

    # Widest prefix first: the lookup takes the first hit and a 36-bit
    # assignment is the specific one.
    sections = sorted(by_bits.items(), key=lambda kv: -kv[0])

    header = io.BytesIO()
    body = io.BytesIO()
    header.write(MAGIC)
    header.write(struct.pack(">I", len(sections)))

    offset = HEADER_LEN
    for bits, table in sections:
        keys = sorted(table)
        header.write(struct.pack(">BBBBII", bits, 0, 0, 0, offset, len(keys)))
        for k in keys:
            # Left-align the prefix into the top of a 40-bit field so a MAC's
            # first five bytes can be compared against it directly.
            shifted = k << (40 - bits)
            body.write(struct.pack(">BI", (shifted >> 32) & 0xFF,
                                   shifted & 0xFFFFFFFF))
            body.write(struct.pack(">B", bits))
            body.write(table[k].encode("ascii").ljust(NAME_LEN, b"\0"))
        offset += len(keys) * REC_LEN

    pad = HEADER_LEN - header.tell()
    if pad < 0:
        raise SystemExit("header overflowed")
    header.write(b"\0" * pad)

    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "wb") as f:
        f.write(header.getvalue())
        f.write(body.getvalue())

    total = sum(len(t) for _, t in sections)
    sys.stderr.write("\nwrote %s\n  %d records, %d bytes\n"
                     % (args.out, total, HEADER_LEN + body.tell()))


if __name__ == "__main__":
    main()
