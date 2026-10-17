#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
set -o noclobber
if [[ ${1:-} == --help ]]; then
  echo 'Usage: collect-info.sh DEVICE_SERIAL NEW_OUTPUT_DIRECTORY [dev.bananajeans.solsleep.debug|dev.bananajeans.solsleep]'
  echo 'Reads device/build/app information and copies only the installed application APK. No tests or device changes.'
  exit 0
fi
if [[ $# -lt 2 || $# -gt 3 ]]; then
  echo 'Usage: collect-info.sh DEVICE_SERIAL NEW_OUTPUT_DIRECTORY [SOLSLEEP_PACKAGE]' >&2
  exit 2
fi
device_serial=$1
output_directory=$2
app_package=${3:-dev.bananajeans.solsleep.debug}
case "$app_package" in
  dev.bananajeans.solsleep.debug|dev.bananajeans.solsleep) ;;
  *) echo 'Only a SolSleep package can be collected.' >&2; exit 2 ;;
esac
command -v adb >/dev/null
command -v sha256sum >/dev/null
adb_command=(adb -s "$device_serial")
device_state=$("${adb_command[@]}" get-state 2>/dev/null | tr -d '\r') || {
  echo 'The selected device is not online and authorized. No report was created.' >&2; exit 3;
}
if [[ "$device_state" != device ]]; then
  echo 'The selected device is not online and authorized. No report was created.' >&2
  exit 3
fi
if [[ -e "$output_directory" ]]; then
  echo 'Choose a new output directory; existing reports are never overwritten.' >&2
  exit 4
fi
apk_paths=$("${adb_command[@]}" shell pm path "$app_package" | tr -d '\r')
base_apk=$(printf '%s\n' "$apk_paths" | sed -n 's/^package:\(.*\/base\.apk\)$/\1/p')
if [[ -z "$base_apk" || "$base_apk" == *$'\n'* ]]; then
  echo 'Exactly one installed SolSleep base APK is required. No report was created.' >&2
  exit 5
fi
mkdir -p -- "$(dirname -- "$output_directory")"
mkdir -- "$output_directory"
{
  echo 'SolSleep physical device information — qualification NOT RUN'
  date -u '+Collected at %Y-%m-%dT%H:%M:%SZ (host clock; not an onset measurement)'
  echo "Package: $app_package"
  for property in ro.product.manufacturer ro.product.model ro.product.device ro.build.version.release ro.build.version.sdk ro.build.version.security_patch ro.product.cpu.abilist ro.build.fingerprint; do
    property_value=$("${adb_command[@]}" shell getprop "$property" | tr -d '\r')
    printf '%s: %s\n' "$property" "$property_value"
  done
  echo 'No serial number, personal records, audio, or app-private data collected.'
} > "$output_directory/device-info.txt"
"${adb_command[@]}" shell dumpsys package "$app_package" > "$output_directory/package-info.txt"
"${adb_command[@]}" pull "$base_apk" "$output_directory/installed.apk" >/dev/null
sha256sum "$output_directory/installed.apk" > "$output_directory/installed-apk.sha256"
if command -v apksigner >/dev/null; then
  apksigner verify --print-certs "$output_directory/installed.apk" > "$output_directory/apk-signing.txt"
else
  echo 'Signing information not collected: apksigner is not in PATH.' > "$output_directory/apk-signing.txt"
fi
echo "Information collected in $output_directory. Qualification tests were not run."
