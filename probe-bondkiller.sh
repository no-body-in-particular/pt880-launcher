#!/usr/bin/env bash
# Find what is deleting the bond.
#
#     curl -fsSL https://coredump.ws/pt880/probe-bondkiller.sh | bash
#
# The watch reports UNBOND_REASON_REMOVED, which means removeBond() was called
# locally. The launcher no longer calls it during pairing, so something else on
# the device is policing which devices may pair - plausible on a tracker built
# to be strapped to a child.
#
# Only an app holding BLUETOOTH_ADMIN can do it, which narrows the field to a
# handful. This lists the candidates, then watches a live attempt to see which
# one moves.
#
# It disables nothing. The suggested command at the end is yours to run.
ADB="${ADB:-adb}"
SERIAL="${SERIAL:-}"
[ -n "$SERIAL" ] && ADB="$ADB -s $SERIAL"
adbq() { $ADB "$@" </dev/null 2>&1 | tr -d '\r'; }
BB=/system/xbin/busybox

hd() { printf '\n===== %s =====\n' "$*"; }

hd "the prime suspect"
adbq shell 'pm list packages -d' | grep -i xrs && echo "  ^ disabled already" \
  || echo "  com.xrs.bluetooth_device is ENABLED"
adbq shell "$BB ps -o pid,args 2>/dev/null | $BB grep -iE 'xrs|enqualcomm|ic\.c42' | $BB grep -v grep"

hd "who can even do this"
# removeBond needs BLUETOOTH_ADMIN. Anything without it is not the culprit.
for p in com.xrs.bluetooth_device com.enqualcomm.support com.ic.c42 com.ic.work \
         com.ic.hardware com.thunderst.radio com.android.settings; do
  st=$(adbq shell "pm list packages $p" | grep -c "$p")
  adm=$(adbq shell "dumpsys package $p" | grep -c "BLUETOOTH_ADMIN")
  dis=$(adbq shell 'pm list packages -d' | grep -c "$p")
  [ "$st" = "0" ] && continue
  printf "  %-32s bt_admin=%s disabled=%s\n" "$p" "$adm" "$dis"
done

hd "watch a live attempt"
adbq shell 'logcat -c' >/dev/null 2>&1
cat <<'EOF'

  ------------------------------------------------------------------
  On the watch now: Bluetooth -> Scan -> pick the trackball.
  45 seconds.
  ------------------------------------------------------------------
EOF
for i in $(seq 45 -5 5); do printf "\r  %2ds " $i; sleep 5; done; printf "\r      \n"

hd "who called removeBond"
adbq shell 'logcat -d -v threadtime' | grep -aiE "remove_bond|removeBond|bond_state|BondStateMachine|REMOVED" | tail -25

hd "everything around it"
adbq shell 'logcat -d -v threadtime' | grep -aiE "btif_dm|bt_btif|bond|pair" | tail -40

hd "which pid was that"
adbq shell 'dumpsys activity processes' | grep -aE "ProcessRecord|pid=" | head -25

hd "done"
cat <<'EOF'
If com.xrs.bluetooth_device was enabled above, the quickest test is to turn it
off and try again:

    adb shell pm disable com.xrs.bluetooth_device

and to put it back:

    adb shell pm enable com.xrs.bluetooth_device
EOF
