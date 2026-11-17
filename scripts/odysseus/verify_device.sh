#!/usr/bin/env bash
# Check an Echo running the Odysseus fork over adb and print a PASS/CHECK list
# (docs/odysseus/echo-show-5.md#checking-a-device). Changes nothing on the device: it reads
# the app's version, its Odysseus settings, the privacy button's key events and key layout,
# and the log. The button capture is guided ("press once now… hold now…"); pressing the button
# does what it always does (a recording starts in Record mode), so stop it afterwards.
#
# usage: scripts/odysseus/verify_device.sh [--no-button] [--root] [serial]
#   --no-button  skip the guided privacy-button capture
#   --root       read the settings with `adb root` when the app isn't debuggable (needs
#                Developer options > Rooted debugging; switched back with `adb unroot`)
# Tokens are never printed: values of keys named like token/secret/password/key are hidden.
set -uo pipefail

P=com.msp1974.vacompanion.odysseus
KL=/data/system/devices/keylayout/gpio-privacy-button.kl
WAIT=${VERIFY_WAIT:-8}   # seconds per button step
BUTTON=1
ROOT=0
S=""
for arg in "$@"; do
  case "$arg" in
    --no-button) BUTTON=0 ;;
    --root) ROOT=1 ;;
    -h|--help) sed -n '2,13p' "$0"; exit 0 ;;
    *) S=$arg ;;
  esac
done
S=${S:-$(adb devices | awk 'NR>1 && $2=="device"{print $1; exit}')}
if [ -z "$S" ]; then
  echo "no device: connect the Echo (adb connect <ip>:5555) or pass its serial" >&2
  exit 2
fi
a(){ adb -s "$S" shell "$@" | tr -d '\r'; }

RESULTS=()
pass(){ RESULTS+=("PASS   $1"); }
check(){ RESULTS+=("CHECK  $1"); }

# Hide secret values in SharedPreferences XML (<string name="token_enc">…</string>).
redact(){ sed -E 's#(<string name="[^"]*(token|secret|password|passwd|key)[^"]*">)[^<]*(</string>)#\1<hidden>\3#I'; }

echo "== device $S"
echo "model $(a getprop ro.product.model), LineageOS $(a getprop ro.lineage.version), SDK $(a getprop ro.build.version.sdk)"

echo
echo "== app"
PKG=$(a dumpsys package $P)
if ! printf '%s\n' "$PKG" | grep -q 'versionName='; then
  echo "$P is not installed"
  check "app installed (scripts/odysseus/setup_device.sh)"
  INSTALLED=0
else
  INSTALLED=1
  VNAME=$(printf '%s\n' "$PKG" | grep -m1 -o 'versionName=[^ ]*' | cut -d= -f2)
  VCODE=$(printf '%s\n' "$PKG" | grep -m1 -o 'versionCode=[0-9]*' | cut -d= -f2)
  echo "version $VNAME ($VCODE)"
  pass "app installed: $VNAME ($VCODE)"
  if printf '%s\n' "$PKG" | grep -m1 'pkgFlags=' | grep -q DEBUGGABLE; then DEBUGGABLE=1; else DEBUGGABLE=0; fi
  echo "debuggable: $([ $DEBUGGABLE = 1 ] && echo yes || echo no)"
  if [ -n "$(a pidof $P)" ]; then pass "app running"; else check "app running (not running: adb shell am start -n $P/com.msp1974.vacompanion.MainActivity)"; fi
fi

