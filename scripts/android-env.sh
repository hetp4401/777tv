#!/bin/sh
# Source this file from the project root before using Gradle or SDK tools.
fleet_android_tools=${FLEET_TOOLS_DIR:-$PWD/.tools}
if [ -d "$fleet_android_tools/jdk/Contents/Home" ]; then
    export JAVA_HOME="$fleet_android_tools/jdk/Contents/Home"
else
    export JAVA_HOME="$fleet_android_tools/jdk"
fi
if [ "$(uname -s)" = Darwin ]; then
    export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
else
    export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
fi
export PATH="$JAVA_HOME/bin:$fleet_android_tools/gradle/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
unset fleet_android_tools
