#!/usr/bin/env bash
# Throwaway sibling of run_android_runtime.sh: same AOSP API 36 + SwiftShader profile.
set -euo pipefail
api=${PROTOTYPE_API:-36}
evidence="runtime/statusbar-api$api"
mkdir -p "$evidence"
export ANDROID_SERIAL=emulator-5554
export ANDROID_AVD_HOME="$RUNNER_TEMP/prototype-statusbar-avd-$api"
mkdir -p "$ANDROID_AVD_HOME"
pid=
cleanup() {
  status=$?
  trap - EXIT
  set +e
  timeout 30s adb logcat -b all -d -v threadtime > "$evidence/emulator-log.txt" 2>&1
  timeout 20s adb shell dumpsys window > "$evidence/emulator-window.txt" 2>&1
  if [[ -n "$pid" ]]; then kill "$pid"; wait "$pid"; fi
  exit "$status"
}
trap cleanup EXIT
sdkmanager --install emulator "system-images;android-$api;default;x86_64" > "$evidence/sdk-install.txt" 2>&1
avdmanager create avd --name prototype-statusbar --package "system-images;android-$api;default;x86_64" \
  --device pixel_7_pro <<< no > "$evidence/avd-create.txt" 2>&1
python3 - "$ANDROID_AVD_HOME/prototype-statusbar.avd/config.ini" <<'PY'
from pathlib import Path
import re
import sys
path = Path(sys.argv[1])
text = path.read_text()
for key, value in {"hw.lcd.width": "720", "hw.lcd.height": "1560", "hw.lcd.density": "280"}.items():
    text = re.sub(rf"(?m)^{re.escape(key)}=.*$", f"{key}={value}", text)
path.write_text(text)
PY
cp "$ANDROID_AVD_HOME/prototype-statusbar.avd/config.ini" "$evidence/avd-config.ini"
EGL_PLATFORM=x11 "$ANDROID_HOME/emulator/emulator" -avd prototype-statusbar -port 5554 -cores 4 -memory 4096 \
  -no-window -gpu swiftshader -no-snapshot -noaudio -no-boot-anim -camera-back none -camera-front none \
  > "$evidence/emulator-boot.txt" 2>&1 &
pid=$!
timeout 120s adb wait-for-device
deadline=$((SECONDS + 600))
until [[ $(timeout 10s adb shell getprop sys.boot_completed | tr -d '\r') == 1 ]]; do
  kill -0 "$pid"
  ((SECONDS < deadline)) || { echo 'Emulator boot deadline reached'; exit 1; }
  sleep 2
done
for setting in window_animation_scale transition_animation_scale animator_duration_scale; do
  adb shell settings put global "$setting" 0.0
done
# Wait for a real HOME window before sending input, as in the regular runtime lane.
deadline=$((SECONDS + 180))
until adb shell dumpsys window | grep -Eq 'mCurrentFocus=.*(Launcher|launcher)'; do
  ((SECONDS < deadline)) || { echo 'HOME deadline reached'; exit 1; }
  sleep 2
done
python3 tools/prototype_statusbar_capture.py --apk app/build/outputs/apk/debug/app-debug.apk --evidence "$evidence"
