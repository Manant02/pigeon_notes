#!/bin/bash
# Setup script for the Claude Code cloud environment (paste into the environment's "Setup script"
# field at claude.ai/code). Installs the Android SDK the build needs; the environment must allow
# dl.google.com (Custom network access, defaults + dl.google.com) and set
# ANDROID_HOME=/opt/android-sdk as an environment variable.
set -euo pipefail

SDK=${ANDROID_HOME:-/opt/android-sdk}
TOOLS_ZIP=commandlinetools-linux-16111833_latest.zip # cmdline-tools 23.0

if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  mkdir -p "$SDK/cmdline-tools"
  curl -fsSL "https://dl.google.com/android/repository/$TOOLS_ZIP" -o /tmp/cmdline-tools.zip
  unzip -q /tmp/cmdline-tools.zip -d /tmp/cmdline-tools
  mv /tmp/cmdline-tools/cmdline-tools "$SDK/cmdline-tools/latest"
  rm -rf /tmp/cmdline-tools /tmp/cmdline-tools.zip
fi

yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" --licenses > /dev/null || true
"$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" \
  "platforms;android-37.2" "build-tools;37.0.0" "platform-tools" > /dev/null
