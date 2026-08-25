#!/usr/bin/env bash
# Watch a pairing attempt happen, live.
#
#     curl -fsSL https://coredump.ws/pt880/probe-pair.sh | bash
#
# "Pairing failed" is the app reporting that the bond went to BOND_NONE. Why it
# went there is in the broadcast's reason code and in the stack's own log, and
# neither is visible after the fact -- so this clears the log, waits while you
# try, and then shows what happened.
#
# Read-only.
ADB="${ADB:-adb}"
SERIAL="${SERIAL:-}"
[ -n "$SERIAL" ] && ADB="$ADB -s $SERIAL"
adbq() { $ADB "$@" </dev/null 2>&1 | tr -d '\r'; }

hd() { printf '\n===== %s =====\n' "$*"; }

hd "before"
adbq shell 'dumpsys bluetooth_manager' | grep -aiE "^ *[0-9A-F]{2}:|bonded|bonding" | head -20

adbq shell 'logcat -c' >/dev/null 2>&1

cat <<'EOF'

  ------------------------------------------------------------------
  On the watch, now:

    1. Bluetooth  ->  the trackball  ->  Forget   (if it is listed)
    2. put the trackball into pairing mode
    3. Bluetooth  ->  Scan  ->  pick it

  You have 45 seconds. The watch will show the real reason on its own
  status line now as well - "Failed: timed out (just works)" and so on.
  ------------------------------------------------------------------

EOF
for i in $(seq 45 -5 5); do printf "\r  %2ds remaining " $i; sleep 5; done
printf "\r                    \n"

hd "bond state changes and why"
adbq shell 'logcat -d' | grep -aiE "BondStateMachine|bond state|UNBOND|createBond|removeBond|REASON" | tail -30

hd "the pairing request itself"
adbq shell 'logcat -d' | grep -aiE "PAIRING_REQUEST|pairing variant|ssp|pin_request|sspRequest|authorize" | tail -25

hd "what the stack said"
adbq shell 'logcat -d' | grep -aiE "bt_btif|bluedroid|btif_dm|BluetoothBondState|hid|smp|auth" | tail -35

hd "what the launcher said"
adbq shell 'logcat -d' | grep -ai "watchlauncher" | tail -15

hd "after"
adbq shell 'dumpsys bluetooth_manager' | grep -aiE "^ *[0-9A-F]{2}:|bonded|bonding" | head -20

hd "done"
echo "Paste this back, and the line the watch showed."
