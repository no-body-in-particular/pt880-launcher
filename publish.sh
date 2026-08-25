#!/usr/bin/env bash
# Publish the built APK to the web root, keeping install-launcher.sh honest.
#
#     ./publish.sh
#
# The install script pins the APK's sha256 so a truncated or tampered download
# is refused. That pin has to be rewritten every time the APK changes, and
# doing it by hand meant publishing a build the installer then rejected. So
# publishing is one step: copy, re-pin, copy the script.
set -e

HERE="$(cd "$(dirname "$0")" && pwd)"
WEB="${WEB:-/var/www/hiawatha/pt880}"
APK="$HERE/watchlauncher.apk"

[ -f "$APK" ] || { echo "no apk -- run ./build.sh first" >&2; exit 1; }
[ -d "$WEB" ] || { echo "no web root at $WEB" >&2; exit 1; }

SUM=$(sha256sum "$APK" | cut -d' ' -f1)

# Re-pin in the repo copy, which is the one under version control; the web
# copy is derived from it so the two can never disagree.
sed -i -E "s/^APK_SHA256=\"[a-f0-9]*\"$/APK_SHA256=\"$SUM\"/" "$HERE/install-launcher.sh"
grep -q "$SUM" "$HERE/install-launcher.sh" || {
    echo "failed to re-pin the checksum -- has APK_SHA256= been renamed?" >&2
    exit 1
}

install -m 644 "$APK" "$WEB/watchlauncher.apk"
install -m 644 "$HERE/install-launcher.sh" "$WEB/install-launcher.sh"

VER=$(grep -o 'versionName="[^"]*"' "$HERE/AndroidManifest.xml" | cut -d'"' -f2)
printf 'published v%s  %s bytes\n  sha256 %s\n  %s\n' \
    "$VER" "$(stat -c%s "$APK")" "$SUM" "$WEB"