echo
echo "== Odysseus settings (SharedPreferences odysseus_*)"
PREFS_READ=0
DEVICE_ID=""
read_prefs(){  # $1: a command prefix that runs as the app or as root, $2: the prefs dir
  local files f
  files=$(a "$1 ls $2" 2>/dev/null | grep -E '^odysseus_.*\.xml$' || true)
  [ -n "$files" ] || return 1
  for f in $files; do
    echo "-- $f"
    a "$1 cat $2/$f" | redact | grep -vE '^<\?xml|^<map>|^</map>'
  done
  DEVICE_ID=$(a "$1 cat $2/${P}_preferences.xml" 2>/dev/null \
    | sed -nE 's#.*<string name="uuid">([^<]*)</string>.*#\1#p' | head -1)
  BASE_URL=$(a "$1 cat $2/odysseus_link.xml" 2>/dev/null \
    | sed -nE 's#.*<string name="base_url">([^<]*)</string>.*#\1#p' | head -1)
  HAS_TOKEN=$(a "$1 cat $2/odysseus_link.xml" 2>/dev/null | grep -c 'name="token_enc"')
  return 0
}
BASE_URL=""; HAS_TOKEN=0
if [ $INSTALLED = 1 ]; then
  if [ "$DEBUGGABLE" = 1 ] && read_prefs "run-as $P" shared_prefs; then
    PREFS_READ=1
  elif [ "$(a id -u)" = 0 ] && read_prefs "" /data/data/$P/shared_prefs; then
    PREFS_READ=1
  elif [ $ROOT = 1 ]; then
    if adb -s "$S" root >/dev/null 2>&1 && adb -s "$S" wait-for-device && [ "$(a id -u)" = 0 ]; then
      read_prefs "" /data/data/$P/shared_prefs && PREFS_READ=1
      adb -s "$S" unroot >/dev/null 2>&1 || true
      adb -s "$S" wait-for-device
    else
      echo "adb root not available (Developer options > Rooted debugging)"
    fi
  fi
  if [ $PREFS_READ = 1 ]; then
    [ -n "$DEVICE_ID" ] && echo "device id (for the hub's scripts/echo_verify.py --device-id): $DEVICE_ID"
    if [ -n "$BASE_URL" ] && [ "$HAS_TOKEN" -gt 0 ]; then
      pass "linked to Odysseus: $BASE_URL"
    else
      check "linked to Odysseus (no hub address or token: Menu > Odysseus > Link)"
    fi
  else
    echo "not read: the app isn't debuggable (a release build) and adb isn't root."
    echo "  rerun with --root (Developer options > Rooted debugging), or see Menu > Odysseus on the Echo"
    check "Odysseus settings not read (--root)"
  fi
fi

echo
echo "== privacy button: key layout"
if ! a 'cat /proc/bus/input/devices' | grep -q 'gpio-privacy-button'; then
  echo "no gpio-privacy-button input device: not an Echo Show 5"
  HAS_BUTTON=0
else
  HAS_BUTTON=1
  LAYOUT=$(a dumpsys input | grep -A12 'gpio-privacy-button' | grep -m1 'KeyLayoutFile' | sed 's/^ *//')
  echo "${LAYOUT:-KeyLayoutFile: (none)}"
  if printf '%s' "$LAYOUT" | grep -q "$KL"; then
    pass "key layout loaded ($KL)"
  else
    check "key layout not loaded: setup_device.sh installs it, then adb reboot"
  fi
fi

capture(){  # $1: prompt; prints the key/switch lines seen in $WAIT seconds
  echo ">>> $1 (${WAIT} s)" >&2
  a "timeout $WAIT getevent -lt 2>/dev/null" \
    | grep -E 'KEY_POWER|KEY_RECORD|KEY_MEDIA|SW_MUTE_DEVICE' || true
}
seconds(){ sed -nE 's/^\[ *([0-9]+\.[0-9]+)\].*/\1/p'; }

