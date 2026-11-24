#!/usr/bin/env bash
# Start the fixed CI AVD without injecting an unlock key before HOME is ready.
# SDK commands: https://developer.android.com/tools/{sdkmanager,avdmanager}
# Emulator options: https://developer.android.com/studio/run/emulator-commandline
set -euo pipefail

smoke_args=("$@")
evidence=runtime/evidence
while (($#)); do
  case "$1" in
    --evidence)
      [[ $# -ge 2 && -n "$2" ]] || { echo 'Missing --evidence directory' >&2; exit 2; }
      evidence=$2
      shift
      ;;
    --evidence=*) evidence=${1#*=} ;;
  esac
  shift
done
mkdir -p "$evidence"
script_directory=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
: "${ANDROID_HOME:?Android SDK setup is required}"
: "${RUNNER_TEMP:?A dedicated CI runner is required}"
: "${DISPLAY:?Run under xvfb-run for the configured X11 environment}"
export ANDROID_SERIAL=emulator-5554
runtime_directory=$(mktemp -d "$RUNNER_TEMP/usage-runtime.XXXXXX")
export ANDROID_AVD_HOME="$runtime_directory/avd"
mkdir -p "$ANDROID_AVD_HOME"
emulator_pid=
boot_evidence=$evidence

preserve_boot() {
  [[ -n "$emulator_pid" ]] || return 0
  local command_status=0
  timeout 15s adb -s "$ANDROID_SERIAL" logcat -b all -d -v threadtime > "$boot_evidence/emulator-system-log.txt" 2>&1 || command_status=$?
  printf '%s\n' "$command_status" > "$boot_evidence/emulator-system-log-exit-status.txt"
  command_status=0
  timeout 15s adb -s "$ANDROID_SERIAL" shell dumpsys dropbox --print system_app_anr > "$boot_evidence/emulator-system-app-anr.txt" 2>&1 || command_status=$?
  printf '%s\n' "$command_status" > "$boot_evidence/emulator-system-app-anr-exit-status.txt"
  timeout 15s adb -s "$ANDROID_SERIAL" shell dumpsys activity lastanr > "$boot_evidence/emulator-last-anr.txt" 2>&1 || true
  timeout 15s adb -s "$ANDROID_SERIAL" shell dumpsys window > "$boot_evidence/emulator-window.txt" 2>&1 || true
  timeout 15s adb -s "$ANDROID_SERIAL" shell dumpsys activity activities > "$boot_evidence/emulator-activities.txt" 2>&1 || true
}

stop_emulator() {
  if [[ -n "$emulator_pid" ]]; then
    if kill -0 "$emulator_pid" 2>/dev/null; then
      kill "$emulator_pid"
      for ((probe = 0; probe < 20; probe++)); do
        kill -0 "$emulator_pid" 2>/dev/null || break
        sleep 1
      done
      if kill -0 "$emulator_pid" 2>/dev/null; then
        kill -KILL "$emulator_pid"
      fi
    fi
    wait "$emulator_pid" 2>/dev/null || true
    emulator_pid=
  fi
}

cleanup() {
  local status=$?
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

# Keep the verified four-CPU guest allocation and record the host capacity.
runtime_host_cpus=$(nproc)
printf '%s\n' "$runtime_host_cpus" > "$evidence/host-nproc.txt"
lscpu > "$evidence/host-lscpu.txt"
stat /dev/kvm > "$evidence/host-kvm.txt"
if [[ ! "$runtime_host_cpus" =~ ^[0-9]+$ ]] || ((runtime_host_cpus < 4)); then
  echo 'API 37 runtime smoke requires at least four available host CPUs.' >&2
  exit 1
fi
if [[ ! -c /dev/kvm || ! -r /dev/kvm || ! -w /dev/kvm ]]; then
  echo 'API 37 runtime smoke requires readable and writable KVM.' >&2
  exit 1
fi

attached=$(timeout 10s adb devices)
if printf '%s\n' "$attached" | tail -n +2 | grep -q '[^[:space:]]'; then
  echo 'Refusing to start runtime smoke with an existing adb device.' >&2
  exit 1
fi

# setup-android provides sdkmanager/avdmanager and accepted SDK licences.
sdkmanager --install 'emulator' 'system-images;android-37.0;default;x86_64' > "$evidence/emulator-sdk-install.txt" 2>&1
avdmanager create avd --name usage-runtime --package 'system-images;android-37.0;default;x86_64' \
  --device pixel_7_pro <<< no > "$evidence/emulator-avd-create.txt" 2>&1
cp "$ANDROID_AVD_HOME/usage-runtime.ini" "$evidence/emulator-avd-root.ini"
cp "$ANDROID_AVD_HOME/usage-runtime.avd/config.ini" "$evidence/emulator-avd-config.ini"
avdmanager_path=$(readlink -f "$(command -v avdmanager)")
cmdline_properties="$(dirname "$(dirname "$avdmanager_path")")/source.properties"
printf 'avdmanager=%s\nsource.properties=%s\n' "$avdmanager_path" "$cmdline_properties" > "$evidence/emulator-cmdline-tools.txt"
if [[ -f "$cmdline_properties" ]]; then
  cat "$cmdline_properties" >> "$evidence/emulator-cmdline-tools.txt"
fi
# Keep the same 411x891 dp viewport while reducing the CPU-bound software-rendered
# surface from 1440x3120 to 720x1560 pixels. A failed API 36 boot showed SystemUI
# blocked 14.3s in SurfaceControl.getGPUContextPriority while SurfaceFlinger and
# the graphics composer were saturated on a four-vCPU GitHub host.
python3 - "$ANDROID_AVD_HOME/usage-runtime.avd/config.ini" "$ANDROID_AVD_HOME/usage-runtime.ini" <<'PY'
from pathlib import Path
import re
import sys

config = Path(sys.argv[1])
root = Path(sys.argv[2])
text = config.read_text()
for path, contents, key, expected in (
    (root, root.read_text(), "target", "android-37.0"),
    (config, text, "image.sysdir.1", "system-images/android-37.0/default/x86_64/"),
):
    values = re.findall(rf"(?m)^{re.escape(key)}\s*=\s*(.*?)\s*$", contents)
    if values != [expected]:
        raise SystemExit(f"{path}: expected exactly one {key}={expected}, found {values!r}")
for key, value in {"hw.lcd.width": "720", "hw.lcd.height": "1560", "hw.lcd.density": "280"}.items():
    pattern = rf"(?m)^{re.escape(key)}=.*$"
    if len(re.findall(pattern, text)) != 1:
        raise SystemExit(f"Expected exactly one {key} AVD setting")
    text = re.sub(pattern, f"{key}={value}", text)
config.write_text(text)
PY
cp "$ANDROID_AVD_HOME/usage-runtime.avd/config.ini" "$evidence/emulator-avd-config.ini"
# The previous runner requested 2048M, but this image raised it to 4096MB.
# Keep that actual allocation and the boot options; use four CPUs.
# Captured LLVMpipe boots stalled SystemUI's GPU-priority request to SurfaceFlinger.
# Select SwiftShader explicitly, matching the earlier healthy boot's backends.
# https://developer.android.com/studio/run/emulator-acceleration
emulator_args=(-avd usage-runtime -port 5554 -cores 4 -memory 4096
  -no-window -gpu swiftshader -no-snapshot -wipe-data -noaudio -no-boot-anim
  -camera-back none -camera-front none)
printf '%s\n' "${emulator_args[@]}" > "$evidence/emulator-arguments.txt"
boot_emulator() {
  for attempt in 1 2 3; do
    boot_evidence="$evidence/boot-$attempt"
    mkdir -p "$boot_evidence"
    EGL_PLATFORM=x11 \
      "$ANDROID_HOME/emulator/emulator" "${emulator_args[@]}" > "$boot_evidence/emulator-boot.txt" 2>&1 &
    emulator_pid=$!
    boot_deadline=$((SECONDS + 600))
    timeout 120s adb -s "$ANDROID_SERIAL" wait-for-device
    while :; do
      if ! kill -0 "$emulator_pid" 2>/dev/null; then
        echo 'Emulator exited before boot completed; see emulator-boot.txt.' >&2
        exit 1
      fi
      if ((SECONDS >= boot_deadline)); then
        echo 'Emulator did not complete boot within 600 seconds.' >&2
        exit 1
      fi
      boot_completed=$(timeout 10s adb -s "$ANDROID_SERIAL" shell getprop sys.boot_completed | tr -d '\r') || boot_completed=
      printf '%s boot_completed=%s\n' "$(date -u +%FT%TZ)" "$boot_completed" >> "$boot_evidence/emulator-boot-probes.txt"
      [[ "$boot_completed" == 1 ]] && break
      sleep 1
    done

    # Fail before APK installation if an SDK update changes the selected backends.
    python3 - "$boot_evidence/emulator-boot.txt" <<'PY'
from pathlib import Path
import re
import sys

modes = set(re.findall(r"vulkan_mode_selected:(\S+)\s+gles_mode_selected:(\S+)", Path(sys.argv[1]).read_text()))
if modes != {("swiftshader", "swiftshader")}:
    raise SystemExit(f"Expected SwiftShader Vulkan/GLES; observed renderer modes: {sorted(modes)}")
PY

    display_size=$(timeout 10s adb -s "$ANDROID_SERIAL" shell wm size | tr -d '\r')
    display_density=$(timeout 10s adb -s "$ANDROID_SERIAL" shell wm density | tr -d '\r')
    printf '%s\n%s\n' "$display_size" "$display_density" > "$boot_evidence/emulator-runtime-display.txt"
    python3 - "$boot_evidence/emulator-runtime-display.txt" <<'PY'
from pathlib import Path
import math
import re
import sys

lines = Path(sys.argv[1]).read_text().splitlines()
size = re.fullmatch(r"Physical size: (\d+)x(\d+)", lines[0]) if len(lines) == 2 else None
density = re.fullmatch(r"Physical density: (\d+)", lines[1]) if len(lines) == 2 else None
if not size or not density or (int(size[1]), int(size[2]), int(density[1])) != (720, 1560, 280):
    raise SystemExit(f"Unexpected API 37 display: {lines}")
dp = tuple(int(value) * 160 / int(density[1]) for value in size.groups())
if any(not math.isclose(actual, expected, abs_tol=0.6)
       for actual, expected in zip(dp, (1440 * 160 / 560, 3120 * 160 / 560))):
    raise SystemExit(f"The reduced-resolution display changed the logical viewport: {dp}")
PY

    readiness_status=0
    python3 "$script_directory/android_runtime_smoke.py" --boot-readiness --serial "$ANDROID_SERIAL" \
      --evidence "$boot_evidence" || readiness_status=$?
    if ((readiness_status == 75)); then
      preserve_boot
      if ((attempt == 3)); then
        message='System UI startup ANR (com.android.systemui failed to complete startup) exhausted all three pre-install boots.'
        echo "$message" >&2
        [[ -z "${GITHUB_STEP_SUMMARY:-}" ]] || printf '%s\n' "$message" >> "$GITHUB_STEP_SUMMARY"
        exit 1
      fi
      stop_emulator
      continue
    fi
    ((readiness_status == 0)) || exit "$readiness_status"
    printf '%s\n' "$attempt" > "$evidence/installed-on-boot.txt"
    break
  done
}
boot_emulator

for setting in window_animation_scale transition_animation_scale animator_duration_scale; do
  timeout 10s adb -s "$ANDROID_SERIAL" shell settings put global "$setting" 0.0
done
# The smoke checks focused HOME and its observed workspace before installing an APK.
# No keyevent, tap, ANR dismissal, or generic post-boot delay is sent here.
python3 "$script_directory/android_runtime_smoke.py" "${smoke_args[@]}"
