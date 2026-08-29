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

# BouncyCastle's pure-Java TLS, because this device's own stack has no AES-GCM
# and cannot be given any: the cipher suites are absent from the system image,
# not merely disabled. Without these the app can only reach a server willing to
# speak 2013-era ciphers.
# Classpath separator: javac on Windows splits on ";" -- a ":" there is part of the
# drive letter, so a Unix-joined classpath silently resolves to nothing and the
# bouncycastle jars are quietly not found. That fails only Tls12SocketFactory, which
# the build then carries on without, shipping an apk with no TLS 1.2.
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) CPSEP=";" ;;
  *)                    CPSEP=":" ;;
esac
JARS=""
if [ -d "$HERE/libs" ]; then
  for j in "$HERE"/libs/*.jar; do
    [ -f "$j" ] || continue
    JARS="$JARS$(w "$j")$CPSEP"
  done
fi

echo "[2/6] javac"
find "$HERE/src" "$OUT/gen" -name '*.java' | wlist > "$OUT/sources.txt"
javac -nowarn -encoding UTF-8 \
    -source 8 -target 8 \
    -bootclasspath "$(w "$AJAR")" \
    -classpath "$JARS$(w "$AJAR")" \
    -d "$(w "$OUT/classes")" \
    @"$OUT/sources.txt" 2>&1 | grep -v "^warning:"

# javac's status, not grep's. This used to test whether any .class file existed, which is
# always true: the previous build's classes are still in the directory. So a compile that
# failed outright was dexed from stale classes and reported success, and the apk installed
# without the change it was built for. Four errors in SleepService.java went out that way
# before this was noticed, on a build that printed "built:" and a size like any other.
rc=${PIPESTATUS[0]}
[ "$rc" -eq 0 ] || { echo "compile failed" >&2; exit 1; }

echo "[3/6] dex"
find "$OUT/classes" -name '*.class' | wlist > "$OUT/classes.txt"
# The jars go in alongside our own classes. All of BouncyCastle is about
# 37,000 methods and the app a couple of thousand, so this still fits one dex
# and needs no multidex support library - which pre-API-21 would otherwise
# require.
# shellcheck disable=SC2086
"$BT/d8$BAT" --release --min-api 19 --lib "$(w "$AJAR")" \
    --output "$(w "$OUT/dex")" @"$OUT/classes.txt" \
    $(ls "$HERE"/libs/*.jar 2>/dev/null | while read -r j; do w "$j"; done | tr '\n' ' ')

echo "[4/6] package dex into apk"
( cd "$OUT/dex" && "$BT/aapt$EXE" add -k "$(w "$OUT/base.apk")" classes.dex >/dev/null )

# Native libraries, if any have been built. aapt add stores by the path given, and the runtime
# looks under lib/<abi>/, so the add has to run from a directory where that is the relative
# path -- hence the staging copy rather than adding straight out of libs/.
if ls "$HERE"/libs/armeabi-v7a/*.so >/dev/null 2>&1; then
  echo "[4b/6] native libs"
  rm -rf "$OUT/nativelib"
  mkdir -p "$OUT/nativelib/lib/armeabi-v7a"
  cp "$HERE"/libs/armeabi-v7a/*.so "$OUT/nativelib/lib/armeabi-v7a/"
  for so in "$OUT/nativelib/lib/armeabi-v7a"/*.so; do
    echo "      lib/armeabi-v7a/$(basename "$so")"
  done
  ( cd "$OUT/nativelib" && "$BT/aapt$EXE" add -k "$(w "$OUT/base.apk")" \
        lib/armeabi-v7a/*.so >/dev/null )
fi

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

# The route line, checked against real routes before anything ships.
#
# RouteLine touches no Android class precisely so this can run: every crash
# this app has had was libhwui being handed more than it could take, so what
# is worth proving on every build is that the path stays small however long
# the route and wherever the map is centred.
if [ -d "$HERE/test" ] && ls "$HERE"/test/*.bin >/dev/null 2>&1; then
    TD=$(mktemp -d)
    javac -nowarn -d "$TD" "$HERE/src/org/watchlauncher/RouteLine.java" \
        "$HERE/src/org/watchlauncher/Mercator.java" 2>/dev/null
    javac -nowarn -cp "$TD" -d "$TD" "$HERE/test/RouteLineTest.java" 2>/dev/null
    if ! java -cp "$TD" RouteLineTest "$HERE"/test/*.bin > "$TD/out"; then
        echo "route line test FAILED:" >&2
        cat "$TD/out" >&2
        rm -rf "$TD"
        exit 1
    fi
    tail -3 "$TD/out"
    rm -rf "$TD"
fi

# The rest-activity maths, against days whose answer is known by
# construction. Circadian touches no Android class for this reason.
TD2=$(mktemp -d)
if javac -nowarn -d "$TD2" "$HERE/src/org/watchlauncher/Circadian.java" 2>/dev/null \
   && javac -nowarn -cp "$TD2" -d "$TD2" "$HERE/test/CircadianTest.java" 2>/dev/null; then
    if ! java -cp "$TD2" CircadianTest > "$TD2/out"; then
        echo "circadian test FAILED:" >&2
        cat "$TD2/out" >&2
        rm -rf "$TD2"
        exit 1
    fi
    tail -1 "$TD2/out"
fi
rm -rf "$TD2"

# Speed and time to arrival. Drive touches no Android class so that the cases
# that actually bite - stopped at a light, a fix that teleports, the watch
# waking an hour later - can be driven here rather than discovered on a road.
TD3=$(mktemp -d)
if javac -nowarn -d "$TD3" "$HERE/src/org/watchlauncher/Drive.java" \
        "$HERE/src/org/watchlauncher/Geo.java" 2>/dev/null \
   && javac -nowarn -cp "$TD3" -d "$TD3" "$HERE/test/DriveTest.java" 2>/dev/null; then
    if ! java -cp "$TD3" DriveTest > "$TD3/out"; then
        echo "drive test FAILED:" >&2
        cat "$TD3/out" >&2
        rm -rf "$TD3"
        exit 1
    fi
    tail -1 "$TD3/out"
fi
rm -rf "$TD3"

# The tracker wire format, against frames captured from the live server. The fields are
# positional with no separators and nobody documented them, so this compares byte for byte
# against something the server actually sent - a position in decimal degrees instead of
# degrees-and-minutes is still a valid-looking fix, just a few hundred kilometres away.
TD4=$(mktemp -d)
if javac -nowarn -d "$TD4" "$HERE/src/org/watchlauncher/BeehomeCodec.java" 2>/dev/null && javac -nowarn -cp "$TD4" -d "$TD4" "$HERE/test/BeehomeCodecTest.java" 2>/dev/null; then
    if ! java -cp "$TD4" BeehomeCodecTest > "$TD4/out"; then
        echo "beehome codec test FAILED:" >&2
        cat "$TD4/out" >&2
        rm -rf "$TD4"
        exit 1
    fi
    tail -1 "$TD4/out"
fi
rm -rf "$TD4"

# The alarm parser reads a format nobody has captured, so what is actually being
# checked is that it refuses what it does not understand rather than inventing a
# time -- a watch that rings at the wrong hour is worse than one that does not.
TD_ALARM=$(mktemp -d)
if javac -nowarn -d "$TD_ALARM" "$HERE/src/org/watchlauncher/AlarmParse.java" 2>/dev/null && javac -nowarn -cp "$TD_ALARM" -d "$TD_ALARM" "$HERE/test/AlarmParseTest.java" 2>/dev/null; then
    if ! java -cp "$TD_ALARM" AlarmParseTest > "$TD_ALARM/out"; then
        echo "alarm parse test FAILED:" >&2
        cat "$TD_ALARM/out" >&2
        rm -rf "$TD_ALARM"
        exit 1
    fi
    tail -1 "$TD_ALARM/out"
fi
rm -rf "$TD_ALARM"

# BP28 arrives a packet at a time and its payload is hex, so the failure that
# matters is a gap or a truncation being written out as an audio file anyway.
TD_VOICE=$(mktemp -d)
if javac -nowarn -d "$TD_VOICE" "$HERE/src/org/watchlauncher/VoiceAssembler.java" 2>/dev/null && javac -nowarn -cp "$TD_VOICE" -d "$TD_VOICE" "$HERE/test/VoiceAssemblerTest.java" 2>/dev/null; then
    if ! java -cp "$TD_VOICE" VoiceAssemblerTest > "$TD_VOICE/out"; then
        echo "voice assembler test FAILED:" >&2
        cat "$TD_VOICE/out" >&2
        rm -rf "$TD_VOICE"
        exit 1
    fi
    tail -1 "$TD_VOICE/out"
fi
rm -rf "$TD_VOICE"

# Where you are on a route and how much of it is left. The fast version is not
# obviously the same as the slow one - the search starts where it finished
# last time and looks at a window around that - so it is checked against a
# full scan of every segment, over a route that doubles back near itself.
TD4=$(mktemp -d)
if javac -nowarn -d "$TD4" "$HERE/src/org/watchlauncher/Route.java" \
        "$HERE/src/org/watchlauncher/Geo.java" \
        "$HERE/test/stub/org/watchlauncher/RoadGraph.java" 2>/dev/null \
   && javac -nowarn -cp "$TD4" -d "$TD4" "$HERE/test/RouteGeomTest.java" 2>/dev/null; then
    if ! java -cp "$TD4" RouteGeomTest > "$TD4/out"; then
        echo "route geometry test FAILED:" >&2
        cat "$TD4/out" >&2
        rm -rf "$TD4"
        exit 1
    fi
    tail -2 "$TD4/out"
fi
rm -rf "$TD4"

# When a turn is announced, at the speeds this watch is used at. Route needs a
# stub RoadGraph to compile off the device; see test/stub.
TD5=$(mktemp -d)
if javac -nowarn -d "$TD5" "$HERE/src/org/watchlauncher/Route.java" \
        "$HERE/src/org/watchlauncher/Geo.java" \
        "$HERE/test/stub/org/watchlauncher/RoadGraph.java" 2>/dev/null \
   && javac -nowarn -cp "$TD5" -d "$TD5" "$HERE/test/TurnTimingTest.java" 2>/dev/null; then
    if ! java -cp "$TD5" TurnTimingTest > "$TD5/out"; then
        echo "turn timing test FAILED:" >&2
        cat "$TD5/out" >&2
        rm -rf "$TD5"
        exit 1
    fi
    tail -1 "$TD5/out"
fi
rm -rf "$TD5"

echo "built: $APK"
ls -l "$APK"
echo
echo "install with:  adb install -r \"$APK\""
