# Echo Show 5 (2nd gen): device check and setup

## Device (checked 2026-11-03)

| Item | Value |
|---|---|
| Model / codename | Amazon Echo Show 5 (2nd Generation), `cronos` |
| SoC / ABI | MediaTek MT8163, 4× Cortex-A53, **32-bit userspace** (`armeabi-v7a`) |
| RAM / free storage | 1 GB (≈330 MB free at idle) / 3.5 GB |
| OS | LineageOS 18.1 UNOFFICIAL (build 2026-11-01), Android 11 (API 30), `userdebug`, kernel 4.9.337, security patch 2024-02-05 |
| WebView | 146.0.7680.153 |
| Audio HAL | `ro.hardware.audio.primary=amazon_wrapper`: Amazon's ASP front-end on top of the MediaTek HAL. Capture is a 4-mic array (24-bit, 16 kHz) plus a speaker echo reference, mixed down to 1 channel 16 kHz. Codecs: TI TLV320AIC3101 (mics), Maxim MAX98396 (speaker amp). The only input profile is 16 kHz, mono or stereo. |
| Speaker | Works (`AUDIO_DEVICE_OUT_SPEAKER`, 48 kHz stereo). |
| Screen / touch | 960×480 at 195 dpi, Goodix touch. Both work. |
| Wi-Fi | Works: 5 GHz, RSSI −22 dBm, 433 Mbps at the test spot. |
| Mic-mute button | Shows up as `gpio-privacy-state` (`SW_MUTE_DEVICE`, currently unmuted) and `gpio-privacy-button`. Behaviour while muted was **not tested** (owner skipped it). |
| Camera shutter | `SW_CAMERA_LENS_COVER` is active (shutter closed). Camera is not used. |
| Google services | None (no GMS / Play Store). |
| Root | `adb root` works ("Rooted debugging" in developer options, enabled by the owner). The Magisk app is installed but not set up (no `su`). Nothing below needs root except reading `/proc/asound` while debugging. |

## Mic measurements

Clips were recorded through the normal Android capture path (the same path the satellite
app uses) with LineageOS Recorder in high-quality WAV mode, then converted to 16 kHz mono.
They were analysed on the dev machine in a temp folder outside the repo and deleted
afterwards. Levels are in dBFS: the floor is the 10th percentile of 50 ms frame RMS, and
"loud" is the 95th percentile. Transcripts come from local faster-whisper `small.en`
(int8, CPU).

| Clip | Floor | Loud | SNR | Whisper |
|---|---|---|---|---|
| Room with TV/music on, nobody speaking | −46 | −17 | — | (picked up the room audio) |
| Quiet room, owner at 1 m, normal voice | −66 | −20 | 47 dB | word-perfect |
| Quiet room, owner at 3 m, normal voice | −61 | −20 | 41 dB | word-perfect (only "the plumber" → "a plumber") |
| Device playing music at volume 10/15, owner at ~1.5–2 m | −73 | −32 | — | **fails** (hallucinated text, `medium.en` too) |

Findings:

- **Far-field speech works.** The front-end levels speech (3 m is as loud as 1 m) and
  whisper got every word at 3 m.
- **Talking over the device's own loud playback does not work.** The Amazon AEC/NR is
  active (in the logs: `ASP/EchoReference*`, the `AUDIOALG` Tx NR/AGC). It pushes the
  music down to about −38 dBFS but also squashes the talker. Speech arrived 12 dB lower
  and STT failed. So the satellite has to duck or pause media when the wake word fires.
  VACA does this, but the wake word itself has to be heard over the music first. Wake-word
  detection over playback at lower volumes still has to be measured with the real wake
  word engine (Phase 3).
- Android 11 delivers **silence** to a capture that starts while the app is in the
  background. A recording or a satellite that is started from the background records
  nothing. VACA avoids this because it starts as the home app with a foreground service.

## Satellite app: View Assist Companion (VACA)

Chosen: **VACA 0.14.0**
(<https://github.com/msp1974/ViewAssist_Companion_App/releases/tag/v0.14.0>,
`vaca-0.14.0.apk`, sha256 `2cc9d8e2b61c1e54b96fd766dbcf90caa3bf03ec36224e109ce63fafc5a3525f`,
package `com.msp1974.vacompanion`, versionCode 6918). It needs the VACA integration
(HACS) on Home Assistant **2026.9 or newer**.

Why VACA and not the HA Companion app (minimal 2026.11.1):

- VACA ships `armeabi-v7a` libraries. It has its own on-device wake word (microWakeWord,
  openWakeWord) with **custom models served from HA**, so the owner's "Hey Athena" works.
  It also has a kiosk dashboard, media ducking, and is known to work on cronos.
