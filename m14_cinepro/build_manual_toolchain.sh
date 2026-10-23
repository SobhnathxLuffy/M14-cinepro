#!/usr/bin/env bash
# =============================================================================
# M14 CinePro — manual toolchain build (aapt2 + kotlinc + d8 + zipalign + apksigner)
#
# Mirrors the verified m14_verifier pipeline for environments where Gradle is
# not available. Requires on PATH (or via env vars):
#   ANDROID_BUILD_TOOLS  e.g. /home/<user>/Android/Sdk/build-tools/34.0.0
#   ANDROID_PLATFORM_JAR e.g. /home/<user>/Android/Sdk/platforms/android-34/android.jar
#   kotlinc              Kotlin compiler 1.9.x  (https://github.com/JetBrains/kotlin/releases)
#   adb (optional, for install)
#
# Usage: ./build_manual_toolchain.sh [--no-install]
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP="$SCRIPT_DIR/app"
cd "$SCRIPT_DIR"

BUILD_TOOLS="${ANDROID_BUILD_TOOLS:-/home/sobhnath/Android/Sdk/build-tools/34.0.0}"
PLATFORM="${ANDROID_PLATFORM_JAR:-/home/sobhnath/Android/Sdk/platforms/android-34/android.jar}"
KEYSTORE="$HOME/.android/debug.keystore"
OUT="$SCRIPT_DIR/build_manual"

log() { printf '\n\033[1;36m--- %s ---\033[0m\n' "$*"; }

for tool in "$BUILD_TOOLS/aapt2" "$BUILD_TOOLS/d8" "$BUILD_TOOLS/zipalign" "$BUILD_TOOLS/apksigner" kotlinc; do
  if ! command -v "$tool" >/dev/null 2>&1 && [ ! -x "$tool" ]; then
    echo "ERROR: missing tool: $tool" >&2
    exit 1
  fi
done

log "Cleaning build directory"
rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/obj" "$OUT/apk"

log "Compiling resources with aapt2"
"$BUILD_TOOLS/aapt2" compile --dir "$APP/src/main/res" -o "$OUT/res.zip"

log "Linking resources + generating R"
"$BUILD_TOOLS/aapt2" link -I "$PLATFORM" \
    --manifest "$APP/src/main/AndroidManifest.xml" \
    -o "$OUT/apk/m14_cinepro.unsigned.apk" \
    --java "$OUT/gen" \
    "$OUT/res.zip" --auto-add-overlay

log "Compiling assets into APK (GLSL shaders)"
"$BUILD_TOOLS/aapt2" link -I "$PLATFORM" \
    --manifest "$APP/src/main/AndroidManifest.xml" \
    -o "$OUT/apk/m14_cinepro.unsigned.apk" \
    -A "$APP/src/main/assets" \
    "$OUT/res.zip" --auto-add-overlay

log "Compiling Kotlin sources"
SRC_DIRS=("$APP/src/main/java")
find_args=()
for d in "${SRC_DIRS[@]}"; do find_args+=( "$d" ); done
kotlinc "${find_args[@]}" "$OUT/gen" \
    -classpath "$PLATFORM" \
    -jvm-target 17 \
    -d "$OUT/obj"

log "Converting to DEX with d8"
find "$OUT/obj" -name '*.class' > "$OUT/classlist.txt"
"$BUILD_TOOLS/d8" --output "$OUT/apk" --lib "$PLATFORM" $(cat "$OUT/classlist.txt")

log "Adding DEX + assets to APK"
cd "$OUT/apk"
jar uf m14_cinepro.unsigned.apk classes.dex
cd "$SCRIPT_DIR"
# NOTE: assets were packaged by the second aapt2 link pass (-A flag) above.

log "Aligning and signing APK"
if [ ! -f "$KEYSTORE" ]; then
  mkdir -p "$HOME/.android"
  keytool -genkeypair -keystore "$KEYSTORE" -alias androiddebugkey \
    -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US"
fi
"$BUILD_TOOLS/zipalign" -f -p 4 "$OUT/apk/m14_cinepro.unsigned.apk" "$OUT/apk/m14_cinepro.aligned.apk"
"$BUILD_TOOLS/apksigner" sign \
    --ks "$KEYSTORE" \
    --ks-key-alias androiddebugkey \
    --ks-pass pass:android \
    --key-pass pass:android \
    --out "$OUT/apk/m14_cinepro.apk" \
    "$OUT/apk/m14_cinepro.aligned.apk"

echo "Signed APK: $OUT/apk/m14_cinepro.apk"

if [ "${1:-}" != "--no-install" ]; then
  if command -v adb >/dev/null 2>&1 && adb get-state >/dev/null 2>&1; then
    log "Installing on device"
    adb install -r "$OUT/apk/m14_cinepro.apk"
    adb shell pm grant com.m14.cinepro android.permission.CAMERA || true
    adb shell pm grant com.m14.cinepro android.permission.RECORD_AUDIO || true
    adb logcat -c || true
    adb shell am start -n com.m14.cinepro/.MainActivity
    echo "=== M14 CinePro installed and launched successfully ==="
  else
    echo "=== BUILD OK — no ADB device attached. Install manually: adb install -r $OUT/apk/m14_cinepro.apk ==="
  fi
fi
