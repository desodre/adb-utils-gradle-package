#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 API_LEVEL" >&2
  exit 2
fi

api_level="$1"
adb version

if [[ "$api_level" == "37.0" ]]; then
  package_service_ready=false
  for attempt in {1..120}; do
    service_status="$(adb -s emulator-5554 shell service check package 2>&1 || true)"
    if [[ "$service_status" == *"Service package: found"* ]]; then
      package_service_ready=true
      break
    fi
    sleep 2
  done

  if [[ "$package_service_ready" != true ]]; then
    echo "Android package service did not become ready: $service_status" >&2
    adb -s emulator-5554 shell df -h /data || true
    exit 1
  fi
  adb -s emulator-5554 shell df -h /data
fi

./gradlew adbTest -PadbTest=true -PadbSerial=emulator-5554 -PadbTargetKind=emulator