- The HA Companion app's wake word is experimental and has only Okay Nabu, Hey Jarvis and
  Hey Mycroft. There is an open bug where it streams audio at ~1/7 real time on low-end
  tablets ([home-assistant/android#7683](https://github.com/home-assistant/android/issues/7683));
  this SoC is weaker than the one in that report.

> [!WARNING]
> **Privacy: VACA has always-on Google Firebase Analytics and Crashlytics** (checked in
> its source, `utils/Logger.kt`, `MainActivity.kt`, `settings/Settings.kt`). There is no
> opt-out. It sends the device name, WebView version, an app UUID, app events, crash
> traces and a breadcrumb for every settings change (old and new value). Those breadcrumbs
> go out with every crash or non-fatal error report. They include the dashboard path, mute
> and screen state, the last-motion time, and the **RTSP username and password** (so
> never turn on VACA's camera stream). The HA URL and access token are not on that list,
> and no audio or STT text is sent. The owner chose VACA and blocks these hosts for this
> device on the
> router/DNS **before VACA is first started**:
> `app-measurement.com`, `*.app-measurement.com`, `firebaseinstallations.googleapis.com`,
> `firebaselogging-pa.googleapis.com`, `firebaselogging.googleapis.com`,
> `crashlyticsreports-pa.googleapis.com`, `firebase-settings.crashlytics.com`,
> `settings.crashlytics.com`, `firebasecrashlyticssymbols.googleapis.com`,
> `firebaseremoteconfig.googleapis.com`.
> Check: after the first start, the DNS log shows these hosts only as blocked.
> VACA's own update check (GitHub) is not telemetry and can stay allowed.

## adb commands (fresh device)

If more than one device is attached, use `adb -s <serial>`;
is attached. Everything below can be undone with adb (undo column).

| # | Command | Why | Undo |
|---|---|---|---|
| 1 | `adb devices -l`; `adb shell getprop` (model, device, lineage version, sdk, abi, audio props) | identify device and build | — |
| 2 | `adb shell dumpsys media.audio_policy`; `adb shell cat /vendor/etc/audio_policy_configuration.xml` | input/output devices and sample rates (mic: 16 kHz only) | — |
| 3 | `adb shell getevent -pl`; `adb shell 'for e in /dev/input/event*; do …name; done'` | find the mic-mute switch and buttons | — |
| 4 | `adb shell dumpsys wifi`, `ip addr show wlan0`, `wm size`, `wm density`, `dumpsys battery`, `dumpsys meminfo`, `cat /proc/cpuinfo` | Wi-Fi, screen, power, memory | — |
| 5 | `adb shell pm list packages`, `dumpsys package com.android.webview` | installed apps, WebView version, no GMS | — |
| 6 | `adb root` (needs "Rooted debugging" in Developer options) | read `/proc/asound/pcm` and `tinymix` for codec details | `adb unroot` |
| 7 | `adb shell settings put global stay_on_while_plugged_in 7` | keep the screen on while powered (was `0`) | `… 0` |
| 8 | `adb shell locksettings set-disabled true` | no lock screen on a wall display (was `false`; there is no PIN) | `… false` |
| 9 | `adb shell settings put secure screensaver_enabled 0` | no Daydream on top of the dashboard | `… 1` |
| 10 | Mic tests: `adb shell pm grant org.lineageos.recorder android.permission.READ_PHONE_STATE`; Recorder settings → "Record in high quality" on; `am start -n org.lineageos.recorder/.RecorderActivity`; `input tap 480 342` (record/stop); `adb pull "/sdcard/Music/Sound records/…wav"` then `adb shell rm …` | 16 kHz mic test clips through the normal capture path | revoke the permission |
| 11 | Music test: `adb push testmusic.ogg /sdcard/Music/` (public-domain US Coast Guard Band recording from Wikimedia Commons); `cmd media_session volume --stream 3 --set 10`; start recording **in the foreground first**, then `am start -a android.intent.action.VIEW -d file:///sdcard/Music/testmusic.ogg -t audio/ogg`; stop with `am startservice -n org.lineageos.recorder/.service.SoundRecorderService -a org.lineageos.recorder.service.STOP` | talking over playback | `adb shell rm /sdcard/Music/testmusic.ogg` |
| 12 | `adb install vaca-0.14.0.apk` (verify the sha256 above first) | satellite app | `adb uninstall com.msp1974.vacompanion` |
| 13 | `adb shell pm grant com.msp1974.vacompanion android.permission.RECORD_AUDIO` | microphone for the wake word | `pm revoke …` |
| 14 | `adb shell appops set com.msp1974.vacompanion WRITE_SETTINGS allow` | VACA controls brightness and screen timeout | `… default` |
| 15 | `adb shell cmd notification allow_dnd com.msp1974.vacompanion` | VACA's DND control | `cmd notification disallow_dnd …` |
| 16 | `adb shell dumpsys deviceidle whitelist +com.msp1974.vacompanion`; `appops set … RUN_IN_BACKGROUND allow`; `appops set … RUN_ANY_IN_BACKGROUND allow` | exempt from battery optimisation and Doze so the wake word keeps running | `dumpsys deviceidle whitelist -com.msp1974.vacompanion` |
| 17 | `adb shell cmd package set-home-activity com.msp1974.vacompanion/.MainActivity` | VACA is the launcher, so it **starts at boot** and is the kiosk screen (VACA has no boot receiver; starting as home is how it autostarts) | `cmd package set-home-activity com.android.launcher3/.uioverrides.QuickstepLauncher` |

Not granted on purpose: `CAMERA` (motion detection; the shutter is closed anyway) and
location (only needed for VACA's BLE proxy).

Not done yet (needs Home Assistant and the owner's DNS block):

- First start of VACA (`adb shell am start -n com.msp1974.vacompanion/.MainActivity`),
  pairing through HA discovery, and choosing the pipeline and wake word.
- Training and installing the custom "Hey Athena" wake-word model.
- Checking that the foreground service survives a reboot. A reboot needs the owner's OK.
