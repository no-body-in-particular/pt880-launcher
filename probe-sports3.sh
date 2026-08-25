#!/usr/bin/env bash
# Is any GPS fix kept on the watch at all?
#
#     curl -fsSL https://coredump.ws/pt880/probe-sports3.sh | bash
#
# Round two ruled out the obvious: the tracker's database has no location
# table. STHEART_RATE turned out to be a measurement *schedule*, not readings,
# and it is empty. So either fixes are retained somewhere less obvious, or the
# firmware reads a position, uploads it and keeps nothing.
#
# Two places left worth looking. PROTOCOL_CMD_RECORDS holds 109 rows of raw
# protocol traffic, and AP01/AP02 are the location report frames -- if those
# are in there, the fix is in there with them. And the app's preferences file
# is where a "last known position" would sit if it caches one.
#
# Read-only.
ADB="${ADB:-adb}"
SERIAL="${SERIAL:-}"
[ -n "$SERIAL" ] && ADB="$ADB -s $SERIAL"
adbq() { $ADB "$@" </dev/null 2>&1 | tr -d '\r'; }

hd() { printf '\n===== %s =====\n' "$*"; }

DIR=./tracker-db
mkdir -p "$DIR"
# Re-pull rather than trusting an older copy; these rows turn over.
$ADB pull /data/data/com.enqualcomm.support/databases/data "$DIR/data" </dev/null >/dev/null 2>&1
$ADB pull /data/data/com.enqualcomm.support/databases/data-journal "$DIR/data-journal" </dev/null >/dev/null 2>&1

hd "what kinds of frame are recorded"
sqlite3 "$DIR/data" "SELECT TOPIC, COUNT(*) FROM PROTOCOL_CMD_RECORDS GROUP BY TOPIC;" 2>&1

hd "the most recent frames"
sqlite3 -header "$DIR/data" \
  "SELECT _id, FLAG, DATE, TIME, TOPIC, substr(CMD,1,160) AS CMD
     FROM PROTOCOL_CMD_RECORDS ORDER BY _id DESC LIMIT 15;" 2>&1

hd "any location report frames"
# AP01 and AP02 are the two location reports; APH1 is a historical upload.
sqlite3 "$DIR/data" \
  "SELECT DATE, TIME, substr(CMD,1,220) FROM PROTOCOL_CMD_RECORDS
    WHERE CMD LIKE '%AP01%' OR CMD LIKE '%AP02%' OR CMD LIKE '%APH1%'
    ORDER BY _id DESC LIMIT 8;" 2>&1

hd "anything with a coordinate in it"
# A latitude on this continent starts with a 4 or 5; rather than guess, look
# for the comma-separated decimal pairs a fix frame is made of.
sqlite3 "$DIR/data" \
  "SELECT DATE, TIME, substr(CMD,1,220) FROM PROTOCOL_CMD_RECORDS
    WHERE CMD GLOB '*[0-9][0-9].[0-9][0-9][0-9][0-9]*'
    ORDER BY _id DESC LIMIT 8;" 2>&1

hd "the app's preferences"
adbq shell 'cat /data/data/com.enqualcomm.support/shared_prefs/com.enqualcomm.support_preferences.xml'

hd "does anything else on the device hold a fix"
adbq shell '/system/xbin/busybox grep -rlE "latitude|lastLocation|last_lat" /data/data/com.enqualcomm.support /data/misc 2>/dev/null | /system/xbin/busybox head -10'

hd "done"
echo "Paste this back."
