#!/usr/bin/env bash
# Build the shim that reaches the vendor's SpO2 entry point.
#
# armeabi-v7a, API 19, shared. Shared rather than static because this one is loaded into the
# launcher by System.loadLibrary and has to link against the platform's own libdl and liblog;
# wsu.c next door is static for the opposite reason, being a standalone binary in /system/xbin.
#
# The result goes in libs/armeabi-v7a/ where build.sh picks it up into the apk.
set -e

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$HERE/../libs/armeabi-v7a"

NDK="${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-}}"
if [ -z "$NDK" ]; then
  for c in "$HOME/claude-watch/ndk/android-ndk-r21e" \
           "$HOME/Android/Sdk/ndk-bundle" \
           "$HOME/Android/Sdk/ndk"/*; do
    [ -d "$c/toolchains/llvm/prebuilt" ] && { NDK="$c"; break; }
  done
fi
[ -n "$NDK" ] && [ -d "$NDK" ] || {
  echo "NDK not found -- set ANDROID_NDK_ROOT" >&2; exit 1; }

TC="$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/ 2>/dev/null | head -1)"
TC="${TC%/}"
CC="$TC/bin/armv7a-linux-androideabi19-clang"
[ -x "$CC" ] || CC="$CC.cmd"
[ -x "$CC" ] || { echo "no API 19 armv7a clang under $TC/bin" >&2; exit 1; }

mkdir -p "$OUT"
"$CC" -shared -fPIC -Os -Wall -Wextra \
    -o "$OUT/libgh30x.so" "$HERE/gh30x.c" -ldl

echo "built $OUT/libgh30x.so"
ls -l "$OUT/libgh30x.so"
