#!/bin/bash
set -e

BUILD_TOOLS="/Users/bbr/Library/Android/sdk/build-tools/35.0.0"
PLATFORM="/Users/bbr/Library/Android/sdk/platforms/android-34/android.jar"
PROJECT_DIR="/Users/bbr/AntigravityProjects/TVWebRemote"

cd "$PROJECT_DIR"
rm -rf build && mkdir -p build/gen build/obj build/apk

echo "1. Compiling resources with aapt2..."
"$BUILD_TOOLS/aapt2" compile --dir res -o build/compiled_res.zip

echo "2. Linking resources and generating R.java..."
"$BUILD_TOOLS/aapt2" link \
    -I "$PLATFORM" \
    --manifest AndroidManifest.xml \
    -o build/base.apk \
    --java build/gen \
    --auto-add-overlay \
    build/compiled_res.zip

echo "3. Compiling Java sources with javac..."
javac -d build/obj \
    -cp "$PLATFORM" \
    build/gen/com/bbr/tvwebremote/R.java \
    src/com/bbr/tvwebremote/*.java

echo "4. Converting classes to DEX with d8..."
"$BUILD_TOOLS/d8" \
    --lib "$PLATFORM" \
    --output build/ \
    $(find build/obj -name "*.class")

echo "5. Assembling APK..."
cp build/base.apk build/unaligned.apk
cd build
# Add classes.dex
jar uf unaligned.apk classes.dex
# Add assets/*
mkdir -p assets
cp -r "$PROJECT_DIR/assets/"* assets/
jar uf unaligned.apk assets/*
cd "$PROJECT_DIR"

echo "6. Aligning APK with zipalign..."
"$BUILD_TOOLS/zipalign" -f -p 4 build/unaligned.apk build/aligned.apk

echo "7. Signing APK..."
KEYSTORE="$PROJECT_DIR/debug.keystore"
if [ ! -f "$KEYSTORE" ]; then
    keytool -genkey -v -keystore "$KEYSTORE" -alias debug -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass android -keypass android -dname "CN=Debug,O=Android,C=US"
fi

"$BUILD_TOOLS/apksigner" sign \
    --ks "$KEYSTORE" \
    --ks-key-alias debug \
    --ks-pass pass:android \
    --key-pass pass:android \
    --out build/TVWebRemote.apk \
    build/aligned.apk

echo "BUILD SUCCESSFUL: $PROJECT_DIR/build/TVWebRemote.apk"
ls -lh "$PROJECT_DIR/build/TVWebRemote.apk"
