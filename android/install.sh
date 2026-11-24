#!/usr/bin/env bash
# Vidara (Android) - build the APK and optionally install it on the connected phone.
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
export ANDROID_HOME="$SDK"

if [ -x ./gradlew ]; then GRADLE=./gradlew; else GRADLE=gradle; fi

"$GRADLE" :app:assembleDebug

APK="app/build/outputs/apk/debug/app-debug.apk"
echo "APK: $APK"

ADB="$SDK/platform-tools/adb"
[ -x "$ADB" ] || ADB=adb

if [ "${1:-}" = "--install" ]; then
  if ! "$ADB" devices | grep -q "device$"; then
    echo "No connected phone (adb devices)." >&2
    exit 1
  fi
  "$ADB" install -r "$APK"
  "$ADB" shell monkey -p com.rik.vidaraglass -c android.intent.category.LAUNCHER 1 >/dev/null
  echo "Installed and launched."
fi
