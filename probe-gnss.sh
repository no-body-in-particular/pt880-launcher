#!/usr/bin/env bash
# Can this watch produce a position often enough to navigate with?
#
#     curl -fsSL https://coredump.ws/pt880/probe-gnss.sh | bash
#
# Everything else about navigation is straightforward. This is not. The
# framework holds no fixes, the gps provider is not in the allowed list, and
# the tracker firmware owns the receiver through its own gpsd on a 600 second
# cycle. There are three ways in and this works out which are open:
#
#   1. the framework provider, if it can be switched on and will deliver;
#   2. the firmware's own cadence, if it can be turned down;
#   3. the GNSS chip directly, if gpsd leaves a readable NMEA source.
#
# Read-only. Nothing here changes a setting.
ADB="${ADB:-adb}"
SERIAL="${SERIAL:-}"
[ -n "$SERIAL" ] && ADB="$ADB -s $SERIAL"
adbq() { $ADB "$@" </dev/null 2>&1 | tr -d '\r'; }
BB=/system/xbin/busybox

hd() { printf '\n===== %s =====\n' "$*"; }

hd "what gpsd is and what it has open"
adbq shell "$BB ps -o pid,user,args 2>/dev/null | $BB grep -i gps | $BB grep -v grep"
adbq shell 'getprop | grep -iE "gps|gnss|wcn|supl|agps"'
adbq shell "for p in \$($BB pidof gpsd 2>/dev/null); do
  echo \"-- pid \$p\"; $BB ls -l /proc/\$p/fd 2>/dev/null | $BB head -30
done"

hd "gnss device nodes"
# A serial node or a chip-specific one is what option 3 would read from.
adbq shell "$BB ls -l /dev/tty* /dev/gps* /dev/gnss* /dev/ge2* /dev/slog* 2>/dev/null | $BB head -25"
adbq shell "$BB ls -l /dev/socket 2>/dev/null | $BB grep -iE 'gps|gnss|loc'"

hd "the hal and its config"
adbq shell "$BB ls -l /system/lib/hw/gps.* /system/lib/libgps* /system/etc/gps* 2>/dev/null"
adbq shell 'cat /system/etc/gps.conf 2>/dev/null'

hd "who is using location right now"
# appops answers the stronger question: not what the app can do, what it did.
adbq shell 'dumpsys appops' | grep -iE "GPS|LOCATION|WIFI_SCAN|NEIGHBORING" | head -20

hd "the firmware's own cadence, and whether it is settable"
adbq shell 'getprop persist.sys.location_cycle; getprop persist.sys.location_mode'
adbq shell 'cat /data/data/com.enqualcomm.support/shared_prefs/com.enqualcomm.support_preferences.xml 2>/dev/null | grep -iE "location|cycle|mode"'

hd "is the provider merely switched off"
adbq shell 'settings get secure location_providers_allowed'
adbq shell 'dumpsys location' | grep -A6 "gps Internal State"

hd "sensors again, for heading"
# No magnetometer means no compass, which decides breadcrumbs vs turn-by-turn.
adbq shell 'dumpsys sensorservice' | sed -n '/Sensor List/,/^$/p' | head -12

hd "done"
cat <<'EOF'
Paste this back.

Separately, and worth more than any of the above: open Sports, hold A, pick
"GPS provider" to switch it on, then stand outside for two or three minutes and
watch the line under the speed. If it ever says "gps" rather than "wifi", the
receiver is reachable and navigation is worth building.
EOF
