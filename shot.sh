#!/usr/bin/env bash
# Grab what is on the watch screen and print it as pasteable text.
#
#     curl -fsSL https://coredump.ws/pt880/shot.sh | bash
#
# uiautomator produces nothing on this build, so the view tree is unavailable
# and the pixels are the only remaining evidence. A 240x240 PNG is a few
# kilobytes, which is small enough to paste into a chat and decode at the
# other end.
ADB="${ADB:-adb}"
SERIAL="${SERIAL:-}"
[ -n "$SERIAL" ] && ADB="$ADB -s $SERIAL"
adbq() { $ADB "$@" </dev/null 2>&1 | tr -d '\r'; }

hd() { printf '\n===== %s =====\n' "$*"; }

hd "restart the launcher"
adbq shell 'am force-stop com.xrs.bluetooth_device'
adbq shell 'am start -n org.watchlauncher/.ShellActivity' | sed 's/^/  /'
sleep 3
adbq shell 'dumpsys activity activities | grep mResumedActivity' | sed 's/^/  /'

hd "capture"
adbq shell 'screencap -p /sdcard/watch.png'
$ADB pull /sdcard/watch.png ./watch.png </dev/null 2>&1 | tr -d '\r' | sed 's/^/  /'

hd "png as base64 -- paste this whole block back"
# BSD base64 on macOS reads stdin happily; -w is a GNU-ism and is not used.
base64 < ./watch.png
echo "===== end of png ====="

hd "done"
echo "./watch.png is also on disk if you want to look at it yourself."
