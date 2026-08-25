#!/usr/bin/env bash
# Capture why the launcher died, including native crashes.
#
#     curl -fsSL https://coredump.ws/pt880/crashlog.sh | bash
#
# A native crash - a segfault in the PNG decoder, the GPS HAL, anything below
# the Java layer - never reaches an UncaughtExceptionHandler. It is reported
# by NativeCrashListener, and the backtrace that matters is written by
# debuggerd under the DEBUG tag, plus a tombstone on disk.
set -e

ADB="${ADB:-adb}"
SERIAL="${SERIAL:-}"
[ -n "$SERIAL" ] && ADB="$ADB -s $SERIAL"
sh() { $ADB shell "$@" </dev/null 2>&1 | tr -d '\r'; }
say() { printf '\n===== %s\n' "$*"; }

if sh 'ls /system/xbin/wsu' | grep -q wsu; then
    r() { sh "/system/xbin/wsu -c '$*'"; }
else
    r() { sh "$*"; }
fi

say "the java-side record this app keeps"
sh 'cat /sdcard/Documents/crash.txt' | tail -40

say "most recent tombstone (the native backtrace)"
LAST=$(r 'ls -t /data/tombstones/ 2>/dev/null | head -1')
if [ -n "$LAST" ]; then
    echo "-- /data/tombstones/$LAST"
    r "cat /data/tombstones/$LAST" | sed -n '1,80p'
else
    echo "  none"
fi

say "debuggerd output still in the log buffer"
sh 'logcat -d -v brief -s DEBUG:* -s libc:* -s DEBUG' | tail -80

say "anything the launcher logged before dying"
sh 'logcat -d -v brief' | grep -iE "watchmap|watchlauncher|FATAL|signal|SIGSEGV|SIGABRT|art |dalvik" | tail -60

printf '\ndone. paste from "most recent tombstone" down.\n'
