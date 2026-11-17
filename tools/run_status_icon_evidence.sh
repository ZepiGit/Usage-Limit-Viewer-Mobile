#!/usr/bin/env bash
# Dedicated CI emulator; retry only a proven SystemUI ANR before APK installation.
set -euo pipefail
api=${STATUS_ICON_API:?Set STATUS_ICON_API to 30, 36 or 37}
case "$api" in 30|36|37) ;; *) echo "Unsupported API: $api" >&2; exit 2 ;; esac
: "${ANDROID_HOME:?Android SDK setup is required}"
: "${RUNNER_TEMP:?A dedicated CI runner is required}"
: "${DISPLAY:?Run under xvfb-run}"
evidence="runtime/status-icons-api$api"
mkdir -p "$evidence"
script_directory=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
export ANDROID_SERIAL=emulator-5554
runtime_directory=$(mktemp -d "$RUNNER_TEMP/status-icons.XXXXXX")
export ANDROID_AVD_HOME="$runtime_directory/avd"
mkdir -p "$ANDROID_AVD_HOME"
emulator_pid=
boot_evidence=

stop_emulator() {
  if [[ -n "$emulator_pid" ]]; then
    kill "$emulator_pid" 2>/dev/null || true
    for ((i = 0; i < 20; i++)); do
      kill -0 "$emulator_pid" 2>/dev/null || break
      sleep 1
    done
    kill -KILL "$emulator_pid" 2>/dev/null || true
    wait "$emulator_pid" 2>/dev/null || true
    emulator_pid=
  fi
}

preserve_boot() {
  [[ -n "$boot_evidence" ]] || return 0
  timeout 15s adb logcat -b all -d -v threadtime > "$boot_evidence/system-log.txt" 2>&1 || true
  timeout 15s adb shell dumpsys dropbox --print system_app_anr > "$boot_evidence/system-app-anr.txt" 2>&1 || true
  timeout 15s adb shell dumpsys activity lastanr > "$boot_evidence/last-anr.txt" 2>&1 || true
  timeout 15s adb shell dumpsys window > "$boot_evidence/window.txt" 2>&1 || true
  timeout 15s adb shell dumpsys activity activities > "$boot_evidence/activities.txt" 2>&1 || true
  timeout 15s adb exec-out screencap -p > "$boot_evidence/final-screen.png" 2>/dev/null || true
}

cleanup() {
  status=$?
  trap - EXIT
  set +e
  preserve_boot
  stop_emulator
  rm -rf -- "$runtime_directory"
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

nproc > "$evidence/host-nproc.txt"
lscpu > "$evidence/host-lscpu.txt"
stat /dev/kvm > "$evidence/host-kvm.txt"
(( $(nproc) >= 4 )) || { echo 'Four host CPUs are required' >&2; exit 1; }
[[ -c /dev/kvm && -r /dev/kvm && -w /dev/kvm ]] || { echo 'Usable KVM is required' >&2; exit 1; }
attached=$(timeout 10s adb devices)
if printf '%s\n' "$attached" | tail -n +2 | grep -q '[^[:space:]]'; then
  echo 'Refusing to use a runner with an already attached device' >&2
  exit 1
fi
image_api=$api
[[ "$api" != 37 ]] || image_api=37.0
image="system-images;android-$image_api;default;x86_64"
sdkmanager --list > "$evidence/sdk-packages.txt"
grep -F "$image " "$evidence/sdk-packages.txt" > "$evidence/selected-image.txt" || {
  echo "The SDK repository does not list $image" >&2; exit 1;
}
# setup-android supplies the same accepted licences as the production runtime lane.
sdkmanager --install emulator "$image" > "$evidence/sdk-install.txt" 2>&1
avdmanager create avd --name status-icons --package "$image" --device pixel_7_pro \
  <<< no > "$evidence/avd-create.txt" 2>&1
avdmanager_path=$(readlink -f "$(command -v avdmanager)")
properties="$(dirname "$(dirname "$avdmanager_path")")/source.properties"
printf 'avdmanager=%s\n' "$avdmanager_path" > "$evidence/cmdline-tools.txt"
cat "$properties" >> "$evidence/cmdline-tools.txt"
python3 - "$ANDROID_AVD_HOME/status-icons.avd/config.ini" "$ANDROID_AVD_HOME/status-icons.ini" "$image_api" <<'PY'
from pathlib import Path
import re
import sys
config, root, api = Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3]
text = config.read_text()
for path, contents, key, expected in (
    (root, root.read_text(), "target", f"android-{api}"),
    (config, text, "image.sysdir.1", f"system-images/android-{api}/default/x86_64/"),
):
    values = re.findall(rf"(?m)^{re.escape(key)}\s*=\s*(.*?)\s*$", contents)
    if values != [expected]:
        raise SystemExit(f"{path}: expected {key}={expected}, found {values!r}")
