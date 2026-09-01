#!/usr/bin/env bash
# Build the vitals daemon and its two helpers for the watch.
#
#     ./build-vitals.sh
#
# The launcher measures nothing itself. It asks vitalsd, which drives the
# Goodix chip directly, and vitalsd runs ppgd and adtwear as helpers because
# each needs the device node to itself for the length of a pass. All three come
# from the `vitals` submodule; none of them is in the APK.
#
# This used to matter less, because the launcher could fall back on the OEM's
# com.ic.work when its own daemon was not there. That client has been removed,
# so a watch without these three reports no pulse, no pressure, no saturation
# and no temperature at all -- which is why they are now part of the install
# rather than something you set up separately.
#
# Needs an Android NDK with an arm32 toolchain. Set ANDROID_NDK_ROOT if yours
# is not one of the usual places.
set -e

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="$HERE/vitals/tools/gh3011"
OUT="$HERE/vitals-bin"

[ -f "$SRC/vitalsd.c" ] || {
    echo "the vitals submodule is not checked out; run:" >&2
    echo "    git submodule update --init vitals" >&2
    exit 1
}

NDK="${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-}}"
if [ -z "$NDK" ]; then
    for c in "$HOME/claude-watch/ndk/android-ndk-"* "$HOME/Android/Sdk/ndk/"* \
             "$HOME/Android/Sdk/ndk-bundle" /opt/android-ndk*; do
        [ -d "$c" ] && { NDK="$c"; break; }
    done
fi
[ -n "$NDK" ] && [ -d "$NDK" ] || {
    echo "Android NDK not found -- set ANDROID_NDK_ROOT" >&2
    exit 1
}

# API 19, because that is what the watch is, and armv7a because that is the
# SL8521E. The name carries both, so there is nothing else to get right.
CC="$(ls "$NDK"/toolchains/llvm/prebuilt/*/bin/armv7a-linux-androideabi19-clang 2>/dev/null | head -1)"
[ -n "$CC" ] || {
    echo "no armv7a-linux-androideabi19-clang under $NDK" >&2
    exit 1
}

mkdir -p "$OUT"
cd "$SRC"

# ppgd wants libm; the other two do not, and asking for it anyway would only
# hide which of them actually needs it.
"$CC" -Os -Wall -o "$OUT/ppgd"    ppgd.c -lm
"$CC" -Os -Wall -o "$OUT/vitalsd" vitalsd.c
"$CC" -Os -Wall -o "$OUT/adtwear" adtwear.c

echo "built into $OUT"
for f in vitalsd ppgd adtwear; do
    printf '  %-8s %8s bytes  %s\n' "$f" "$(stat -c%s "$OUT/$f")" \
        "$(sha256sum "$OUT/$f" | cut -d' ' -f1)"
done
