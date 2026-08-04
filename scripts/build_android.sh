#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "🔨 Building Android Client..."
cd "$ROOT_DIR/AndroidClient"

# Find a JDK 17+: an explicit JAVA_HOME wins, then Android Studio's bundled JDK,
# then Homebrew's openjdk@17. AGP 8.x rejects the Java 8 that macOS ships.
if [ -z "$JAVA_HOME" ] || [ ! -d "$JAVA_HOME" ]; then
    for candidate in \
        "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
        "/opt/homebrew/opt/openjdk@17" \
        "/usr/local/opt/openjdk@17"
    do
        if [ -d "$candidate" ]; then
            export JAVA_HOME="$candidate"
            break
        fi
    done
fi

if [ -z "$JAVA_HOME" ] || [ ! -d "$JAVA_HOME" ]; then
    echo "❌ No JDK 17+ found."
    echo "   Install one with:  brew install openjdk@17"
    echo "   Or set JAVA_HOME manually."
    exit 1
fi
echo "   Using JAVA_HOME=$JAVA_HOME"

# The Android SDK location. Homebrew's command-line tools cask installs here;
# local.properties (gitignored) may already point at it.
if [ -z "$ANDROID_HOME" ] && [ -d "/opt/homebrew/share/android-commandlinetools" ]; then
    export ANDROID_HOME="/opt/homebrew/share/android-commandlinetools"
fi

./gradlew assembleDebug

echo ""
echo "✅ Build successful!"
echo ""
echo "📦 APK: $ROOT_DIR/AndroidClient/app/build/outputs/apk/debug/app-debug.apk"
echo ""
echo "To install on device:"
echo "  adb install -r $ROOT_DIR/AndroidClient/app/build/outputs/apk/debug/app-debug.apk"
echo ""
echo "Or run: ./scripts/install_android.sh"
