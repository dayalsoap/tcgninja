#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if [[ -d /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ]]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
fi
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
adb_bin="${ANDROID_HOME}/platform-tools/adb"
if ! "$adb_bin" devices | awk 'NR>1 && $2=="device" {found=1} END {exit !found}'; then
  echo "No authorized Android device connected. Connect a phone with USB debugging enabled."
  exit 1
fi
./gradlew installDebug
