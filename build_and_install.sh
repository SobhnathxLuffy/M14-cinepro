#!/usr/bin/env bash
set -e

BUILD_TOOLS="/home/sobhnath/Android/Sdk/build-tools/35.0.0"
PLATFORM="/home/sobhnath/Android/Sdk/platforms/android-36/android.jar"
KEYSTORE="$HOME/.android/debug.keystore"

cd m14_verifier

echo "--- Cleaning build directory ---"
rm -rf build
mkdir -p build/gen build/obj build/apk

echo "--- Compiling resources with aapt2 ---"
$BUILD_TOOLS/aapt2 compile --dir res -o build/res.zip 2>/dev/null || true

if [ -f build/res.zip ]; then
    $BUILD_TOOLS/aapt2 link -I $PLATFORM \
        --manifest AndroidManifest.xml \
        -o build/apk/m14_verifier.unsigned.apk \
        --java build/gen \
        build/res.zip --auto-add-overlay
else
    $BUILD_TOOLS/aapt2 link -I $PLATFORM \
        --manifest AndroidManifest.xml \
        -o build/apk/m14_verifier.unsigned.apk \
        --java build/gen \
        --auto-add-overlay
fi

echo "--- Compiling Java source files ---"
javac --release 17 -d build/obj \
    -cp $PLATFORM \
    src/com/cinema/verifier/MainActivity.java

echo "--- Converting to DEX with d8 ---"
$BUILD_TOOLS/d8 --output build/apk/ \
    --lib $PLATFORM \
    build/obj/com/cinema/verifier/*.class

echo "--- Adding DEX to APK ---"
cd build/apk
jar uf m14_verifier.unsigned.apk classes.dex
cd ../..

echo "--- Aligning and Signing APK ---"
$BUILD_TOOLS/zipalign -f -p 4 build/apk/m14_verifier.unsigned.apk build/apk/m14_verifier.aligned.apk

$BUILD_TOOLS/apksigner sign \
    --ks $KEYSTORE \
    --ks-key-alias androiddebugkey \
    --ks-pass pass:android \
    --key-pass pass:android \
    --out build/apk/m14_verifier.apk \
    build/apk/m14_verifier.aligned.apk

echo "--- Installing on device ---"
adb install -r build/apk/m14_verifier.apk

echo "--- Granting permissions ---"
adb shell pm grant com.cinema.verifier android.permission.CAMERA || true
adb shell pm grant com.cinema.verifier android.permission.RECORD_AUDIO || true

echo "--- Clearing logcat buffer ---"
adb logcat -c

echo "--- Starting MainActivity ---"
adb shell am start -n com.cinema.verifier/.MainActivity

echo "--- Application launched successfully ---"
