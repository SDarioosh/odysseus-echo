#!/usr/bin/env bash
# Set up an Echo Show 5 (2nd gen, LineageOS 18.1) as an always-on Odysseus voice satellite.
# Every step can be undone with adb; see docs/odysseus/echo-show-5.md (undo column).
# usage: scripts/odysseus/setup_device.sh <apk> [serial]
set -euo pipefail
APK=${1:?apk path}
S=${2:-$(adb devices | awk 'NR==2{print $1}')}
P=com.msp1974.vacompanion.odysseus
a(){ adb -s "$S" shell "$@"; }

python3 "$(dirname "$0")/check_apk_privacy.py" "$APK"

echo "== device"
a getprop ro.product.model; a getprop ro.lineage.version; a getprop ro.build.version.sdk

echo "== install $APK"
adb -s "$S" install -r "$APK"

echo "== permissions"
a pm grant $P android.permission.RECORD_AUDIO
a pm grant $P android.permission.POST_NOTIFICATIONS 2>/dev/null || true   # API 33+ only
a appops set $P WRITE_SETTINGS allow
a cmd notification allow_dnd $P

echo "== keep running (Doze / background)"
a dumpsys deviceidle whitelist +$P
a appops set $P RUN_IN_BACKGROUND allow
a appops set $P RUN_ANY_IN_BACKGROUND allow

echo "== kiosk: launcher = satellite (starts at boot), screen on while powered, no lock/screensaver"
a cmd package set-home-activity $P/com.msp1974.vacompanion.MainActivity
a settings put global stay_on_while_plugged_in 7
a locksettings set-disabled true
a settings put secure screensaver_enabled 0

echo "== start"
a am start -n $P/com.msp1974.vacompanion.MainActivity
echo done
