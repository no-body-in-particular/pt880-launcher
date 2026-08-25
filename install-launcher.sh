#!/usr/bin/env bash
# Install the watch launcher over adb, fetching what it needs from the web.
#
#     curl -fsSL https://coredump.ws/pt880/install-launcher.sh | bash -s -- --all
#
#     (no flags)   install the APK and nothing else
#     --root       also install the setuid helper the Terminal needs
#     --home       also make it the watch's home screen
#     --all        both of the above
#
# Root and home are opt-in on purpose. One installs a binary that hands root to
# anything on the device that can exec it, and the other changes what the watch
# boots into -- neither belongs in the default path of a one-liner.
set -e

# Piped into bash the script itself is on stdin, and `adb shell` reads stdin --
# so an unguarded call swallows the rest of the script and the run stops dead
# after the first one. Every adb call below closes stdin.
ADB="${ADB:-adb}"
SERIAL="${SERIAL:-}"
[ -n "$SERIAL" ] && ADB="$ADB -s $SERIAL"
adbq() { $ADB "$@" </dev/null; }

BASE="${BASE:-https://coredump.ws/pt880}"
APK_SHA256="a06acfc92081614ba59bf47f6f279195c260228126d5a98ae8a5aa3e206e43d5"

say() { printf '\n== %s\n' "$*"; }
die() { printf '\nerror: %s\n' "$*" >&2; exit 1; }

DO_ROOT=0
DO_HOME=0
for a in "$@"; do
  case "$a" in
    --root) DO_ROOT=1 ;;
    --home) DO_HOME=1 ;;
    --all)  DO_ROOT=1; DO_HOME=1 ;;
    -h|--help)
      sed -n '2,12p' "$0" 2>/dev/null || echo "flags: --root --home --all"
      exit 0 ;;
    *) die "unknown flag: $a" ;;
  esac
done

fetch() {
  # $1 url, $2 destination
  if command -v curl >/dev/null 2>&1; then curl -fsSL -o "$2" "$1"
  elif command -v wget >/dev/null 2>&1; then wget -qO "$2" "$1"
  else die "need curl or wget"; fi
}

sha256of() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  elif command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | cut -d' ' -f1
  else echo ""; fi
}

command -v $ADB >/dev/null 2>&1 || die "adb not found on PATH"

say "device"
adbq wait-for-device
adbq shell 'echo connected' | tr -d '\r'

TMP="$(mktemp -d "${TMPDIR:-/tmp}/watchlauncher.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT

say "download"
fetch "$BASE/watchlauncher.apk" "$TMP/watchlauncher.apk" \
  || die "could not fetch $BASE/watchlauncher.apk"
echo "  $(wc -c < "$TMP/watchlauncher.apk") bytes"

# A 404 page downloads perfectly well and installs not at all, so check what
# actually arrived before handing it to the package manager.
GOT="$(sha256of "$TMP/watchlauncher.apk")"
if [ -n "$GOT" ] && [ "$GOT" != "$APK_SHA256" ]; then
  die "apk checksum mismatch
       expected $APK_SHA256
       got      $GOT"
fi

say "install"
adbq install -r "$TMP/watchlauncher.apk" 2>&1 | tr -d '\r' | sed 's/^/  /'
adbq shell 'pm path org.watchlauncher' | tr -d '\r' | grep -q package: \
  || die "the package is not installed"

if [ "$DO_ROOT" = "1" ]; then
  say "root helper"
  fetch "$BASE/install-root-helper.sh" "$TMP/install-root-helper.sh" \
    || die "could not fetch the root helper installer"
  # Runs as a file rather than a pipe, so its own BASH_SOURCE logic works and
  # its adb calls are not competing with this script for stdin.
  ADB="$ADB" WSU_URL="$BASE/wsu" bash "$TMP/install-root-helper.sh"
fi

if [ "$DO_HOME" = "1" ]; then
  say "home screen"
  fetch "$BASE/set-as-home.sh" "$TMP/set-as-home.sh" \
    || die "could not fetch the home installer"
  ADB="$ADB" bash "$TMP/set-as-home.sh"
else
  say "start it"
  adbq shell 'am start -n org.watchlauncher/.ShellActivity' | tr -d '\r' | sed 's/^/  /'
fi

say "done"
cat <<EOF
Contacts, when you want the dialler:

    adb push contacts.txt /sdcard/Documents/

    Arno Phone:+31619036989
    Home:0031619036989
EOF
