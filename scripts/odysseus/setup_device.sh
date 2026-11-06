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
a appops set $P REQUEST_INSTALL_PACKAGES allow   # updates from Odysseus (docs/odysseus/updates.md)
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
# Landscape, rotation locked (rotation 0 is landscape on cronos; there is no accelerometer)
a settings put system accelerometer_rotation 0
a settings put system user_rotation 0

echo "== privacy button: key layout (scancode 116 = MEDIA_RECORD, no sleep; docs/odysseus/echo-show-5.md)"
KL=gpio-privacy-button.kl
KLDIR=/data/system/devices/keylayout
if a 'cat /proc/bus/input/devices' | grep -q 'gpio-privacy-button'; then
  if adb -s "$S" root >/dev/null 2>&1 && adb -s "$S" wait-for-device && [ "$(a id -u | tr -d '\r')" = 0 ]; then
    if [ "$(a "md5sum $KLDIR/$KL 2>/dev/null" | awk '{print $1}')" = "$(md5sum "$(dirname "$0")/$KL" | awk '{print $1}')" ]; then
      echo "already installed"
    else
      adb -s "$S" push "$(dirname "$0")/$KL" /data/local/tmp/$KL
      a "mkdir -p $KLDIR && cp /data/local/tmp/$KL $KLDIR/ \
        && chown -R system:system /data/system/devices && chmod 755 /data/system/devices $KLDIR \
        && chmod 644 $KLDIR/$KL && restorecon -R /data/system/devices; rm -f /data/local/tmp/$KL"
    fi
    adb -s "$S" unroot >/dev/null 2>&1 || true
    adb -s "$S" wait-for-device
    # The layout is read when the input device opens, i.e. at boot.
    if a dumpsys input | grep -A12 'gpio-privacy-button' | grep -q "KeyLayoutFile: $KLDIR/$KL"; then
      echo "loaded"
    else
      echo "installed, NOT loaded yet: reboot the Echo (adb reboot), then check:"
      echo "  adb shell dumpsys input | grep -A12 gpio-privacy-button | grep KeyLayoutFile"
    fi
  else
    echo "WARNING: adb root not available (Settings > System > Developer options > Rooted debugging);"
    echo "         key layout not installed: the privacy button still puts the device to sleep"
  fi
else
  echo "no gpio-privacy-button input device: not an Echo Show 5, skipped"
fi

echo "== start"
a am start -n $P/com.msp1974.vacompanion.MainActivity
echo done
