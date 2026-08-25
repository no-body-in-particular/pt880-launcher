#!/usr/bin/env bash
# Install the setuid helper that gives the Terminal app a root shell.
#
# The adbd patch in this repo makes `adb shell` uid 0, but that does nothing
# for an app: apps are forked from zygote, not from adbd. What works is that
# Android 4.4's zygote never shrinks the capability bounding set and this build
# has SELinux disabled, so a setuid-root binary exec'd from an app regains full
# privileges. That binary is native/wsu.c.
#
# Needs a boot image built by tools/build_boot_capbnd.py -- the earlier
# build_boot_root.py leaves CapBnd=0xc0, which cannot remount /system, and this
# script has to write to it.
set -e

# Works both as a file and piped into bash. Piped there is no BASH_SOURCE, so
# fall back to an empty HERE -- the helper is fetched over the network in that
# case anyway.
SRC="${BASH_SOURCE[0]:-}"
if [ -n "$SRC" ] && [ -f "$SRC" ]; then
    HERE="$(cd "$(dirname "$SRC")" && pwd)"
else
    HERE=""
fi

# Where to fetch the prebuilt helper when there is no NDK to build one with.
WSU_URL="${WSU_URL:-https://coredump.ws/pt880/wsu}"

# The binary native/build_wsu.sh produces from native/wsu.c. Update this when
# the helper is rebuilt; the script prints both hashes when they disagree.
WSU_SHA256="182033acd72225a1e625f596d828789ec1d3901280f7ba765b6ca7a32d2078b5"

ADB="${ADB:-adb}"
SERIAL="${SERIAL:-}"
[ -n "$SERIAL" ] && ADB="$ADB -s $SERIAL"

# `adb shell` reads stdin, so an unguarded call inside a script that is itself
# piped into bash swallows the rest of the script. Every call closes stdin.
adbq() { $ADB "$@" </dev/null; }

say() { printf '\n== %s\n' "$*"; }
die() { printf '\nerror: %s\n' "$*" >&2; exit 1; }

DEST=/system/xbin/wsu
STAGE=/data/local/tmp/wsu

say "device"
adbq wait-for-device
adbq shell 'echo connected'

say "root check"
UID_OUT="$(adbq shell id | tr -d '\r')"
echo "  $UID_OUT"
case "$UID_OUT" in
  *uid=0*) ;;
  *) die "adb shell is not root -- flash the boot image from tools/build_boot_capbnd.py first" ;;
esac

CAPBND="$(adbq shell 'grep CapBnd /proc/self/status' | tr -d '\r')"
echo "  $CAPBND"
case "$CAPBND" in
  *3fffffffff*) ;;
  *) die "capability bounding set is not full ($CAPBND) -- this is the
       build_boot_root.py image, which cannot remount /system.
       Use tools/build_boot_capbnd.py instead. See NOTES.md section 6." ;;
esac

sha256of() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  elif command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | cut -d' ' -f1
  else echo ""; fi
}

WSU="$HERE/native/wsu"
if [ -n "$HERE" ] && [ -f "$WSU" ]; then
  :
elif [ -n "$HERE" ] && [ -f "$HERE/native/build_wsu.sh" ]; then
  say "building the helper"
  bash "$HERE/native/build_wsu.sh"
else
  # Run from a pipe, or from a checkout with no NDK. Fetch the prebuilt one.
  say "fetching the helper"
  WSU="$(mktemp "${TMPDIR:-/tmp}/wsu.XXXXXX")"
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL -o "$WSU" "$WSU_URL" || die "could not download $WSU_URL"
  elif command -v wget >/dev/null 2>&1; then
    wget -qO "$WSU" "$WSU_URL" || die "could not download $WSU_URL"
  else
    die "no curl or wget, and no NDK to build with"
  fi
  echo "  $WSU_URL -> $WSU ($(wc -c < "$WSU") bytes)"
fi
[ -f "$WSU" ] || die "native/wsu missing and could not be built or fetched"

# A 404 page is a perfectly valid file to download and a useless thing to
# install setuid root, so check what actually arrived.
GOT="$(sha256of "$WSU")"
if [ -n "$GOT" ] && [ "$GOT" != "$WSU_SHA256" ]; then
  die "helper checksum mismatch
       expected $WSU_SHA256
       got      $GOT
       If you rebuilt native/wsu.c, update WSU_SHA256 in this script."
fi

say "push"
adbq push "$WSU" "$STAGE"

say "install into /system"
# Remount, copy, set ownership and the setuid bit, remount back. Done as one
# shell so a failure part-way still hits the final remount.
adbq shell "
  set -e
  mount -o remount,rw /system
  cat $STAGE > $DEST
  chown 0:0 $DEST
  chmod 06755 $DEST
  rm -f $STAGE
  mount -o remount,ro /system
  ls -l $DEST
" | tr -d '\r'

say "verify"

# 1. The mode. This is the whole point of the install, and it is the part that
#    silently fails when chmod does not understand a 4-digit mode.
MODE="$(adbq shell "ls -l $DEST" | tr -d '\r')"
echo "  $MODE"
case "$MODE" in
  -rws*) ;;
  *) die "setuid bit is not set -- $MODE" ;;
esac

# 2. That it runs at all. `adb shell` is already root here, so uid=0 from this
#    proves the binary is not broken, and nothing more than that.
OUT="$(adbq shell "$DEST id" 2>&1 | tr -d '\r')"
echo "  as root: $OUT"
case "$OUT" in
  *uid=0*) ;;
  *) die "the helper did not run: $OUT" ;;
esac

# 3. The case that actually matters: an unprivileged caller. Only busybox can
#    drop privileges from here, so this check is skipped rather than faked when
#    it is absent -- the Terminal app reports the real answer on its first line.
if adbq shell '[ -x /system/xbin/busybox ] && echo yes' | grep -q yes; then
  DROP="$(adbq shell "/system/xbin/busybox setuidgid 2000 $DEST id" 2>&1 | tr -d '\r')"
  case "$DROP" in
    *uid=0*)
      echo "  as uid 2000: $DROP"
      ;;
    *applet*|*not\ found*|*Unknown*)
      echo "  as uid 2000: skipped (this busybox has no setuidgid)"
      ;;
    *)
      die "setuid did not take -- an unprivileged caller got: $DROP
       Check that /system is not mounted nosuid:  adb shell mount | grep system"
      ;;
  esac
else
  echo "  as uid 2000: skipped (no busybox)"
fi

say "done"
cat <<'EOF'
The Terminal app will now open a root shell. Its first line says which:

    uid=0(root) gid=0(root)

To remove it again:

    adb shell 'mount -o remount,rw /system; rm /system/xbin/wsu; mount -o remount,ro /system'
EOF
