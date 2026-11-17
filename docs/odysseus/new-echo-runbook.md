# Runbook: set up a brand-new Echo Show 5 (for an agent)

Written for an agent with adb access on the build machine. It takes an Echo Show 5 (2nd gen,
`cronos`) from "LineageOS just booted and the owner turned on adb root" to "a satellite that matches
the other Echos, waiting for phone setup". Each step has the command, what to check, and what to do
when it fails. Background on each setting is in [echo-show-5.md](echo-show-5.md); this file only
gives the order.

## Starting point (done by the owner, not by you)

- The Echo is unlocked (amonet), TWRP is installed, and LineageOS 18.1 for `cronos` is flashed.
- After the first boot the owner went through the Android setup, connected Wi-Fi, and turned on
  Settings → About → tap Build number 7× → Developer options → **USB debugging** and
  **Rooted debugging**. They accepted the computer's RSA key on the Echo screen.
- The Echo is on its own power adapter and is connected to the build machine by USB.

If any of this is missing, stop and tell the owner what's missing. Never flash, fastboot, wipe, or
touch `/system` or the bootloader partitions yourself: on `cronos` a brick there can't be fixed.

## Rules for this job

- On every command use `adb -s $S`, where `$S` is the serial whose `ro.product.device` is
  `cronos`. Other adb devices (phones, tablets, other Echos) may be attached.
- Before every tap, dump the UI (`uiautomator dump`) and tap the centre of the node's bounds.
  Never tap blind coordinates: the onboarding screens include one with an *Uninstall app* button.
- Never type passwords, tokens or Tailscale credentials. Logins happen on the owner's phone.
- Don't call Home Assistant, Odysseus or Music Assistant APIs. Read only the Echo's state over adb.
- Ask the owner once before the reboot in step 9 (or before at the start, covering both).
- In your report, say what you changed and what is still left for the owner.

## Inputs

| Input | Where it comes from |
|---|---|
| `APK` | The release-signed APK with the **same `versionName` as the owner's other Echos** (all Echos must run the same build, see wake-word arbitration in `CLAUDE.md`). It's usually the newest `app/build/outputs/apk/release/odysseus-vaca-<versionName>-release.apk` in the worktrees. If there's none, build it ([build.md](build.md)). Never install a debug build: Odysseus's updates only install over the release key. |
| `TZ` | The owner's time zone, as an IANA name (e.g. `America/New_York`). Ask if you don't know it. |

```sh
S=$(for s in $(adb devices | awk 'NR>1 && $2=="device"{print $1}'); do
      [ "$(adb -s $s shell getprop ro.product.device | tr -d '\r')" = cronos ] && echo $s; done | head -1)
P=com.msp1974.vacompanion.odysseus
echo "serial=$S"
```

If `adb devices` lists the Echo as `no permissions`, or doesn't list it although `lsusb` shows it,
it's a host udev permission problem, not a device problem. The owner fixes this on the host (with
sudo; see step 0).

## 0. Host USB access (once per host and per USB id)

The Echo shows up with a different USB id in each mode: LineageOS `1949:*`, TWRP `18d1:d001`,
fastboot `18d1:4ee2`, and amonet's bootrom/preloader `0e8d:*`. Each of them needs a udev rule that
lets the agent's user open it. If one is missing, give the owner this line to add (replace the user
name), then `sudo udevadm control --reload && sudo udevadm trigger`:

```
SUBSYSTEM=="usb", ATTR{idVendor}=="18d1", ATTR{idProduct}=="d001", GROUP="<agent user>", MODE="0660"
```

## 1. Check the device

```sh
adb -s $S shell 'getprop ro.product.model; getprop ro.lineage.version; getprop ro.build.version.sdk;
  pm list packages -3; dumpsys wifi | grep -m1 mWifiInfo; date -u'; date -u
adb -s $S root && adb -s $S wait-for-device && adb -s $S shell id -u   # expect 0
adb -s $S unroot; adb -s $S wait-for-device
```

Expect `Echo Show 5 (2nd Generation)`, `18.1-…-cronos`, SDK `30`, Wi-Fi `Supplicant state:
COMPLETED`, and a UTC clock within a few seconds of the host's. If `adb root` says "adbd cannot run
as root", Rooted debugging is off: ask the owner to turn it on.

## 2. Time zone and clock

```sh
adb -s $S shell "service call alarm 3 s16 $TZ"   # IAlarmManager.setTimeZone; works as shell on API 30
adb -s $S shell 'settings put global auto_time 1; settings put global auto_time_zone 0'
adb -s $S shell 'getprop persist.sys.timezone; date'
```

A fresh flash defaults to `Pacific/Midway` (UTC−11). There's no cell radio, so automatic time zone
can't work: turn it off so the zone stays as set. Automatic time (NTP) stays on.

## 3. Display: no night tint

LineageOS's LiveDisplay ships in Auto mode, which turns the screen yellow (4800 K) at "night". Shell
lacks `lineageos.permission.WRITE_SETTINGS`, so this needs root:

```sh
adb -s $S root && adb -s $S wait-for-device
adb -s $S shell 'content insert --uri content://lineagesettings/system --bind name:s:display_temperature_mode --bind value:s:0
  content insert --uri content://lineagesettings/system --bind name:s:display_auto_outdoor_mode --bind value:s:0'
adb -s $S unroot; adb -s $S wait-for-device
adb -s $S shell dumpsys lineagelivedisplay | grep -E 'mMode=|mColorTemperature='   # mMode=0, 6500
```

Undo: value `2` (Auto).

## 4. Install and configure the satellite app

Run this from a checkout of `origin/odysseus` (don't use an older branch: its `setup_device.sh`
and key layout lag behind):

