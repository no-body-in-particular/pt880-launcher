#!/usr/bin/env bash
# Make the launcher the watch's home screen -- or put the stock one back.
#
#     bash set-as-home.sh            take over
#     bash set-as-home.sh --revert   give it back
#
# Both halves happen together on purpose. Two enabled HOME activities with no
# default chosen means the next boot shows the "which launcher?" chooser, and
# that dialog needs a touchscreen this device does not have. So the alias that
# carries the HOME filter is enabled in the same breath as the stock launcher
# is disabled, and never on its own.
set -e

ADB="${ADB:-adb}"
SERIAL="${SERIAL:-}"
[ -n "$SERIAL" ] && ADB="$ADB -s $SERIAL"
adbq() { $ADB "$@" </dev/null; }

PKG=org.watchlauncher
ALIAS="$PKG/.HomeAlias"

# Vendor apps that sit on top of whatever is home and eat the buttons.
#
# com.xrs.bluetooth_device is the one that matters. It parks a 1x1 activity at
# layer 21010 -- above the launcher's 21005 -- with hasFocus=true and
# canReceiveKeys=true. Nothing is drawn, the launcher behind it is stopped, and
# what you see is the bare system wallpaper while every key press goes into a
# window one pixel across. It does this to the stock launcher too; it is not
# specific to this one.
#
# Disabling it costs nothing here: pairing is done in the Bluetooth screen, and
# the audio link itself is owned by com.android.bluetooth, not by this app.
# apps/watchplayer/README.md documents the same app stealing MENU.
FOCUS_THIEVES="com.xrs.bluetooth_device"

say() { printf '\n== %s\n' "$*"; }
die() { printf '\nerror: %s\n' "$*" >&2; exit 1; }

REVERT=0
[ "${1:-}" = "--revert" ] && REVERT=1

say "device"
adbq wait-for-device
case "$(adbq shell id | tr -d '\r')" in
  *uid=0*) ;;
  *) die "adb shell is not root; pm disable needs it" ;;
esac

adbq shell "pm path $PKG" | grep -q package: || die "$PKG is not installed"

# Find every installed package whose apk looks like a launcher, so this works
# without hardcoding the vendor's package name. dumpsys prints
#
#     Package [com.example.launcher] (deadbeef):
#       codePath=/system/app/L004Launcher.apk
#
# so the package line three lines above a launcher-shaped codePath is the one.
say "finding home apps"
OTHERS="$(adbq shell dumpsys package \
  | tr -d '\r' \
  | grep -B4 -iE 'codePath=.*(launcher|home).*\.apk' \
  | grep -oE 'Package \[[^]]+\]' \
  | sed 's/Package \[//; s/\]//' \
  | grep -v "^$PKG$" \
  | sort -u || true)"

if [ -z "$OTHERS" ]; then
  echo "  none found besides this one"
else
  echo "$OTHERS" | sed 's/^/  /'
fi

if [ "$REVERT" = "1" ]; then
  say "handing home back"
  adbq shell "pm disable $ALIAS" | tr -d '\r' | sed 's/^/  /'
  for p in $OTHERS; do
    adbq shell "pm enable $p" | tr -d '\r' | sed 's/^/  /'
  done
  for p in $FOCUS_THIEVES; do
    adbq shell "pm enable $p" | tr -d '\r' | sed 's/^/  /'
  done
  say "done -- the stock launcher is home again"
  exit 0
fi

say "taking over home"
# Disable the others first. If the run dies between the two, the watch has no
# home activity at all, which is recoverable over adb; the other order leaves
# an unanswerable chooser, which is much worse.
for p in $OTHERS; do
  adbq shell "pm disable $p" | tr -d '\r' | sed 's/^/  /'
done
adbq shell "pm enable $ALIAS" | tr -d '\r' | sed 's/^/  /'

say "clearing focus thieves"
for p in $FOCUS_THIEVES; do
  if adbq shell "pm path $p" | grep -q package:; then
    adbq shell "pm disable $p" | tr -d '\r' | sed 's/^/  /'
    $ADB shell "am force-stop $p" </dev/null >/dev/null 2>&1 || true
  else
    echo "  $p not installed"
  fi
done

say "start it now"
adbq shell "am start -n $PKG/.ShellActivity" | tr -d '\r' | sed 's/^/  /'

# Whatever was on top before should be gone, but the launcher is only really
# up once it is the resumed activity. Report what won rather than claim it.
say "what is on screen now"
adbq shell 'dumpsys activity activities | grep mFocusedActivity' | tr -d '\r' | sed 's/^/  /'

say "done"
cat <<EOF
The watch boots into the launcher from now on.

If mFocusedActivity above is not org.watchlauncher, something else is still
sitting on top. Find it with:

    adb shell 'dumpsys activity activities | grep mFocusedActivity'

To undo everything this script did, including re-enabling the vendor apps:

    bash set-as-home.sh --revert
EOF
