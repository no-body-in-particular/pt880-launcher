#!/usr/bin/env bash
# Teach the watch about certificate authorities issued after 2013.
#
#     curl -fsSL https://coredump.ws/pt880/install-ca-roots.sh | bash
#
# Android 4.4's trust store was assembled in 2013 and knows no ISRG root, so a
# Let's Encrypt chain - which is most of the web now, and coredump.ws with it -
# fails to validate. Old Androids used to survive on Let's Encrypt's
# cross-signature from DST Root CA X3, which they accepted despite its expiry.
# That cross-sign is gone.
#
# The launcher carries these roots itself and does not need this. Everything
# else on the watch does: the browser, the vendor apps, anything at all that
# speaks https. This adds them to the system store so they all work.
#
# Nothing is removed. Adding a root the device is simply too old to have heard
# of is a different act from replacing a trust store, and only the first one is
# safe to do to a device you rely on.
#
# Needs the boot image from tools/build_boot_capbnd.py, which is the one that
# can remount /system.
set -e

ADB="${ADB:-adb}"
SERIAL="${SERIAL:-}"
[ -n "$SERIAL" ] && ADB="$ADB -s $SERIAL"
adbq() { $ADB "$@" </dev/null; }

BASE="${BASE:-https://coredump.ws/pt880}"
STORE=/system/etc/security/cacerts

say() { printf '\n== %s\n' "$*"; }
die() { printf '\nerror: %s\n' "$*" >&2; exit 1; }

say "device"
adbq wait-for-device
case "$(adbq shell id | tr -d '\r')" in
  *uid=0*) ;;
  *) die "adb shell is not root - flash the build_boot_capbnd.py image first" ;;
esac

TMP="$(mktemp -d "${TMPDIR:-/tmp}/caroots.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT

say "fetching the roots"
if command -v curl >/dev/null 2>&1; then
  curl -fsSL -o "$TMP/roots.pem" "$BASE/roots.pem" || die "cannot fetch $BASE/roots.pem"
else
  wget -qO "$TMP/roots.pem" "$BASE/roots.pem" || die "cannot fetch $BASE/roots.pem"
fi
grep -c "BEGIN CERTIFICATE" "$TMP/roots.pem" | sed 's/^/  certificates: /'

command -v openssl >/dev/null 2>&1 || die "openssl is needed to name the files"

say "installing"
# Android names each file by the OpenSSL *old* subject hash, the 0.9.8
# algorithm - not the modern one. A file under the wrong name is simply never
# consulted, which fails silently and looks exactly like it worked.
( cd "$TMP" && csplit -sz -f cert- -b '%d.pem' roots.pem '/BEGIN CERTIFICATE/' '{*}' )

adbq shell 'mount -o remount,rw /system' >/dev/null 2>&1 || die "cannot remount /system"

installed=0
for f in "$TMP"/cert-*.pem; do
  hash=$(openssl x509 -in "$f" -noout -subject_hash_old 2>/dev/null) || continue
  cn=$(openssl x509 -in "$f" -noout -subject | sed 's/.*CN *= *//')
  # Android's own files are the PEM followed by a text dump of the certificate.
  # Only the PEM is parsed, but matching the format keeps the directory
  # consistent for anything that reads it by eye.
  {
    cat "$f"
    openssl x509 -in "$f" -noout -text
  } > "$TMP/$hash.0"

  adbq push "$TMP/$hash.0" "$STORE/$hash.0" >/dev/null 2>&1 \
      || { echo "  FAILED $cn"; continue; }
  adbq shell "chmod 644 $STORE/$hash.0; chown 0:0 $STORE/$hash.0" >/dev/null 2>&1
  printf "  %-14s -> %s.0\n" "$cn" "$hash"
  installed=$((installed + 1))
done

adbq shell 'mount -o remount,ro /system' >/dev/null 2>&1 || true

[ "$installed" -gt 0 ] || die "nothing was installed"

say "verify"
adbq shell "ls -l $STORE | wc -l" | tr -d '\r' | sed 's/^/  certificates in the store: /'

say "done"
cat <<'EOF'
The store is read when a process starts, so restart anything that needs it:

    adb shell am force-stop org.watchlauncher

To undo, delete the two files:

    adb shell 'mount -o remount,rw /system; rm /system/etc/security/cacerts/6187b673.0 /system/etc/security/cacerts/558fe057.0; mount -o remount,ro /system'
EOF
