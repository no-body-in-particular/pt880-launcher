#!/usr/bin/env bash
# Install the watch launcher over adb, fetching what it needs from the web.
#
#     curl -fsSL https://coredump.ws/pt880/install-launcher.sh | bash -s -- --all
#
#     (no flags)   install the APK and nothing else
#     --root       also install the setuid helper the Terminal needs
#     --vitals     also install the vitals daemon, which measures everything
#     --home       also make it the watch's home screen
#     --call       also take the in-call screen off the stock dialler
#     --all        all four
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
# The vitals daemon and its two helpers, pinned the same way and for the same
# reason. Re-pinned by publish.sh; built by build-vitals.sh.
VITALSD_SHA256="9cfa0bc9c2bb13cecae985665abae04ba020e891f18decb874fb27f0f84b1d2b"
PPGD_SHA256="4a38bd1ad3f3666988280b7e7044e11579dfe4d8cfa30acc3d4f647dacb108a4"
ADTWEAR_SHA256="0906e7bc2877f943d68315b2ed803d6e7966194fa4e2890b38c366b9d0bbeb82"

APK_SHA256="e3b37ff7a2752dea020b5d5ccf3bb1b566cac9ac1468d90c923468584a64edfb"

say() { printf '\n== %s\n' "$*"; }
die() { printf '\nerror: %s\n' "$*" >&2; exit 1; }

DO_ROOT=0
DO_HOME=0
DO_CALL=0
DO_VITALS=0
for a in "$@"; do
  case "$a" in
    --root) DO_ROOT=1 ;;
    --vitals) DO_VITALS=1 ;;
    --home) DO_HOME=1 ;;
    --call) DO_CALL=1 ;;
    --all)  DO_ROOT=1; DO_HOME=1; DO_CALL=1; DO_VITALS=1 ;;
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
OUT="$(adbq install -r "$TMP/watchlauncher.apk" 2>&1 | tr -d '\r')"
printf '%s\n' "$OUT" | sed 's/^/  /'

# A signature that does not match the one already on the watch.
#
# This project has not always signed with the same key, and Android will not
# upgrade a package across that change. Nor is there a way round it: -r does
# not force it, and `pm uninstall -k` - which keeps the data directory - makes
# it worse, because the retained data still belongs to the old signature and
# the install then fails with INSTALL_FAILED_UPDATE_INCOMPATIBLE. The old
# package and its data have to go.
#
# Two things make that safe to do unattended, and both matter:
#
#   * Everything worth keeping is on /sdcard - the maps, the sleep logs,
#     destination.txt, contacts.txt, tracker.txt, the photos. An uninstall does
#     not touch any of it. What goes is today's running sleep total, the screen
#     and volume settings, and any tracker rows not yet uploaded.
#
#   * If the launcher is the home screen then the stock launcher is disabled,
#     and a watch with neither is a recovery job on a device with no
#     touchscreen. So the stock one is put back before the uninstall and the
#     alias restored after, and the gap between them is a few seconds with no
#     reboot in it.
case "$OUT" in
  *INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES*|*INSTALL_FAILED_UPDATE_INCOMPATIBLE*)
    say "signed with a different key than the install on this watch"
    echo "  The old package has to be removed. /sdcard is not touched, so maps,"
    echo "  sleep logs and destination.txt all survive; today's sleep total and"
    echo "  the settings do not."

    WAS_HOME=0
    if adbq shell "pm list packages -e org.watchlauncher" | tr -d '\r' | grep -q watchlauncher; then
      if adbq shell "dumpsys package org.watchlauncher/.HomeAlias" \
           | tr -d '\r' | grep -q "enabled=1"; then
        WAS_HOME=1
      fi
    fi
    if [ "$WAS_HOME" = "1" ]; then
      echo "  it is the home screen - handing that back to the stock launcher first"
      adbq shell "pm enable com.android.launcher" >/dev/null 2>&1 || true
      adbq shell "pm disable-user org.watchlauncher/.HomeAlias" >/dev/null 2>&1 || true
    fi

    adbq uninstall org.watchlauncher 2>&1 | tr -d '\r' | sed 's/^/  /'
    adbq install "$TMP/watchlauncher.apk" 2>&1 | tr -d '\r' | sed 's/^/  /'

    if [ "$WAS_HOME" = "1" ]; then
      echo "  putting the home screen back"
      fetch "$BASE/set-as-home.sh" "$TMP/set-as-home.sh" \
        && ADB="$ADB" bash "$TMP/set-as-home.sh" >/dev/null 2>&1 \
        && echo "  done" \
        || echo "  could not re-run set-as-home.sh - run it by hand"
    fi
    ;;
