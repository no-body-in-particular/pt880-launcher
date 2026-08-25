#!/usr/bin/env bash
# Find where this watch keeps its GPS fixes and its heart rate.
#
#     curl -fsSL https://coredump.ws/pt880/probe-sports.sh | bash
#
# Read-only. Two questions:
#
#   1. Can the speed come from Android's own LocationManager -- i.e. does the
#      system hold a recent GPS fix with a speed in it -- or does it have to be
#      read out of the tracker app's own store?
#   2. Is the heart rate a real sensor the framework exposes, or is it
#      something com.enqualcomm.support measures and keeps to itself?
#
# The answers decide whether the sports screen reads two public APIs or has to
# go digging with root.
ADB="${ADB:-adb}"
SERIAL="${SERIAL:-}"
[ -n "$SERIAL" ] && ADB="$ADB -s $SERIAL"
adbq() { $ADB "$@" </dev/null 2>&1 | tr -d '\r'; }

hd() { printf '\n===== %s =====\n' "$*"; }

hd "sensors the framework knows about"
# A heart-rate sensor would show as type 21 (TYPE_HEART_RATE, API 20) or, on a
# build this old, as a vendor type with a telling name.
adbq shell 'dumpsys sensorservice' | head -40

hd "last known location, per provider"
# If there is a fix here with a non-zero speed, the sports screen needs nothing
# but LocationManager.
adbq shell 'dumpsys location' | head -50

hd "gps state"
adbq shell 'getprop | grep -iE "gps|location|agps"' | head -20
adbq shell 'ls -l /data/gps /data/misc/location /data/location 2>/dev/null' | head -20

hd "the tracker app's own store"
adbq shell 'ls -l /data/data/com.enqualcomm.support/databases /data/data/com.enqualcomm.support/files 2>/dev/null'
adbq shell 'ls -l /data/data/com.enqualcomm.support/shared_prefs 2>/dev/null'

hd "other vendor packages that might hold it"
adbq shell 'for p in com.ic.work com.ic.hardware com.ic.c42 com.enqualcomm.imessage; do
  echo "-- $p"; ls -l /data/data/$p/databases /data/data/$p/files 2>/dev/null | head -12
done'

hd "anything log-shaped on the card"
adbq shell 'ls -lt /sdcard | head -25'
adbq shell 'find /sdcard -maxdepth 3 -type f \( -iname "*gps*" -o -iname "*track*" -o -iname "*sport*" -o -iname "*.log" -o -iname "*.nmea" \) 2>/dev/null' | head -25

hd "recently written files under /data (last 2 days)"
adbq shell 'find /data -maxdepth 4 -type f -mtime -2 2>/dev/null | grep -viE "/(dalvik-cache|system/(package|users|proc))" ' | head -40

hd "done"
echo "Paste this back. If you already know which log you meant, say so and skip the guesswork."
