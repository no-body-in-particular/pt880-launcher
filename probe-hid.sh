#!/usr/bin/env bash
# Why a paired keyboard or mouse does nothing.
#
#     curl -fsSL https://coredump.ws/pt880/probe-hid.sh | bash
#
# Bonding and connecting are different things. A device can be paired -- the
# link key stored, the name in the list -- while the HID profile never comes
# up, in which case no key ever reaches Android at all and the fault is
# nowhere near the app.
#
# The decisive question is whether an input node appeared. If the kernel has
# one, the link is up and the problem is in the app. If it has not, the link
# never formed and the app never had anything to receive.
#
# Read-only.
ADB="${ADB:-adb}"
SERIAL="${SERIAL:-}"
[ -n "$SERIAL" ] && ADB="$ADB -s $SERIAL"
adbq() { $ADB "$@" </dev/null 2>&1 | tr -d '\r'; }
BB=/system/xbin/busybox

hd() { printf '\n===== %s =====\n' "$*"; }

hd "did an input node appear for them"
# The decisive one. A connected bluetooth keyboard shows up here as a real
# input device alongside the watch's own two keys.
adbq shell 'getevent -pl 2>/dev/null' | grep -aE "add device|name:|KEY_A|BTN_LEFT|REL_X" | head -40

hd "what android thinks is attached"
adbq shell 'dumpsys input' | grep -aE "^ *[0-9]+: |Name:|KeyboardType|Sources|Location" | head -40

hd "bonded devices and their class"
# Class of device decides which profile the app tries. 0x...540 is a keyboard,
# 0x...580 a mouse, 0x...5C0 a combo; anything 0x...5xx is HID.
adbq shell 'dumpsys bluetooth_manager' | grep -aiE "Bonded|address|name|class|state|connect" | head -40

hd "the hid host profile"
# If this profile is absent from the build, nothing the app does can connect a
# keyboard, and that would be the whole answer.
adbq shell 'dumpsys bluetooth_manager' | grep -aiE "input|hid|hog" | head -20
adbq shell "$BB ls -l /data/misc/bluedroid/ 2>/dev/null"
adbq shell "$BB grep -aiE 'hid|input' /data/misc/bluedroid/bt_config.xml 2>/dev/null | $BB head -20"

hd "what the log says when they connect"
adbq shell 'logcat -d -t 400' | grep -aiE "hid|bluetoothinput|MouseObserver|input device|keyboard" | tail -30

hd "the vendor's mouse gate"
# A vendor class named MouseObserverController turned up disabling mouse input
# when the launcher was focused. Worth seeing what else it says.
adbq shell 'logcat -d -t 600' | grep -ai "MouseObserver" | tail -12

hd "done"
echo "Paste this back."
