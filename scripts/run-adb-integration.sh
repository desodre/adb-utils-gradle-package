#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 API_LEVEL" >&2
  exit 2
fi

api_level="$1"
adb version

dump_emulator_failure() {
  result=$?
  if (( result != 0 )) && [[ "$api_level" == "37.0" ]]; then
    echo "Filtered Android 17 logcat after integration failure:" >&2
    adb -s emulator-5554 logcat -d -v time \
      | grep -Ei 'FATAL EXCEPTION|Fatal signal|OutOfMemory|lowmemory|Watchdog.*(Killing|blocked)|system_server.*(died|crash|abort)|adbutils.fixture|PackageManagerInternal' \
      | tail -n 120 || true
  fi
}
trap dump_emulator_failure EXIT

if [[ "$api_level" == "37.0" ]]; then
  package_service_ready=false
  for attempt in {1..24}; do
    service_status="$(adb -s emulator-5554 shell service check package 2>&1 || true)"
    activity_status="$(adb -s emulator-5554 shell service check activity 2>&1 || true)"
    system_pid_before="$(adb -s emulator-5554 shell pidof system_server 2>/dev/null || true)"
    boot_state="$(adb -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null || true)"
    sleep 5
    system_pid_after="$(adb -s emulator-5554 shell pidof system_server 2>/dev/null || true)"
    if [[ "$service_status" == *"Service package: found"* ]] &&
       [[ "$activity_status" == *"Service activity: found"* ]] &&
       [[ "$boot_state" == "1" ]] &&
       [[ -n "$system_pid_before" ]] &&
       [[ "$system_pid_before" == "$system_pid_after" ]]; then
      package_service_ready=true
      break
    fi
  done

  if [[ "$package_service_ready" != true ]]; then
    echo "Android services did not stabilize: package=$service_status activity=$activity_status boot=$boot_state pid_before=$system_pid_before pid_after=$system_pid_after" >&2
    adb -s emulator-5554 shell df -h /data || true
    exit 1
  fi
  adb -s emulator-5554 shell df -h /data
fi

./gradlew adbTest -PadbTest=true -PadbSerial=emulator-5554 -PadbTargetKind=emulator