for key, value in {"hw.lcd.width": "720", "hw.lcd.height": "1560", "hw.lcd.density": "280"}.items():
    pattern = rf"(?m)^{re.escape(key)}=.*$"
    if len(re.findall(pattern, text)) != 1:
        raise SystemExit(f"Expected exactly one {key} AVD setting")
    text = re.sub(pattern, f"{key}={value}", text)
config.write_text(text)
PY
cp "$ANDROID_AVD_HOME/status-icons.ini" "$evidence/avd-root.ini"
cp "$ANDROID_AVD_HOME/status-icons.avd/config.ini" "$evidence/avd-config.ini"
emulator_args=(-avd status-icons -port 5554 -cores 4 -memory 4096
  -no-window -gpu swiftshader -no-snapshot -wipe-data -noaudio -no-boot-anim
  -camera-back none -camera-front none)
printf '%s\n' "${emulator_args[@]}" > "$evidence/emulator-arguments.txt"

for attempt in 1 2 3; do
  boot_evidence="$evidence/boot-$attempt"
  mkdir -p "$boot_evidence"
  EGL_PLATFORM=x11 "$ANDROID_HOME/emulator/emulator" "${emulator_args[@]}" > "$boot_evidence/emulator-boot.txt" 2>&1 &
  emulator_pid=$!
  timeout 120s adb wait-for-device
  deadline=$((SECONDS + 600))
  boot_completed=
  while ((SECONDS < deadline)); do
    kill -0 "$emulator_pid" 2>/dev/null || { echo 'Emulator exited before boot' >&2; exit 1; }
    boot_completed=$(timeout 10s adb shell getprop sys.boot_completed | tr -d '\r') || boot_completed=
    printf '%s boot_completed=%s\n' "$(date -u +%FT%TZ)" "$boot_completed" >> "$boot_evidence/boot-probes.txt"
    [[ "$boot_completed" == 1 ]] && break
    sleep 2
  done
  # Only this exact diagnosis may request another clean boot. No app is installed yet.
  preflight_status=0
  python3 "$script_directory/status_icon_evidence.py" --preflight --evidence "$boot_evidence" || preflight_status=$?
  if ((preflight_status == 75)); then
    preserve_boot
    if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
      printf 'API %s: rejected pre-install boot %s for a proven SystemUI ANR; evidence in boot-%s/.\n' "$api" "$attempt" "$attempt" >> "$GITHUB_STEP_SUMMARY"
    fi
    if ((attempt == 3)); then
      echo 'SystemUI ANR persisted after two pre-install boot retries' >&2
      exit 1
    fi
    printf 'SystemUI ANR before installation; retrying clean boot %s of 2\n' "$attempt" >> "$evidence/boot-retries.txt"
    stop_emulator
    continue
  fi
  ((preflight_status == 0)) || exit "$preflight_status"
  [[ "$boot_completed" == 1 ]] || { echo 'Boot did not complete within 600 seconds' >&2; exit 1; }
  python3 - "$boot_evidence/emulator-boot.txt" <<'PY'
from pathlib import Path
import re
import sys
modes = set(re.findall(r"vulkan_mode_selected:(\S+)\s+gles_mode_selected:(\S+)", Path(sys.argv[1]).read_text()))
if modes != {("swiftshader", "swiftshader")}:
    raise SystemExit(f"Expected SwiftShader Vulkan/GLES, found {sorted(modes)}")
PY
  timeout 10s adb shell wm size > "$evidence/runtime-display.txt"
  timeout 10s adb shell wm density >> "$evidence/runtime-display.txt"
  python3 - "$evidence/runtime-display.txt" <<'PY'
from pathlib import Path
import sys
lines = Path(sys.argv[1]).read_text().splitlines()
if lines != ["Physical size: 720x1560", "Physical density: 280"]:
    raise SystemExit(f"Unexpected emulator display: {lines}")
PY
  printf '%s\n' "$attempt" > "$evidence/installed-on-boot.txt"
  # No retry path exists beyond this point, even if installation or the scenario fails.
  python3 "$script_directory/status_icon_evidence.py" --apk app/build/outputs/apk/debug/app-debug.apk --evidence "$evidence"
  exit 0
done