esac

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

if [ "$DO_VITALS" = "1" ]; then
  say "vitals daemon"
  # The launcher measures nothing itself: it asks vitalsd over a socket, and
  # vitalsd runs ppgd and adtwear as helpers because each needs the chip's
  # device node to itself for the length of a pass. Without these three the
  # watch reports no pulse, no pressure, no saturation and no temperature -
  # there is no longer a fallback to the OEM's own service.
  for f in vitalsd ppgd adtwear; do
    fetch "$BASE/vitals/$f" "$TMP/$f" || die "could not fetch $BASE/vitals/$f"
    case "$f" in
      vitalsd) want="$VITALSD_SHA256" ;;
      ppgd)    want="$PPGD_SHA256" ;;
      adtwear) want="$ADTWEAR_SHA256" ;;
    esac
    got="$(sha256of "$TMP/$f")"
    if [ -n "$want" ] && [ -n "$got" ] && [ "$got" != "$want" ]; then
      die "$f checksum mismatch
       expected $want
       got      $got"
    fi
    adbq push "$TMP/$f" "/data/local/tmp/$f" >/dev/null 2>&1 \
      || die "could not push $f to the watch"
    adbq shell "chmod 755 /data/local/tmp/$f" >/dev/null 2>&1
    echo "  $f  $(wc -c < "$TMP/$f") bytes"
  done

  # init already runs gh3011_service as root and restarts it if it dies, which
  # is exactly the supervision this needs and is not otherwise available: the
  # app has no root, and nothing else here survives an adb disconnect. The real
  # vendor binary is kept alongside as .real, so the original behaviour is one
  # copy away.
  #
  # Needs root. With the helper installed --root puts it there; without it this
  # step says so rather than half-doing it.
  adbq shell 'su -c id 2>/dev/null || wsu id 2>/dev/null || id' \
    | tr -d '\r' | grep -q 'uid=0' || {
      echo "  no root on this watch, so the daemon cannot be put in init's slot."
      echo "  run again with --root first, then --vitals."
      DO_VITALS=0
  }
fi

if [ "$DO_VITALS" = "1" ]; then
  adbq shell 'wsu sh -c "
    mount -o rw,remount /system 2>/dev/null
    setprop ctl.stop gh3011_daemon
    sleep 2
    if [ ! -f /system/bin/gh3011_service.real ]; then
        cat /system/bin/gh3011_service > /system/bin/gh3011_service.real
        chmod 755 /system/bin/gh3011_service.real
    fi
    printf \"#!/system/bin/sh\\nexec /data/local/tmp/vitalsd\\n\" > /system/bin/gh3011_service
    chmod 755 /system/bin/gh3011_service
    setprop ctl.start gh3011_daemon
    sleep 3
    ps | grep vitalsd | grep -v grep
  "' | tr -d '\r' | sed 's/^/  /'
  echo "  to go back:  adb shell wsu cat /system/bin/gh3011_service.real \> /system/bin/gh3011_service"
fi

if [ "$DO_CALL" = "1" ]; then
  say "in-call screen"
  # The stock one cannot be dismissed on a watch with no touchscreen: it
  # covers the launcher the moment a call is placed, and the two hardware
  # buttons are not something it listens to, so an outgoing call cannot be
  # cancelled at all.
  #
  # Only the Activity is disabled. The service that actually runs the call is
  # a different component in the same package and is untouched - measured on
  # API 19 with the activity disabled, a call still connects, the launcher
  # keeps the screen, and hanging up returns the line to idle.
  #
  # The launcher does this for itself on first start when it has root. This
  # flag is for a watch without the root helper, and for putting it back.
  COMPONENT=""
  for cand in \
      "com.android.dialer/com.android.incallui.InCallActivity" \
      "com.android.incallui/com.android.incallui.InCallActivity" \
      "com.android.incallui/.InCallActivity" \
      "com.android.phone/.InCallScreen" ; do
    pkg="${cand%%/*}"; cls="${cand#*/}"
    case "$cls" in .*) fq="$pkg$cls" ;; *) fq="$cls" ;; esac
    if adbq shell "dumpsys package $pkg" 2>/dev/null | tr -d '\r' | grep -q "$fq"; then
      COMPONENT="$cand"; break
    fi
  done
  if [ -z "$COMPONENT" ]; then
    echo "  no stock in-call screen found on this build; nothing to disable"
  else
    echo "  $COMPONENT"
    adbq shell "pm disable $COMPONENT" | tr -d '\r' | sed 's/^/  /'
    echo "  put it back with:  adb shell pm enable $COMPONENT"
  fi
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
