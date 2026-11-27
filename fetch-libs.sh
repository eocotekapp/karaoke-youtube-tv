#!/bin/bash
# Download the binary libraries build.sh expects in libs/.
# Run from ~/workspace/karaoke-apk (or the repo root):  ./fetch-libs.sh
# Needs internet. The libraries themselves are NOT committed to the repo.
# Layout reproduced: plain jars in libs/, and *-classes.jar extracted from
# each AAR into libs/extracted/ (exactly what build.sh puts on the classpath).
set -e
APP="$(cd "$(dirname "$0")" && pwd)"
cd "$APP"
mkdir -p libs/extracted
GM="https://dl.google.com/dl/android/maven2"
MC="https://repo1.maven.org/maven2"

dl() { # dl <url> <dest>
  echo "== $2"
  curl -fL --retry 2 -o "$2" "$1"
}

# --- ExoPlayer 2.19.1 AARs (Google Maven) ---
for mod in core common container dash database datasource decoder extractor hls; do
  aar="exoplayer-$mod-2.19.1.aar"
  dl "$GM/com/google/android/exoplayer/exoplayer-$mod/2.19.1/$aar" "libs/$aar"
  mkdir -p "libs/extracted/exoplayer-$mod-2.19.1"
  unzip -o -q "libs/$aar" classes.jar -d "libs/extracted/exoplayer-$mod-2.19.1"
  mv "libs/extracted/exoplayer-$mod-2.19.1/classes.jar" "libs/extracted/exoplayer-$mod-2.19.1-classes.jar"
done

# --- AndroidX core 1.8.0 AAR (Google Maven) ---
dl "$GM/androidx/core/core/1.8.0/core-1.8.0.aar" "libs/core-1.8.0.aar"
mkdir -p libs/extracted/core-1.8.0
unzip -o -q libs/core-1.8.0.aar classes.jar -d libs/extracted/core-1.8.0
mv libs/extracted/core-1.8.0/classes.jar libs/extracted/core-1.8.0-classes.jar

# --- Plain jars (Maven Central) ---
dl "$MC/androidx/annotation/annotation/1.3.0/annotation-1.3.0.jar" libs/annotation-1.3.0.jar
dl "$MC/androidx/collection/collection/1.2.0/collection-1.2.0.jar" libs/collection-1.2.0.jar
dl "$MC/com/google/guava/guava/31.1-android/guava-31.1-android.jar" libs/guava-31.1-android.jar
dl "$MC/com/github/mwiede/jsch/0.2.9/jsch-0.2.9.jar" libs/jsch-0.2.9.jar
# ZXing is only used by the JVM QR round-trip test, not by the APK build.
dl "$MC/com/google/zxing/core/3.5.2/core-3.5.2.jar" libs/zxing-core-3.5.2.jar

echo "=== done: libs/ ready, run ./build.sh ==="
ls -la libs/ libs/extracted/ | head -40
