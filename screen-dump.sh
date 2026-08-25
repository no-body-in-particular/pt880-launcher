#!/usr/bin/env bash
# What is actually being drawn, as text you can paste back.
#
#     curl -fsSL https://coredump.ws/pt880/screen-dump.sh | bash
#
# "Time and battery, no menu" describes two different failures: our launcher
# with an empty list under its status bar, or the stock clock face, which shows
# the same two things and has no list at all. BatteryIcon redraws the stock
# glyph on purpose, so they look alike. The view tree tells them apart.
ADB="${ADB:-adb}"
SERIAL="${SERIAL:-}"
[ -n "$SERIAL" ] && ADB="$ADB -s $SERIAL"
# `adb shell` reads stdin, and piped into bash this script *is* stdin.
adbq() { $ADB "$@" </dev/null 2>&1 | tr -d '\r'; }

hd() { printf '\n===== %s =====\n' "$*"; }

hd "clearing the way"
adbq shell 'am force-stop com.xrs.bluetooth_device'
# Clear the log first, so the crash section below can only contain things that
# happened during this launch and not scrollback from an earlier attempt.
adbq shell 'logcat -c' >/dev/null 2>&1
adbq shell 'am force-stop org.watchlauncher'
adbq shell 'am start -n org.watchlauncher/.ShellActivity'
sleep 3

hd "did it survive the launch"
adbq shell 'logcat -d' | grep -iE "FATAL|AndroidRuntime|watchlauncher" | head -40

hd "who owns the screen"
adbq shell 'dumpsys activity activities | grep -E "mFocusedActivity|mResumedActivity"'

hd "view tree"
# Every node with its bounds. If the five app rows are here with real bounds,
# the launcher drew and the problem is elsewhere. If they are here as
# [0,0][0,0], it is a layout bug. If they are absent, render() never ran. If
# the package is com.ic.c42, we were looking at the stock clock face.
adbq shell 'uiautomator dump /sdcard/ui.xml' >/dev/null 2>&1
adbq shell 'cat /sdcard/ui.xml' \
  | tr '<' '\n' \
  | grep -E 'text=|package=' \
  | sed -e 's/ *index=/ /' -e 's/resource-id="[^"]*"//' \
        -e 's/checkable[^ ]*//g' -e 's/clickable[^ ]*//g' \
        -e 's/checked[^ ]*//g' -e 's/enabled[^ ]*//g' \
        -e 's/focusable[^ ]*//g' -e 's/focused[^ ]*//g' \
        -e 's/scrollable[^ ]*//g' -e 's/long-clickable[^ ]*//g' \
        -e 's/password[^ ]*//g' -e 's/selected[^ ]*//g' \
        -e 's/  */ /g'

hd "screenshot"
adbq shell 'screencap -p /sdcard/watch.png' >/dev/null 2>&1
$ADB pull /sdcard/watch.png ./watch.png </dev/null 2>&1 | tr -d '\r'
echo "  saved as ./watch.png -- open it and say what you see"

hd "done"
