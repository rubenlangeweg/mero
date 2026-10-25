#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
variant="${1:-home}"
case "$variant" in
    home) build=launcher/build ;;
    lab) build=launcher/build-experiment ;;
    preview) build=launcher/build-preview ;;
    *) echo "Usage: bash tools/test-protocol.sh [home|lab|preview]" >&2; exit 2 ;;
esac
: "${JAVA_HOME:?Set JAVA_HOME to a JDK 17 or newer}"
ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK directory}"
android_jar="$ANDROID_HOME/platforms/android-35/android.jar"
[[ -f "$build/classes/studio/invrse/mero/ArtworkServer.class" ]] || {
    echo "Build the $variant variant before running its protocol checks." >&2; exit 1;
}
mkdir -p launcher/build/test-classes
"$JAVA_HOME/bin/javac" --release 8 -cp "$android_jar:$build/classes" \
    -d launcher/build/test-classes tests/ArtworkServerProtocolTest.java
"$JAVA_HOME/bin/java" -cp "$android_jar:$build/classes:launcher/build/test-classes" \
    studio.invrse.mero.ArtworkServerProtocolTest
