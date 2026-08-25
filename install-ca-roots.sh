#!/usr/bin/env bash
# Give the watch a modern set of certificate authorities.
#
#     curl -fsSL https://coredump.ws/pt880/install-ca-roots.sh | bash
#     ... | bash -s -- --replace     wipe the old store first
#
# Android 4.4's trust store was assembled in 2013 and knows no ISRG root, so a
# Let's Encrypt chain - most of the web now, and coredump.ws with it - fails to
# validate. Old Androids used to survive on Let's Encrypt's cross-signature
# from DST Root CA X3, which they accepted despite its expiry. That cross-sign
# is gone, and the failure is silent: every https request simply reports
# itself as offline.
#
# The bundle is 145 roots from the Mozilla CA list, which is where Android's
# own store comes from. Files are named by the OpenSSL *old* subject hash, the
# 0.9.8 algorithm Android uses - a certificate under the modern hash is never
# consulted, which fails silently and looks exactly like success.
#
# Default is add-only: nothing already trusted is touched, and only roots the
# device has never heard of are installed. --replace swaps the store wholesale,
# which also drops roots that have since been distrusted, but risks removing
# one something on the watch depends on. The old store is backed up either way.
#
# Needs the boot image from tools/build_boot_capbnd.py - the one that can
# remount /system.
set -e

ADB="${ADB:-adb}"
SERIAL="${SERIAL:-}"
[ -n "$SERIAL" ] && ADB="$ADB -s $SERIAL"
adbq() { $ADB "$@" </dev/null; }

BASE="${BASE:-https://coredump.ws/pt880}"
STORE=/system/etc/security/cacerts
BACKUP=/sdcard/cacerts-backup.tar.gz

REPLACE=0
[ "${1:-}" = "--replace" ] && REPLACE=1

say() { printf '\n== %s\n' "$*"; }
die() { printf '\nerror: %s\n' "$*" >&2; exit 1; }

say "device"
adbq wait-for-device
case "$(adbq shell id | tr -d '\r')" in
  *uid=0*) ;;
  *) die "adb shell is not root - flash the build_boot_capbnd.py image first" ;;
esac

before=$(adbq shell "ls $STORE | wc -l" | tr -d '\r ')
echo "  certificates now: $before"

say "space on /system"
adbq shell "df /system" | tr -d '\r' | tail -1 | sed 's/^/  /'

TMP="$(mktemp -d "${TMPDIR:-/tmp}/cacerts.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT

say "fetching the bundle"
if command -v curl >/dev/null 2>&1; then
  curl -fsSL -o "$TMP/cacerts.tar.gz" "$BASE/cacerts.tar.gz" || die "cannot fetch the bundle"
else
  wget -qO "$TMP/cacerts.tar.gz" "$BASE/cacerts.tar.gz" || die "cannot fetch the bundle"
fi
mkdir -p "$TMP/certs"
tar xzf "$TMP/cacerts.tar.gz" -C "$TMP/certs" || die "bundle is not a tarball"
count=$(ls "$TMP/certs" | wc -l | tr -d ' ')
echo "  $count certificates"
[ "$count" -gt 50 ] || die "that is too few to be the real bundle"

say "backing up the old store"
# Onto the card, not into /system: the point of a backup is to survive the
# thing that might go wrong with the filesystem being written to.
adbq shell "cd /system/etc/security && /system/xbin/busybox tar czf $BACKUP cacerts" >/dev/null 2>&1 \
  || adbq shell "cd /system/etc/security && tar czf $BACKUP cacerts" >/dev/null 2>&1 \
  || echo "  could not tar; continuing without one"
adbq shell "ls -l $BACKUP" | tr -d '\r' | sed 's/^/  /'

say "installing"
adbq shell "mount -o remount,rw /system" >/dev/null 2>&1 || die "cannot remount /system"

if [ "$REPLACE" = "1" ]; then
  echo "  removing the old store"
  adbq shell "rm -f $STORE/*" >/dev/null 2>&1 || true
fi

# Pushed as a directory in one go rather than file by file: 145 separate adb
# push calls is a minute of round trips for a megabyte of data.
adbq push "$TMP/certs/." "$STORE/" 2>&1 | tail -1 | sed 's/^/  /'
adbq shell "chmod 644 $STORE/*; chown 0:0 $STORE/*" >/dev/null 2>&1

adbq shell "mount -o remount,ro /system" >/dev/null 2>&1 || true

say "verify"
after=$(adbq shell "ls $STORE | wc -l" | tr -d '\r ')
echo "  certificates now: $after  (was $before)"
adbq shell "ls $STORE/6187b673.0" | tr -d '\r' | sed 's/^/  ISRG Root X1: /'

say "done"
cat <<EOF
The store is read when a process starts, so restart anything that needs it:

    adb shell am force-stop org.watchlauncher

To undo:

    adb shell 'mount -o remount,rw /system; rm -rf $STORE; cd /system/etc/security && tar xzf $BACKUP; mount -o remount,ro /system'
EOF
