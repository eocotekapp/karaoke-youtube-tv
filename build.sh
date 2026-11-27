#!/bin/bash
# Build Karaoke YouTube standalone (Pinggy ONLY) — manual, no Gradle.
# Run from ~/workspace/karaoke-apk :  ./build.sh  -> KaraokeYouTube.apk
# minSdk 21, targetSdk 30. ExoPlayer 2.19.1 + JSch + ZXing from libs/.
set -e
APP="$(cd "$(dirname "$0")" && pwd)"
SDK="$HOME/workspace/tsboot-app/sdk"
BT="$SDK/build-tools/34.0.0/android-14"
AJAR="$SDK/platforms/android-30/android-11/android.jar"
JAVA_BIN="$(command -v javac | xargs dirname)"
[ -x "$JAVA_BIN/javac" ] || JAVA_BIN=/usr/lib/jvm/java-17-openjdk-amd64/bin
export PATH="$JAVA_BIN:$PATH"
cd "$APP"

DEPS=""
for j in libs/extracted/*-classes.jar libs/jsch-0.1.55.jar libs/guava-31.1-android.jar libs/annotation-1.3.0.jar libs/collection-1.2.0.jar; do
  DEPS="$DEPS:$j"
done
DEPS="${DEPS#:}"

echo "=== 1. aapt2 compile res ==="
rm -rf build && mkdir -p build/classes build/dex build/gen
"$BT/aapt2" compile --dir res -o build/compiled.zip

echo "=== 2. aapt2 link ==="
"$BT/aapt2" link -o build/app-unsigned.apk -I "$AJAR" --auto-add-overlay \
    --manifest AndroidManifest.xml -R build/compiled.zip --java build/gen

echo "=== 3. javac (Java 8, android.jar) ==="
"$JAVA_BIN/javac" -encoding UTF-8 -source 8 -target 8 -bootclasspath "$AJAR" \
    -cp "$DEPS" -sourcepath build/gen -d build/classes \
    $(find src -name '*.java') build/gen/vn/karaoke/youtube/R.java

echo "=== 4. d8 -> classes.dex (min-api 21) ==="
(cd build/classes && "$JAVA_BIN/jar" cf ../app.jar .)
"$BT/d8" --lib "$AJAR" --min-api 21 --output build/dex build/app.jar $(echo "$DEPS" | tr ':' ' ')
ls -la build/dex/

echo "=== 5. them dex vao apk ==="
(cd build/dex && "$JAVA_BIN/jar" uf ../app-unsigned.apk classes.dex)

echo "=== 6. zipalign ==="
"$BT/zipalign" -f 4 build/app-unsigned.apk build/app-aligned.apk

echo "=== 7. ky (keystore moi cua project) ==="
if [ ! -f keystore.jks ]; then
    "$JAVA_BIN/keytool" -genkeypair -keystore keystore.jks -alias karaoke \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass karaoke123 -keypass karaoke123 \
        -dname "CN=Karaoke YouTube, O=Mesu, C=VN" 2>/dev/null
fi
"$BT/apksigner" sign --ks keystore.jks --ks-pass pass:karaoke123 \
    --key-pass pass:karaoke123 --out KaraokeYouTube.apk build/app-aligned.apk
"$BT/apksigner" verify KaraokeYouTube.apk && echo "VERIFY OK"

echo "=== xong: $APP/KaraokeYouTube.apk ($(stat -c %s KaraokeYouTube.apk) bytes) ==="
"$BT/aapt2" dump badging KaraokeYouTube.apk | grep -E "package:|sdkVersion|targetSdkVersion|uses-permission|application-label|launchable-activity"