LOG_SINCE=""
if [ $HAS_BUTTON = 1 ] && [ $BUTTON = 1 ]; then
  echo
  echo "== privacy button: key events (guided)"
  echo "The app reacts as usual: in Record mode a press starts a recording and a hold a voice note;"
  echo "stop them afterwards (press once more, or tap the pill)."
  LOG_SINCE=$(a date '+%m-%d %H:%M:%S.000')
  PRESS=$(capture "Press the privacy button ONCE now")
  printf '%s\n' "$PRESS"
  HOLD=$(capture "Now HOLD the privacy button for 2 s, then let go")
  printf '%s\n' "$HOLD"

  DOWN=$(printf '%s\n' "$PRESS" | grep -c 'EV_KEY.*DOWN')
  UP=$(printf '%s\n' "$PRESS" | grep -c 'EV_KEY.*UP')
  if [ "$DOWN" -ge 1 ] && [ "$UP" -ge 1 ]; then
    pass "press: key DOWN and UP seen"
  else
    check "press: no key DOWN/UP seen (screen off? Android sees no key then; see echo-show-5.md)"
  fi
  T0=$(printf '%s\n' "$HOLD" | grep -m1 'EV_KEY.*DOWN' | seconds)
  T1=$(printf '%s\n' "$HOLD" | grep 'EV_KEY.*UP' | tail -1 | seconds)
  if [ -n "$T0" ] && [ -n "$T1" ]; then
    HELD=$(awk -v a="$T0" -v b="$T1" 'BEGIN{printf "%.2f", b-a}')
    if awk -v h="$HELD" 'BEGIN{exit !(h >= 1.0)}'; then
      pass "hold: UP ${HELD} s after DOWN (holds work: voice notes from the button)"
    else
      check "hold: UP only ${HELD} s after DOWN: the button reports no hold (every press is a press)"
    fi
  else
    check "hold: no DOWN/UP pair seen"
  fi
  if printf '%s\n%s\n' "$PRESS" "$HOLD" | grep -q SW_MUTE_DEVICE; then
    pass "mute switch (gpio-privacy-state) reports: the screen-off fallback works"
  else
    check "mute switch: no SW_MUTE_DEVICE seen"
  fi
fi

echo
echo "== log (Recorder|Kiosk|Today|Update|Announce|Privacy button)"
LOG=$(a logcat -d -v time)
printf '%s\n' "$LOG" | grep -E 'Recorder|Kiosk|Today|Update|Announce|Privacy button' | tail -n 60
if printf '%s\n' "$LOG" | grep -q 'Going to sleep due to power_button'; then
  check "the device went to sleep on the button (power_button in the log): key layout not loaded?"
else
  pass "no sleep on the privacy button in the log"
fi
if [ $HAS_BUTTON = 1 ] && [ $BUTTON = 1 ]; then
  SINCE=$(printf '%s\n' "$LOG" | awk -v s="$LOG_SINCE" '$1" "$2 >= s')
  if printf '%s\n' "$SINCE" | grep -q 'Privacy button: \(press\|hold\)'; then
    pass "the app saw the button ($(printf '%s\n' "$SINCE" | grep -o 'Privacy button: [a-z]* ([a-z ]*)' | sort -u | paste -sd, -))"
  else
    check "the app logged no 'Privacy button: press/hold' (Privacy button set to Mute, or the app not in front?)"
  fi
fi
grep_check(){  # $1: pattern meaning it works, $2: label, $3: hint when missing
  if printf '%s\n' "$LOG" | grep -qE "$1"; then pass "$2"; else check "$2: not in the log ($3)"; fi
}
grep_check 'Announcements: hello sent' "hello sent to Odysseus" "linked? the log may have rotated"
FEED=$(printf '%s\n' "$LOG" | grep -o 'Announcements: feed [A-Z_]*' | tail -1 | awk '{print $3}')
case "$FEED" in
  OK) pass "announcements feed: OK" ;;
  "") check "announcements feed: no outcome in the log (it logs only changes; the log may have rotated)" ;;
  *) check "announcements feed: last outcome $FEED (UNAUTHORIZED: link again; NOT_SUPPORTED: older hub)" ;;
esac
if printf '%s\n' "$LOG" | grep -qE 'Update: check failed|Update: download of .* failed|Update: install of .* refused'; then
  check "app updates: a failure in the log (see the Update lines above)"
else
  pass "app updates: no failure in the log"
fi
if printf '%s\n' "$LOG" | grep -qE 'Recorder: (hub refused|could not)'; then
  check "recorder: a failure in the log (see the Recorder lines above)"
else
  pass "recorder: no failure in the log"
fi

echo
echo "== summary"
printf '%s\n' "${RESULTS[@]}"
N=$(printf '%s\n' "${RESULTS[@]}" | grep -c '^CHECK')
echo
echo "$((${#RESULTS[@]} - N)) PASS, $N CHECK"
