#!/bin/bash
# VisionTest MCP Server Launcher
# This script handles environment setup automatically

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INSTALL_DIR="${VISIONTEST_DIR:-$HOME/.local/share/visiontest}"
INSTALLED_JAR="$INSTALL_DIR/visiontest.jar"
REPO_JAR="$SCRIPT_DIR/app/build/libs/visiontest.jar"
APK_PATH="$SCRIPT_DIR/automation-server/build/outputs/apk/androidTest/debug/automation-server-debug-androidTest.apk"

# Use repo build if available, otherwise fall back to installed JAR
if [ -f "$REPO_JAR" ]; then
    JAR_PATH="$REPO_JAR"
elif [ -f "$INSTALLED_JAR" ]; then
    JAR_PATH="$INSTALLED_JAR"
else
    echo "Error: visiontest.jar not found." >&2
    echo "  Build with: ./gradlew shadowJar" >&2
    echo "  Or install from a tagged release: see https://github.com/docer1990/visiontest/releases" >&2
    exit 1
fi

# Find Java
if [ -n "$JAVA_HOME" ]; then
    JAVA_CMD="$JAVA_HOME/bin/java"
elif command -v java &> /dev/null; then
    JAVA_CMD="java"
else
    echo "Error: Java not found. Set JAVA_HOME or add java to PATH" >&2
    exit 1
fi

# Only set up Android SDK and project-relative paths when running from the repo
if [ "$JAR_PATH" = "$REPO_JAR" ]; then
    # Find Android SDK
    if [ -z "$ANDROID_HOME" ]; then
        # Try common locations
        if [ -d "$HOME/Library/Android/sdk" ]; then
            export ANDROID_HOME="$HOME/Library/Android/sdk"
        elif [ -d "$HOME/Android/Sdk" ]; then
            export ANDROID_HOME="$HOME/Android/Sdk"
        else
            echo "Error: ANDROID_HOME not set and Android SDK not found in common locations" >&2
            exit 1
        fi
    fi

    # Add platform-tools to PATH for ADB
    export PATH="$ANDROID_HOME/platform-tools:$PATH"

    # Set APK path
    export VISION_TEST_APK_PATH="$APK_PATH"

    # Change to project root so relative paths (Xcode project, APKs) resolve correctly
    # This is needed when MCP clients launch the script from a different working directory
    cd "$SCRIPT_DIR"
fi

# Run the server
exec "$JAVA_CMD" -jar "$JAR_PATH" "$@"