```sh
scripts/odysseus/setup_device.sh "$APK" $S
adb -s $S shell dpm set-active-admin $P/com.msp1974.vacompanion.VACADeviceAdminReceiver
```

The script runs the privacy check, the install and steps 7–9, 12–17 and 24 of
[echo-show-5.md](echo-show-5.md#adb-commands-fresh-device) (what each does and how to undo it), plus
`REQUEST_INSTALL_PACKAGES` for Odysseus updates and landscape locked. The privacy-button key layout
loads at the next boot.

Check:

```sh
adb -s $S shell "dumpsys package $P | grep -E 'versionName|RECORD_AUDIO: granted';
  appops get $P REQUEST_INSTALL_PACKAGES; dumpsys deviceidle whitelist | grep $P;
  cmd package resolve-activity -c android.intent.category.HOME -a android.intent.action.MAIN | grep -m1 name=;
  pidof $P"
```

## 5. First-run screens on the Echo

Dump the UI and handle, in order:

| Screen text | Action |
|---|---|
| "Welcome to VACA", UUID field | Tap **Continue** with the default id. The name entered during phone setup replaces it. |
| "Allow Odysseus Echo to take pictures and record video?" | **Deny** |
| "Allow Odysseus Echo to access photos and media…?" | **Deny** |
| "Set up with your phone" + QR + "Closes in m:ss" | Done here. Leave it for the owner (step 10). |

```sh
adb -s $S shell 'uiautomator dump /sdcard/u.xml >/dev/null; cat /sdcard/u.xml; rm /sdcard/u.xml' \
  | grep -oE '<node[^>]*text="[^"]+"[^>]*bounds="[^"]+"'
```

## 6. Tailscale (for use away from home)

Why, and the privacy notes: [tailscale.md](tailscale.md). Install the official GitHub release. Fetch the latest version and its sha256 from the API, and
verify the download against it:

```sh
curl -s https://api.github.com/repos/tailscale/tailscale-android/releases/latest \
  | python3 -c "import sys,json;r=json.load(sys.stdin);[print(a['browser_download_url'],a['digest']) for a in r['assets'] if 'universal' in a['name']]"
curl -sL -o tailscale.apk <url>; sha256sum tailscale.apk     # must equal the digest
adb -s $S install tailscale.apk
adb -s $S shell dumpsys deviceidle whitelist +com.tailscale.ipn
adb -s $S shell monkey -p com.tailscale.ipn -c android.intent.category.LAUNCHER 1
```

- A **"Connection request"** (VPN) dialog appears: dump the UI and tap **OK**. Check with
  `appops get com.tailscale.ipn ACTIVATE_VPN` → `allow`.
- The app then opens a browser with a login QR. Tell the owner to scan it with their phone and approve
  the device. Wait until `adb -s $S shell ip -br addr | grep tun` shows a `100.x` address.
- Then make it the always-on VPN, without lockdown (the LAN keeps working if Tailscale is down):

```sh
adb -s $S shell 'settings put secure always_on_vpn_app com.tailscale.ipn; settings put secure always_on_vpn_lockdown 0'
adb -s $S shell am force-stop org.lineageos.jelly        # close the login browser
```

Tell the owner that the Tailscale Android app always uploads connection-metadata logs (no traffic)
to `log.tailscale.com` ([tailscale.md](tailscale.md#privacy)).

## 7. Re-lock landscape (the login browser undoes it)

Opening the browser for the Tailscale login turned auto-rotate back on. This ROM declares an
accelerometer it doesn't have, so the screen then sticks in portrait. Lock it again and check:

```sh
adb -s $S shell 'settings put system accelerometer_rotation 0; settings put system user_rotation 0'
adb -s $S shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
adb -s $S shell "dumpsys window | grep -m1 'mRotation='"     # mRotation=0
```

## 8. Smoke test (release build)

Release builds are minified, and a missing JNI keep rule only crashes there ([build.md](build.md#build)). Watch for about 2 minutes:

```sh
p1=$(adb -s $S shell pidof $P); sleep 120; p2=$(adb -s $S shell pidof $P); echo "$p1 -> $p2"   # same pid
adb -s $S logcat -d | grep -E 'AppExceptionHandler|Fatal signal|F/DEBUG'                    # nothing
```

## 9. Reboot (ask the owner first)

The reboot loads the privacy-button key layout, and it proves that everything comes back at boot.

```sh
adb -s $S reboot; adb -s $S wait-for-device; sleep 60
adb -s $S shell "dumpsys input | grep -A12 gpio-privacy-button | grep KeyLayoutFile;  # …/gpio-privacy-button.kl
  pidof $P; ip -br addr | grep tun; settings get system accelerometer_rotation;
  getprop persist.sys.timezone; dumpsys lineagelivedisplay | grep mMode="
scripts/odysseus/verify_device.sh --no-button --root $S
```

## 10. Hand-off to the owner

Report the installed `versionName`, the Tailscale `100.x` address, and the results of steps 8–9.
Then list what's left for the owner:

1. **Phone setup:** scan the "Set up with your phone" QR on the Echo ([setup.md](setup.md)). The
   setup server stops after 10 minutes. To show the QR again, restart the app
   (`adb shell am force-stop $P; adb shell am start -n $P/com.msp1974.vacompanion.MainActivity`).
   The phone must be on the same Wi-Fi or on Tailscale.
2. **Music Assistant** (if this Echo plays music away from home): add the Echo's `100.x` address
   to MA's Sendspin manual IP addresses ([tailscale.md](tailscale.md#music-assistant-sendspin-over-tailscale)).
3. **Tailnet ACL** (if the tailnet restricts traffic): allow the HA host to reach the Echo on
   tcp:10800 (Wyoming), and MA to reach it on 8928 (Sendspin).
