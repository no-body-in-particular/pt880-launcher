#!/usr/bin/env bash
# What this watch does to the CPU and the wifi radio when the screen goes off.
#
#     curl -fsSL https://coredump.ws/pt880/probe-power.sh | bash
#
# Read-only: it reports, it changes nothing. The point is to find which knob
# is actually responsible for downloads collapsing with the backlight off,
# rather than guessing at the usual suspects.
set -e

ADB="${ADB:-adb}"
SERIAL="${SERIAL:-}"
[ -n "$SERIAL" ] && ADB="$ADB -s $SERIAL"
sh() { $ADB shell "$@" </dev/null 2>&1 | tr -d '\r'; }
say() { printf '\n== %s\n' "$*"; }

# Root via the helper the launcher installs, falling back to plain shell.
if sh 'ls /system/xbin/wsu' | grep -q wsu; then
    r() { sh "/system/xbin/wsu -c '$*'"; }
else
    r() { sh "$*"; }
fi

say "device"
sh 'getprop ro.product.model; getprop ro.build.version.release; getprop ro.board.platform'

say "wifi sleep policy   (0=sleep 1=never-while-plugged 2=never)"
sh 'settings get global wifi_sleep_policy'

say "wifi interface power management"
r 'iw dev wlan0 get power_save' || true
r 'iwconfig wlan0' || true

say "driver power-save knobs under /sys"
r 'ls -l /sys/module/*/parameters/ 2>/dev/null | grep -i -E "power|psm|sleep|ps_" ' || true
r 'find /sys -maxdepth 6 -name "*power_save*" -o -maxdepth 6 -name "*powersave*" 2>/dev/null | head -20' || true

say "wifi driver module"
r 'lsmod' || true

say "cpu governor"
r 'cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor' || true
r 'cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_available_governors' || true
r 'cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_min_freq /sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq /sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_min_freq /sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq' || true

say "cpus online"
r 'cat /sys/devices/system/cpu/online' || true

say "any cpu-throttle-on-suspend driver"
r 'ls /sys/power/' || true
r 'cat /sys/power/wake_lock 2>/dev/null' || true

say "wake locks the framework is holding right now"
sh 'dumpsys power' | sed -n '1,60p'

say "our locks (expect watchlauncher.map while a download runs)"
sh 'dumpsys power' | grep -i -E "watchlauncher|PARTIAL_WAKE|WIFI" || echo "  none held"

printf '\ndone. paste the whole output back.\n'
