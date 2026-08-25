#!/usr/bin/env bash
# Build watchlauncher.apk.
#
# No Gradle: the target is API 19 and the modern AGP stack fights that harder
# than it helps, so this drives the SDK tools directly --
# aapt -> javac -> d8 -> zipalign -> apksigner.
#
# Runs on both Windows/MSYS and Linux: the SDK ships .exe/.bat wrappers on the
# former and bare executables on the latter, so the suffixes are probed rather
# than hardcoded.
#
# Override ANDROID_SDK_ROOT / JAVA_HOME if yours live elsewhere.
set -e

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -z "$SDK" ]; then
  for c in "$HOME/AppData/Local/Android/Sdk" "$HOME/Android/Sdk" \
           "$HOME/Library/Android/sdk" /opt/android-sdk; do
    [ -d "$c" ] && { SDK="$c"; break; }
  done
fi
[ -n "$SDK" ] && [ -d "$SDK" ] || { echo "Android SDK not found -- set ANDROID_SDK_ROOT" >&2; exit 1; }

# Newest build-tools and platform present, rather than a pinned version.
BT="$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1)"
BT="${BT%/}"
AJAR="$(ls "$SDK"/platforms/*/android.jar 2>/dev/null | sort -V | tail -1)"
[ -n "$BT" ] && [ -n "$AJAR" ] || { echo "need build-tools and a platform android.jar under $SDK" >&2; exit 1; }

# .exe/.bat on Windows, bare names everywhere else.
if [ -f "$BT/aapt.exe" ]; then EXE=".exe"; BAT=".bat"; else EXE=""; BAT=""; fi

# keytool is not always on PATH even where java is (Oracle's javapath shim only
# exports java/javaw/javac), so locate it next to javac.
if command -v keytool >/dev/null 2>&1; then
  KEYTOOL=keytool
elif [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/keytool" ]; then
  KEYTOOL="$JAVA_HOME/bin/keytool"
else
  KEYTOOL="$(ls "$HOME/../../Program Files/Java"/*/bin/keytool.exe 2>/dev/null | sort -V | tail -1)"
  KEYTOOL="${KEYTOOL:-/c/Program Files/Java/jdk-23/bin/keytool.exe}"
fi

# The SDK tools may be native Windows binaries; hand them Windows paths.
w() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else echo "$1"; fi; }

# MSYS rewrites paths passed as arguments but not paths *inside* an @argfile,
# so the file lists javac and d8 read have to be converted explicitly.
wlist() { if command -v cygpath >/dev/null 2>&1; then cygpath -m -f -; else cat; fi; }

OUT="$HERE/build"
KS="$HERE/debug.keystore"
APK="$HERE/watchlauncher.apk"

if [ ! -f "$HERE/assets/oui.db" ]; then
  echo "assets/oui.db is missing -- build it with:"
  echo "    python3 tools/build_oui_db.py"
  exit 1
fi

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/gen"

echo "[1/6] resources + manifest + assets"
# -0 db keeps oui.db stored uncompressed. OuiDb binary-searches it in place
# through the APK's own file descriptor; a deflated entry cannot be seeked and
# would have to be unpacked to /data first.
"$BT/aapt$EXE" package -f -m \
    -M "$(w "$HERE/AndroidManifest.xml")" \
    -S "$(w "$HERE/res")" \
    -A "$(w "$HERE/assets")" \
    -0 db \
    -I "$(w "$AJAR")" \
    -J "$(w "$OUT/gen")" \
    -F "$(w "$OUT/base.apk")"

echo "[2/6] javac"
find "$HERE/src" "$OUT/gen" -name '*.java' | wlist > "$OUT/sources.txt"
javac -nowarn -encoding UTF-8 \
    -source 8 -target 8 \
    -bootclasspath "$(w "$AJAR")" \
    -classpath "$(w "$AJAR")" \
    -d "$(w "$OUT/classes")" \
    @"$OUT/sources.txt" 2>&1 | grep -v "^warning:" || true

# javac's exit status is lost to the pipe above, so check for output instead.
[ -n "$(find "$OUT/classes" -name '*.class' -print -quit)" ] || {
  echo "compile failed" >&2; exit 1; }

echo "[3/6] dex"
find "$OUT/classes" -name '*.class' | wlist > "$OUT/classes.txt"
"$BT/d8$BAT" --release --min-api 19 --lib "$(w "$AJAR")" \
    --output "$(w "$OUT/dex")" @"$OUT/classes.txt"

echo "[4/6] package dex into apk"
( cd "$OUT/dex" && "$BT/aapt$EXE" add -k "$(w "$OUT/base.apk")" classes.dex >/dev/null )

echo "[5/6] zipalign"
# -p page-aligns the uncompressed oui.db, so the positional reads OuiDb makes
# land on page boundaries rather than straddling them.
"$BT/zipalign$EXE" -f -p 4 "$(w "$OUT/base.apk")" "$(w "$OUT/aligned.apk")"

if [ ! -f "$KS" ]; then
  echo "      creating debug keystore"
  "$KEYTOOL" -genkeypair -v -keystore "$(w "$KS")" -storepass android -keypass android \
      -alias androiddebugkey -dname "CN=Android Debug,O=Android,C=US" \
      -keyalg RSA -keysize 2048 -validity 10000 >/dev/null 2>&1
fi

echo "[6/6] sign"
# minSdk 19 needs a v1 (JAR) signature; v2 is harmless alongside it.
"$BT/apksigner$BAT" sign \
    --ks "$(w "$KS")" --ks-pass pass:android --key-pass pass:android \
    --min-sdk-version 19 \
    --v1-signing-enabled true --v2-signing-enabled true \
    --out "$(w "$APK")" "$(w "$OUT/aligned.apk")"

echo
echo "built: $APK"
ls -l "$APK"
echo
echo "install with:  adb install -r \"$APK\""
