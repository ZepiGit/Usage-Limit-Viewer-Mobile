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
export ANDROID_SERIAL=emulator-5554
runtime_directory=$(mktemp -d "$RUNNER_TEMP/usage-runtime.XXXXXX")
export ANDROID_AVD_HOME="$runtime_directory/avd"
mkdir -p "$ANDROID_AVD_HOME"
emulator_pid=

cleanup() {
  local status=$?
  trap - EXIT
  set +e
  # Preserve boot/system evidence before terminating only the emulator we started.
  if [[ -n "$emulator_pid" ]]; then
    timeout 15s adb -s "$ANDROID_SERIAL" logcat -b all -d -v threadtime > "$evidence/emulator-system-log.txt" 2>&1
    timeout 15s adb -s "$ANDROID_SERIAL" shell dumpsys activity lastanr > "$evidence/emulator-last-anr.txt" 2>&1
    timeout 15s adb -s "$ANDROID_SERIAL" shell dumpsys window > "$evidence/emulator-window.txt" 2>&1
    timeout 15s adb -s "$ANDROID_SERIAL" shell dumpsys activity activities > "$evidence/emulator-activities.txt" 2>&1
    if kill -0 "$emulator_pid" 2>/dev/null; then
      kill "$emulator_pid"
      for ((attempt = 0; attempt < 20; attempt++)); do
        kill -0 "$emulator_pid" 2>/dev/null || break
        sleep 1
      done
      if kill -0 "$emulator_pid" 2>/dev/null; then
        kill -KILL "$emulator_pid"
      fi
    fi
    wait "$emulator_pid" 2>/dev/null
  fi
  rm -rf -- "$runtime_directory"
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# The API 36 guest exhausted its two assigned CPUs during captured SystemUI
# startup ANRs. Record the host and require capacity for four guest CPUs.
runtime_host_cpus=$(nproc)
printf '%s\n' "$runtime_host_cpus" > "$evidence/host-nproc.txt"
lscpu > "$evidence/host-lscpu.txt"
stat /dev/kvm > "$evidence/host-kvm.txt"
if [[ ! "$runtime_host_cpus" =~ ^[0-9]+$ ]] || ((runtime_host_cpus < 4)); then
  echo 'API 36 runtime smoke requires at least four available host CPUs.' >&2
  exit 1
fi
if [[ ! -c /dev/kvm || ! -r /dev/kvm || ! -w /dev/kvm ]]; then
  echo 'API 36 runtime smoke requires readable and writable KVM.' >&2
  exit 1
fi

attached=$(timeout 10s adb devices)
if printf '%s\n' "$attached" | tail -n +2 | grep -q '[^[:space:]]'; then
  echo 'Refusing to start runtime smoke with an existing adb device.' >&2
  exit 1
fi

# setup-android provides sdkmanager/avdmanager and accepted SDK licences.
sdkmanager --install 'emulator' 'system-images;android-36;default;x86_64' > "$evidence/emulator-sdk-install.txt" 2>&1
avdmanager create avd --name usage-runtime --package 'system-images;android-36;default;x86_64' \
  --device pixel_7_pro <<< no > "$evidence/emulator-avd-create.txt" 2>&1
cp "$ANDROID_AVD_HOME/usage-runtime.avd/config.ini" "$evidence/emulator-avd-config.ini"
# The previous runner requested 2048M, but this image raised it to 4096MB.
# Keep that actual allocation and the same rendering/boot options; use four CPUs.
emulator_args=(-avd usage-runtime -port 5554 -cores 4 -memory 4096
  -no-window -gpu swiftshader_indirect -no-snapshot -noaudio -no-boot-anim
  -camera-back none -camera-front none)
printf '%s\n' "${emulator_args[@]}" > "$evidence/emulator-arguments.txt"
"$ANDROID_HOME/emulator/emulator" "${emulator_args[@]}" > "$evidence/emulator-boot.txt" 2>&1 &
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
  printf '%s boot_completed=%s\n' "$(date -u +%FT%TZ)" "$boot_completed" >> "$evidence/emulator-boot-probes.txt"
  [[ "$boot_completed" == 1 ]] && break
  sleep 1
done

for setting in window_animation_scale transition_animation_scale animator_duration_scale; do
  timeout 10s adb -s "$ANDROID_SERIAL" shell settings put global "$setting" 0.0
done
# The smoke checks focused HOME and its observed workspace before installing an APK.
# No keyevent, tap, ANR dismissal, or generic post-boot delay is sent here.
python3 "$script_directory/android_runtime_smoke.py" "${smoke_args[@]}"
