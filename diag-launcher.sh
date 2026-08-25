#!/usr/bin/env bash
# What the watch thinks is going on. Read-only: this changes nothing.
#
#     curl -fsSL https://coredump.ws/pt880/diag-launcher.sh | bash
#
# A black screen has two very different causes and this tells them apart:
# either the launcher is not the thing on screen (a home-resolution problem,
# which set-as-home.sh can cause), or it is on screen and crashed or drew
# nothing (our bug). The input section covers the other half of the symptom --
# buttons that do nothing.
ADB="${ADB:-adb}"
SERIAL="${SERIAL:-}"
[ -n "$SERIAL" ] && ADB="$ADB -s $SERIAL"
# `adb shell` reads stdin, and piped into bash this script *is* stdin.
adbq() { $ADB "$@" </dev/null 2>&1 | tr -d '\r'; }

PKG=org.watchlauncher

hd() { printf '\n===== %s =====\n' "$*"; }

hd "device"
adbq get-state
adbq shell 'getprop ro.product.model; getprop ro.build.version.sdk; getprop ro.build.display.id'

hd "is it installed"
adbq shell "pm path $PKG"
adbq shell "dumpsys package $PKG | grep -E 'versionName|firstInstallTime|lastUpdateTime|flags='"

hd "enabled state of our components"
# enabled=0 is the manifest default (for HomeAlias that means DISABLED, since
# the manifest sets android:enabled="false"); 1 = force-enabled, 2 = disabled.
adbq shell "dumpsys package $PKG | sed -n '/disabledComponents/,/^ *[a-zA-Z]*Permissions:/p'"

hd "who is home"
# Every activity that answers the HOME category, and whether its package is
# enabled. If this list is empty, that alone explains a black screen.
adbq shell 'pm list packages -d' | sed 's/^/  disabled: /'
adbq shell 'dumpsys package | grep -iE "codePath=.*(launcher|home).*\.apk" -B4' | grep -E "Package \[|codePath="

hd "what is actually on screen"
adbq shell 'dumpsys activity activities | grep -E "mFocusedActivity|mResumedActivity|mCurrentFocus|Run #"'

hd "windows on top"
# A window above the launcher's layer with hasFocus=true is the whole answer
# when the screen is black and the buttons do nothing.
adbq shell 'dumpsys window windows | grep -E "^ *Window \{|mHasSurface|mViewVisibility|hasFocus=|frame=\[" ' | head -60

hd "crash log"
# The crash buffer does not exist on every 4.4 build, so fall back to main.
adbq shell "logcat -d -b crash -t 200" | grep -iE "watchlauncher|AndroidRuntime|FATAL" || true
adbq shell "logcat -d -t 400" | grep -iE "watchlauncher|AndroidRuntime|FATAL|ActivityManager.*$PKG" || true

hd "input devices"
# The launcher tells a real Bluetooth keyboard from the watch's own two
# buttons by KeyboardType: 2 (alphabetic) is treated as a keyboard and its
# keys are routed to the terminal instead of to the gesture decoder. If the
# built-in keypad reports 2, that is why the buttons do nothing.
adbq shell 'dumpsys input | grep -E "^ *[0-9]+: |Name:|KeyboardType|Sources|Location|KeyLayoutFile"'

hd "key layouts in use"
adbq shell 'ls -l /system/usr/keylayout/'

hd "done"
echo "Paste all of the above back."
