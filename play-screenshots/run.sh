#!/usr/bin/env bash
# Capture Play Store screenshots for VoxCrew using the android-play-screenshots skill.
# Compatible with macOS Bash 3.2.
# Uses native AVD resolutions only (no crop/scale). Phone AVD must be ≤2:1 (1080×1920).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SKILL="${HOME}/.cursor/skills/android-play-screenshots/scripts"
CONFIG="$ROOT/play-screenshots/config.yaml"
OUT="$ROOT/play-screenshots/out"
FLOWS="$ROOT/play-screenshots/flows"
CAPTURE_FLOW="$FLOWS/capture-all.yaml"

export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
export PATH="$HOME/.maestro/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

if [[ ! -d "$SKILL" ]]; then
  echo "Missing skill scripts at $SKILL" >&2
  exit 1
fi
if ! command -v maestro >/dev/null 2>&1; then
  echo "Maestro CLI not found. Install: curl -Ls \"https://get.maestro.mobile.dev\" | bash" >&2
  exit 1
fi

APP_ID=$(awk '/^appId:/{print $2; exit}' "$CONFIG")
APK_REL=$(awk '/^apkPath:/{print $2; exit}' "$CONFIG")
ANDROID_DIR=$(awk '/^androidDir:/{print $2; exit}' "$CONFIG")
APK="$ROOT/$APK_REL"

AVDS=""
while IFS= read -r line; do
  avd=$(echo "$line" | awk '{print $2}')
  AVDS="$AVDS $avd"
done < <(awk '/avd:/{print}' "$CONFIG")
AVDS=$(echo "$AVDS" | xargs)

echo "Building debug APK…"
(
  cd "$ROOT/$ANDROID_DIR"
  ./gradlew :app:assembleDebug
)

echo "Booting AVDs: $AVDS"
BOOT_TMP=$(mktemp)
# shellcheck disable=SC2086
"$SKILL/boot-avds.sh" $AVDS | tee "$BOOT_TMP"

grant_perms() {
  local serial="$1"
  local perm
  while IFS= read -r perm; do
    [[ -z "$perm" ]] && continue
    "$ANDROID_HOME/platform-tools/adb" -s "$serial" shell pm grant "$APP_ID" "$perm" 2>/dev/null || true
  done < <(awk '/^- android\.permission/{print $2}' "$CONFIG")
}

while IFS=$'\t' read -r avd serial; do
  [[ -z "${serial:-}" ]] && continue
  "$SKILL/wait-for-device.sh" "$serial"
done < "$BOOT_TMP"

while IFS=$'\t' read -r avd serial; do
  [[ -z "${serial:-}" ]] && continue
  "$SKILL/install-apk.sh" "$serial" "$APK"
  grant_perms "$serial"
done < "$BOOT_TMP"

slot_for_avd() {
  case "$1" in
    Play_Phone_1080x1920|Pixel_9) echo phone ;;
    My_7_inch_tablet) echo tablet7 ;;
    Pixel_Tablet) echo tablet10 ;;
    *) echo "$1" ;;
  esac
}

SHOT_NAMES="01-profile-name 02-main-crew-demo 03-audio-bluetooth 04-solo-marc 05-vox-bluetooth-ptt 06-forget-quentin"

rm -rf "$OUT"
mkdir -p "$OUT"

# Index-based loop — Maestro (and other tools) must not consume the boot-list via stdin.
device_count=$(wc -l < "$BOOT_TMP" | tr -d ' ')
i=1
while [ "$i" -le "$device_count" ]; do
  line=$(sed -n "${i}p" "$BOOT_TMP")
  i=$((i + 1))
  avd=$(printf '%s' "$line" | cut -f1)
  serial=$(printf '%s' "$line" | cut -f2)
  [[ -z "$serial" ]] && continue
  slot="$(slot_for_avd "$avd")"
  mkdir -p "$OUT/$slot"
  echo "=== Device $avd ($serial) → $slot ==="

  # Tablets ship landscape natively; ensure width > height for Play tablet shots.
  if [[ "$slot" == tablet7 || "$slot" == tablet10 ]]; then
    "$ANDROID_HOME/platform-tools/adb" -s "$serial" shell wm size reset >/dev/null
    for _ in 1 2 3 4; do
      dims=$("$ANDROID_HOME/platform-tools/adb" -s "$serial" shell wm size | awk -F': ' '{print $2}' | tr -d '\r')
      w=${dims%x*}
      h=${dims#*x}
      if [[ -n "$w" && -n "$h" && "$w" -gt "$h" ]]; then break; fi
      "$ANDROID_HOME/platform-tools/adb" -s "$serial" emu rotate || true
      sleep 1
    done
  fi

  "$ANDROID_HOME/platform-tools/adb" -s "$serial" shell pm clear "$APP_ID" >/dev/null
  grant_perms "$serial"

  # Maestro takeScreenshot writes into the process CWD — run from the slot folder.
  (
    cd "$OUT/$slot"
    maestro --device "$serial" test "$CAPTURE_FLOW" </dev/null
  )

  for name in $SHOT_NAMES; do
    png="$OUT/$slot/${name}.png"
    if [[ ! -f "$png" ]]; then
      echo "Missing screenshot $png" >&2
      exit 1
    fi
    "$SKILL/validate-png.sh" "$png"
  done
done

rm -f "$BOOT_TMP"

echo "Done. Screenshots:"
find "$OUT" -name '*.png' | sort
