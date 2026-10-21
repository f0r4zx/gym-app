#!/usr/bin/env bash
# Installs the optimized (R8-shrunk) release APK on the emulator and checks that it starts and
# shows the welcome screen. Shrinking can break apps in ways the debug build never shows.
set +e
./gradlew :app:assembleRelease --no-daemon --console=plain 2>&1 | tail -8
APK=$(ls app/build/outputs/apk/release/*.apk | head -1)
echo "APK: $APK"
adb install -r "$APK" || { echo "INSTALL FAILED"; exit 1; }
adb logcat -c
adb shell am start -W -n app.gymquest/.MainActivity
sleep 12
adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
adb pull /sdcard/ui.xml ui.xml > /dev/null 2>&1
adb logcat -d -t 800 AndroidRuntime:E 'GymRepository:E' '*:S' > release-crash.txt 2>&1
if grep -q "FATAL" release-crash.txt; then
  echo "===== THE RELEASE APP CRASHED ====="
  head -60 release-crash.txt
  exit 1
fi
if ! grep -q "Bienvenido a GymQuest" ui.xml; then
  echo "===== WELCOME SCREEN NOT FOUND ====="
  head -c 3000 ui.xml
  exit 1
fi
echo "Release app started and showed the welcome screen."
