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

# The PPG gate patcher, same arrangement as the APK: patch-watch-ppg.sh pins the
# python file's sha256 because it downloads it and runs it against a system
# partition, so that pin is rewritten here rather than by hand.
TOOLS="$HERE/../../tools"

if [ -f "$TOOLS/patch_ppg_gate.py" ]; then
    PSUM=$(sha256sum "$TOOLS/patch_ppg_gate.py" | cut -d' ' -f1)

    sed -i -E "s/^PATCHER_SHA256=\"[a-f0-9]*\"$/PATCHER_SHA256=\"$PSUM\"/" \
        "$TOOLS/patch-watch-ppg.sh"
    grep -q "$PSUM" "$TOOLS/patch-watch-ppg.sh" || {
        echo "failed to re-pin the patcher checksum -- has PATCHER_SHA256= been renamed?" >&2
        exit 1
    }

    install -m 644 "$TOOLS/patch_ppg_gate.py" "$WEB/patch_ppg_gate.py"
    install -m 644 "$TOOLS/patch-watch-ppg.sh" "$WEB/patch-watch-ppg.sh"
    printf 'published patch-watch-ppg.sh\n  sha256 %s (patcher)\n' "$PSUM"
fi

VER=$(grep -o 'versionName="[^"]*"' "$HERE/AndroidManifest.xml" | cut -d'"' -f2)
printf 'published v%s  %s bytes\n  sha256 %s\n  %s\n' \
    "$VER" "$(stat -c%s "$APK")" "$SUM" "$WEB"
