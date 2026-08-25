#!/usr/bin/env bash
# Find the GPS fixes inside the tracker's database.
#
#     curl -fsSL https://coredump.ws/pt880/probe-sports2.sh | bash
#
# Round one established that Android's LocationManager has nothing -- no last
# known location, and the gps provider is not even in Enabled Providers. The
# fixes therefore live in com.enqualcomm.support's own store, and
# databases/data (114 KB, written today) is the candidate.
#
# The schema work happens on the Mac, which has sqlite3; the watch does not.
# Round one also tripped over the device having no `head` and an `ls` with no
# -t, so everything on-device goes through busybox here.
ADB="${ADB:-adb}"
SERIAL="${SERIAL:-}"
[ -n "$SERIAL" ] && ADB="$ADB -s $SERIAL"
adbq() { $ADB "$@" </dev/null 2>&1 | tr -d '\r'; }
BB=/system/xbin/busybox

hd() { printf '\n===== %s =====\n' "$*"; }

DIR=./tracker-db
mkdir -p "$DIR"

hd "pull the database"
# The journal comes too and keeps its name, so sqlite can roll it back and we
# read the same state the tracker sees rather than a torn one.
$ADB pull /data/data/com.enqualcomm.support/databases/data "$DIR/data" </dev/null 2>&1 | tr -d '\r' | sed 's/^/  /'
$ADB pull /data/data/com.enqualcomm.support/databases/data-journal "$DIR/data-journal" </dev/null 2>&1 | tr -d '\r' | sed 's/^/  /'

if ! command -v sqlite3 >/dev/null 2>&1; then
  echo "  no sqlite3 on this Mac -- unexpected, it ships with macOS"
  exit 1
fi

hd "tables"
sqlite3 "$DIR/data" ".tables" 2>&1

hd "schema"
sqlite3 "$DIR/data" ".schema" 2>&1 | cut -c1-400

hd "row counts"
for t in $(sqlite3 "$DIR/data" "SELECT name FROM sqlite_master WHERE type='table';" 2>/dev/null); do
  n=$(sqlite3 "$DIR/data" "SELECT COUNT(*) FROM \"$t\";" 2>/dev/null)
  printf '  %-32s %s\n' "$t" "$n"
done

hd "tables that look like fixes or heart rate"
# Any table with a column named after a coordinate, a speed, or a rate.
for t in $(sqlite3 "$DIR/data" "SELECT name FROM sqlite_master WHERE type='table';" 2>/dev/null); do
  cols=$(sqlite3 "$DIR/data" "PRAGMA table_info(\"$t\");" 2>/dev/null | cut -d'|' -f2 | tr '\n' ' ')
  case "$cols" in
    *lat*|*Lat*|*lng*|*Lng*|*lon*|*Lon*|*speed*|*Speed*|*heart*|*Heart*|*rate*|*Rate*|*hr*|*HR*|*bpm*)
      echo "-- $t"
      echo "   cols: $cols"
      echo "   last 3 rows:"
      sqlite3 -header "$DIR/data" "SELECT * FROM \"$t\" ORDER BY rowid DESC LIMIT 3;" 2>&1 | sed 's/^/     /' | cut -c1-300
      ;;
  esac
done

hd "anything log-shaped on the card (via busybox this time)"
adbq shell "$BB ls -lt /sdcard 2>/dev/null | $BB head -20"
adbq shell "$BB find /sdcard /data/gps /data/misc -maxdepth 3 -type f \
  \( -iname '*gps*' -o -iname '*track*' -o -iname '*sport*' -o -iname '*.nmea' -o -iname '*.log' \) 2>/dev/null | $BB head -25"

hd "files the tracker has written recently"
adbq shell "$BB find /data/data/com.enqualcomm.support -type f -mmin -1440 2>/dev/null | $BB head -25"

hd "can the gps provider be turned on"
adbq shell 'settings get secure location_providers_allowed'

hd "done"
echo "Paste this back. The database is in $DIR if anything needs a closer look."
