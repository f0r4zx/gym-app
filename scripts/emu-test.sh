#!/usr/bin/env bash
# Runs the instrumented tests on the emulator and keeps the crash log if they fail.
set +e
./gradlew :app:connectedDebugAndroidTest --no-daemon --console=plain 2>&1 | tee emu.log
status=${PIPESTATUS[0]}
adb logcat -d -t 800 AndroidRuntime:E 'GymRepository:E' 'MapLibre:E' 'Mbgl:E' 'SMOKE:*' '*:S' > logcat.txt 2>&1
exit $status
