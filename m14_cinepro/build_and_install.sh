#!/usr/bin/env bash
# =============================================================================
# M14 CinePro — build, sign, install (primary Gradle pipeline)
#
# Target: Samsung Galaxy M14 5G (SM-M146B / Exynos 1330 / Mali-G68 MP2)
# Usage:  ./build_and_install.sh [--no-install] [--release]
#
# Self-contained bootstrap:
#   1. Verifies JDK 17+ (Gradle 8.7 + AGP 8.4.1 requirement).
#   2. Verifies ANDROID_HOME / ANDROID_SDK_ROOT; if missing, downloads Android
#      cmdline-tools, installs platforms;android-34 + build-tools;34.0.0 +
#      platform-tools, and accepts licenses.
#   3. Uses the committed Gradle wrapper (gradle-8.7); if the wrapper jar is
#      missing, downloads the Gradle 8.7 distribution directly.
#   4. assembles the debug APK, zipaligns + apksigner-signs with the standard
#      Android debug keystore (auto-generated via keytool if absent).
#   5. adb install -r + grants CAMERA/RECORD_AUDIO + launches the app when a
#      device is attached; otherwise prints the APK path for manual install.
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

DO_INSTALL=1
BUILD_VARIANT="debug"
for arg in "$@"; do
  case "$arg" in
    --no-install) DO_INSTALL=0 ;;
    --release) BUILD_VARIANT="release" ;;
  esac
done

log() { printf '\n\033[1;36m--- %s ---\033[0m\n' "$*"; }

# ---------------------------------------------------------------------------
# 1. JDK check
# ---------------------------------------------------------------------------
log "Checking JDK"
if ! command -v java >/dev/null 2>&1; then
  echo "ERROR: Java not found. Install JDK 17+ (e.g. sudo apt install openjdk-17-jdk)." >&2
  exit 1
fi
JAVA_VER="$(java -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+)\..*/\1/')"
if [ "$JAVA_VER" -lt 17 ] 2>/dev/null; then
  echo "ERROR: JDK 17+ required, found: $(java -version 2>&1 | head -1)" >&2
  exit 1
fi
echo "Java OK: $(java -version 2>&1 | head -1)"

# ---------------------------------------------------------------------------
# 2. Android SDK check / bootstrap
# ---------------------------------------------------------------------------
log "Checking Android SDK"
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
if [ ! -d "$SDK_ROOT/platforms/android-34" ] || [ ! -d "$SDK_ROOT/build-tools/34.0.0" ]; then
  echo "Android SDK (platform 34 / build-tools 34.0.0) not found at $SDK_ROOT — bootstrapping."
  mkdir -p "$SDK_ROOT/cmdline-tools"
  ZIP="$SDK_ROOT/cmdline-tools.zip"
  curl -fSL -o "$ZIP" https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  unzip -oq "$ZIP" -d "$SDK_ROOT/cmdline-tools"
  rm -f "$ZIP"
  [ -d "$SDK_ROOT/cmdline-tools/cmdline-tools" ] && \
    mv "$SDK_ROOT/cmdline-tools/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"
  SDKM="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
  yes | "$SDKM" --sdk_root="$SDK_ROOT" --licenses >/dev/null || true
  "$SDKM" --sdk_root="$SDK_ROOT" "platforms;android-34" "build-tools;34.0.0" "platform-tools"
fi
export ANDROID_HOME="$SDK_ROOT"
export ANDROID_SDK_ROOT="$SDK_ROOT"
echo "Android SDK OK: $SDK_ROOT"

# ---------------------------------------------------------------------------
# 3. Gradle (wrapper preferred)
# ---------------------------------------------------------------------------
log "Checking Gradle"
if [ ! -f gradle/wrapper/gradle-wrapper.jar ]; then
  echo "Gradle wrapper jar missing — downloading Gradle 8.7 distribution."
  mkdir -p .gradle-dist
  curl -fSL -o /tmp/gradle-8.7-bin.zip https://services.gradle.org/distributions/gradle-8.7-bin.zip
  unzip -oq /tmp/gradle-8.7-bin.zip -d .gradle-dist
  rm -f /tmp/gradle-8.7-bin.zip
  GRADLE_CMD="$(pwd)/.gradle-dist/gradle-8.7/bin/gradle"
else
  GRADLE_CMD="./gradlew"
fi
echo "Gradle command: $GRADLE_CMD"

# ---------------------------------------------------------------------------
# 4. Debug keystore
# ---------------------------------------------------------------------------
log "Checking debug keystore"
KEYSTORE="$HOME/.android/debug.keystore"
if [ ! -f "$KEYSTORE" ]; then
  mkdir -p "$HOME/.android"
  keytool -genkeypair -keystore "$KEYSTORE" -alias androiddebugkey \
    -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US"
fi
echo "Keystore OK: $KEYSTORE"

# ---------------------------------------------------------------------------
# 5. Build
# ---------------------------------------------------------------------------
log "Building APK (assemble$BUILD_VARIANT)"
"$GRADLE_CMD" --no-daemon "assemble$BUILD_VARIANT"

APK="app/build/outputs/apk/$BUILD_VARIANT/app-$BUILD_VARIANT.apk"
if [ ! -f "$APK" ]; then
  echo "ERROR: expected APK not found at $APK" >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# 6. Align + sign
# ---------------------------------------------------------------------------
log "Aligning and signing"
BT="$SDK_ROOT/build-tools/34.0.0"
SIGNED="app/build/outputs/apk/$BUILD_VARIANT/m14_cinepro-signed.apk"
"$BT/zipalign" -f -p 4 "$APK" "$APK.aligned"
"$BT/apksigner" sign \
  --ks "$KEYSTORE" \
  --ks-key-alias androiddebugkey \
  --ks-pass pass:android \
  --key-pass pass:android \
  --out "$SIGNED" \
  "$APK.aligned"
"$BT/apksigner" verify "$SIGNED"
echo "Signed APK: $SIGNED"

# ---------------------------------------------------------------------------
# 7. Install + launch (skipped when no device is attached)
# ---------------------------------------------------------------------------
if [ "$DO_INSTALL" -eq 1 ]; then
  if ! command -v adb >/dev/null 2>&1; then
    export PATH="$PATH:$SDK_ROOT/platform-tools"
  fi
  if command -v adb >/dev/null 2>&1 && adb get-state >/dev/null 2>&1; then
    log "Installing on device"
    adb install -r "$SIGNED"
    adb shell pm grant com.m14.cinepro android.permission.CAMERA || true
    adb shell pm grant com.m14.cinepro android.permission.RECORD_AUDIO || true
    adb logcat -c || true
    adb shell am start -n com.m14.cinepro/.MainActivity
    echo "=== M14 CinePro installed and launched successfully ==="
  else
    log "No ADB device attached"
    echo "=== BUILD OK — sideload manually: adb install -r $SIGNED ==="
  fi
else
  echo "=== BUILD OK (--no-install): $SIGNED ==="
fi
