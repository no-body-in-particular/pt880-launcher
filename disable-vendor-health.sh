#!/usr/bin/env bash
# Turn off the vendor's health stack, permanently.
#
#     curl -fsSL https://coredump.ws/pt880/disable-vendor-health.sh | bash
#     curl -fsSL https://coredump.ws/pt880/disable-vendor-health.sh | bash -s -- --undo
#
# The launcher measures its own vitals now: vitalsd drives the Goodix chip
# directly, and the client that used to bind the OEM's sensor service was
# removed. What is left behind is the OEM's service still running, still holding
# the same chip, and still taking turns on a work queue this firmware cannot
# recover if it jams. Nothing needs it any more.
#
# `pm disable` is persistent -- it lives in
# /data/system/users/0/package-restrictions.xml and survives a reboot -- and
# --undo puts every package back. Nothing here touches /system, so a factory
# image is not involved either way.
set -e

ADB="${ADB:-adb}"
SERIAL="${SERIAL:-}"
[ -n "$SERIAL" ] && ADB="$ADB -s $SERIAL"
adbq() { $ADB "$@" </dev/null; }

say() { printf '\n== %s\n' "$*"; }
die() { printf '\nerror: %s\n' "$*" >&2; exit 1; }

UNDO=0
for a in "$@"; do
  case "$a" in
    --undo) UNDO=1 ;;
    -h|--help)
      sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
      exit 0 ;;
    *) die "unknown flag: $a" ;;
  esac
done

# The three that measure, in the order they matter.
#
# com.ic.work is the one that counts. It is the OEM's SensorDataService -- the
# thing the launcher used to bind for a pulse and a saturation -- and it starts
# itself at boot. It also carries an AMap location SDK and holds INTERNET,
# WAKE_LOCK and BODY_SENSORS, so it is worth stopping on grounds that have
# nothing to do with the sensor.
#
# com.ic.temperaturetest is a factory test app with a BackgroundService, which
# is enough to have it hold the thermometer at an awkward moment.
#
# com.ic.hardware is a pedometer test screen and nothing else -- no service, no
# receiver. Included because it belongs to the same set, and harmless either
# way.
PACKAGES="com.ic.work com.ic.temperaturetest com.ic.hardware"

command -v $ADB >/dev/null 2>&1 || die "adb not found on PATH"

say "device"
adbq wait-for-device
adbq shell 'echo connected' | tr -d '\r'

installed() {
  adbq shell "pm list packages $1" | tr -d '\r' | grep -q "^package:$1$"
}

if [ "$UNDO" = "1" ]; then
  say "re-enabling"
  for p in $PACKAGES; do
    if installed "$p"; then
      adbq shell "pm enable $p" | tr -d '\r' | sed 's/^/  /'
    else
      echo "  $p is not on this watch"
    fi
  done
  say "done"
  echo "The launcher does not use any of them; this only puts them back."
  exit 0
fi

say "what is on this watch"
FOUND=""
for p in $PACKAGES; do
  if installed "$p"; then
    FOUND="$FOUND $p"
    echo "  $p"
  else
    echo "  $p  (not installed -- nothing to do)"
  fi
done
# The vendor's own tracker client, which reported to the vendor's server. Long
# gone on a rooted watch, but worth saying so rather than leaving it unmentioned.
if installed "com.enqualcomm.support"; then
  FOUND="$FOUND com.enqualcomm.support"
  echo "  com.enqualcomm.support  (the vendor tracker -- it will fight the"
  echo "                           launcher for the server's device id)"
else
  echo "  com.enqualcomm.support  (already gone)"
fi

[ -n "$FOUND" ] || { say "nothing to disable"; exit 0; }

say "stopping them now"
for p in $FOUND; do
  adbq shell "am force-stop $p" >/dev/null 2>&1 || true
  echo "  $p"
done

say "disabling them for good"
for p in $FOUND; do
  OUT="$(adbq shell "pm disable $p" 2>&1 | tr -d '\r')"
  printf '  %s\n' "$OUT" | sed 's/^  */  /'
  case "$OUT" in
    *"new state: disabled"*) ;;
    *)
      echo "  ^ that did not take. pm disable needs a shell allowed to change"
      echo "    component state; run install-launcher.sh --root first."
      ;;
  esac
done

say "checking"
for p in $FOUND; do
  if adbq shell "pm list packages -d $p" | tr -d '\r' | grep -q "^package:$p$"; then
    echo "  $p  disabled"
  else
    echo "  $p  STILL ENABLED"
  fi
done

say "done"
cat <<'EOF'
What this did not touch, on purpose:

  /system/lib/libICJniUtils.so   still there, and no longer used by anything of
                                 ours. It is a library rather than a package, so
                                 disabling an app would not have touched it
                                 anyway. The launcher read the thermometer
                                 through it until vitalsd learned to answer a
                                 "temp" request, and borrowed its wrist-to-body
                                 conversion until that was disassembled and
                                 reimplemented. Left in place: it is part of the
                                 stock system and removing it buys nothing.

  gh3011_service                 the init slot the vitals daemon runs in. That
                                 was replaced by install-launcher.sh --vitals,
                                 which is a separate matter from these packages.

Put them back with:  --undo
EOF
