#!/usr/bin/env bash
# Put the watch's adb on wifi, so it can be reached without the cradle.
#
#     curl -fsSL https://coredump.ws/pt880/adb-wifi.sh | bash            # this session only
#     curl -fsSL https://coredump.ws/pt880/adb-wifi.sh | bash -s -- --persist
#
# Android has always been able to do this; it is just not exposed. adbd reads
# service.adb.tcp.port when it starts, so setting that and restarting it is
# the whole trick. Needs the watch on USB once to set it up, and root to make
# it survive a reboot.
set -e

ADB="${ADB:-adb}"
SERIAL="${SERIAL:-}"
[ -n "$SERIAL" ] && ADB="$ADB -s $SERIAL"
adbq() { $ADB "$@" </dev/null; }
say() { printf '\n== %s\n' "$*"; }

PERSIST=0
[ "${1:-}" = "--persist" ] && PERSIST=1

say "the watch's address on your network"
IP=$(adbq shell "ip -f inet addr show wlan0 2>/dev/null | sed -n 's/.*inet \([0-9.]*\).*/\1/p'" | tr -d '\r' | head -1)
if [ -z "$IP" ]; then
    echo "no address on wlan0 - is wifi connected?" >&2
    adbq shell 'ip addr' | tr -d '\r' | sed 's/^/   /' >&2
    exit 1
fi
echo "   $IP"

say "restart adbd listening on tcp"
adbq shell 'setprop service.adb.tcp.port 5555' >/dev/null 2>&1 || true
# tcpip does the same thing through adb's own channel, and works without root.
adbq tcpip 5555 >/dev/null 2>&1 || true
sleep 3

if [ "$PERSIST" = "1" ]; then
    say "make it survive a reboot"
    # The property is read by adbd at start, so it has to be set before adbd
    # runs. A line in build.prop is the least invasive place: no init script,
    # nothing to run, and it is undone by deleting the line.
    if adbq shell 'ls /system/xbin/wsu' | grep -q wsu; then
        adbq shell "/system/xbin/wsu -c 'mount -o rw,remount /system 2>/dev/null || mount -o rw,remount /system /system'"
        adbq shell "/system/xbin/wsu -c 'grep -q ^service.adb.tcp.port /system/build.prop || echo service.adb.tcp.port=5555 >> /system/build.prop'"
        adbq shell "/system/xbin/wsu -c 'mount -o ro,remount /system 2>/dev/null || true'"
        echo "   added to /system/build.prop"
    else
        echo "   no root helper (/system/xbin/wsu) - run install-root-helper.sh first" >&2
        echo "   without it this lasts until the next reboot" >&2
    fi
fi

say "connect"
echo "   adb connect $IP:5555"
$ADB connect "$IP:5555" 2>&1 | sed 's/^/   /'

cat <<TXT

Unplug the cradle and it stays connected. To go back to USB:

    adb -s $IP:5555 usb        # or just: adb disconnect $IP:5555

If it stops answering after a reboot and you did not use --persist, plug it
in once and run this again. With --persist it comes back on its own, because
the property is in build.prop before adbd starts.

Everything that talks to the watch takes SERIAL, so:

    SERIAL=$IP:5555 curl -fsSL https://coredump.ws/pt880/install-launcher.sh | bash
TXT
