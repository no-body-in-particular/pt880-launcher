#!/usr/bin/env bash
# Build the setuid root helper for the watch.
#
# armeabi-v7a, API 19, statically linked. Static because /system/xbin on this
# device holds musl-linked Alpine binaries with their own loader arrangement,
# and a helper that fails to start because it cannot find a libc is a helper
# that cannot be debugged from the terminal it was meant to provide.
set -e

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

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
[ -x "$CC" ] || { echo "no API 19 armv7a clang under $TC/bin" >&2; exit 1; }

"$CC" -static -Os -Wall -Wextra \
    -o "$HERE/wsu" "$HERE/wsu.c"

"$TC/bin/llvm-strip" "$HERE/wsu" 2>/dev/null || true

echo "built: $HERE/wsu"
ls -l "$HERE/wsu"
file "$HERE/wsu" 2>/dev/null || true
