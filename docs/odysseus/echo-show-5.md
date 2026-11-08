# Echo Show 5 (2nd gen): device facts, setup and checks

What the device is, how it is set up, and how to check it. Tests of older builds and how
the fork got here: [history.md](history.md). A new Echo, start to finish:
[new-echo-runbook.md](new-echo-runbook.md).

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
| Mic-mute button | Shows up as `gpio-privacy-state` (`SW_MUTE_DEVICE`, currently unmuted) and `gpio-privacy-button`. Muting doesn't cut the microphone (owner, 2026-11-06). The button is the record button: see [Privacy button](#privacy-button-records-or-mutes-never-sleeps). |
| Camera shutter | `SW_CAMERA_LENS_COVER` is active (shutter closed). Camera is not used. |
| Google services | None (no GMS / Play Store). |
| Root | `adb root` works ("Rooted debugging" in developer options, enabled by the owner). The Magisk app is installed but not set up (no `su`). Nothing below needs root except reading `/proc/asound` while debugging. |

## Mic measurements

Clips went through the normal Android capture path (the one the satellite app uses), at
16 kHz mono, and were deleted after analysis (how they were made: [history.md](history.md)).
Levels are in dBFS: the floor is the 10th percentile of 50 ms frame RMS, and "loud" is the
95th percentile. Transcripts come from local faster-whisper `small.en` (int8, CPU).

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

## Microphone processing

The Echo's audio front-end already does noise reduction and gain control (see Mic
measurements). Upstream's WebRTC noise suppression, gain control and high-pass filter on top of
it distort speech, so the fork turns the app's processing off by default and sends the
front-end's audio as it is (`odysseus/MicProcessing.kt`; why: [history.md](history.md)).

- Setting: Settings → Experimental → Voice assistant → **App microphone processing** (stored on
  the device, applies at once, also to the wake word engine). Settings → Audio → Audio
  Capabilities then shows gain control and noise suppression as "Off (device's own)".
- The `mic_gain` setting, the minimum microphone level and wake sound suppression still apply.
- To compare, save Home Assistant's pipeline audio (`assist_pipeline: debug_recording_dir:
  /share/assist_pipeline` in `configuration.yaml`) and listen to a turn with the setting on and
  one with it off.
- Files: `odysseus/MicProcessing.kt`; hooks in `audio/MicrophoneInput.kt` (`readShort`),
  `ui/layouts/config/ExperimentalConfig.kt` (the setting), `ui/layouts/config/AudioConfig.kt`
  (the labels).

## adb commands (fresh device)

Setting up another new Echo? Follow [new-echo-runbook.md](new-echo-runbook.md): the steps below in order, with checks, for an agent.
This table is the background: what each step does and how to undo it.

If more than one device is attached, use `adb -s <serial>`. Everything below can be undone with adb (undo column). Steps 7–9, 12–17 and 24 are
scripted in `scripts/odysseus/setup_device.sh`. `P=com.msp1974.vacompanion.odysseus`.

| # | Command | Why | Undo |
|---|---|---|---|
| 1 | `adb devices -l`; `adb shell getprop` (model, device, lineage version, sdk, abi, audio props) | identify device and build | — |
| 2 | `adb shell dumpsys media.audio_policy`; `adb shell cat /vendor/etc/audio_policy_configuration.xml` | input/output devices and sample rates (mic: 16 kHz only) | — |
| 3 | `adb shell getevent -pl`; `adb shell 'for e in /dev/input/event*; do …name; done'` | find the mic-mute switch and buttons | — |
| 4 | `adb shell dumpsys wifi`, `ip addr show wlan0`, `wm size`, `wm density`, `dumpsys battery`, `dumpsys meminfo`, `cat /proc/cpuinfo` | Wi-Fi, screen, power, memory | — |
| 5 | `adb shell pm list packages`, `dumpsys package com.android.webview` | installed apps, WebView version, no GMS | — |
| 6 | `adb root` (needs "Rooted debugging" in Developer options) | read `/proc/asound/pcm` and `tinymix` for codec details; read the app's SharedPreferences while debugging | `adb unroot` |
| 7 | `adb shell settings put global stay_on_while_plugged_in 7` | keep the screen on while powered (was `0`) | `… 0` |
| 8 | `adb shell locksettings set-disabled true` | no lock screen on a wall display (was `false`; there is no PIN) | `… false` |
| 9 | `adb shell settings put secure screensaver_enabled 0` | no Daydream on top of the dashboard | `… 1` |
| 10–11 | (mic and music test clips, only for the measurements above; see [history.md](history.md)) | — | — |
| 12 | `python3 scripts/odysseus/check_apk_privacy.py <apk>`; `adb install -r <apk>` | satellite app (this fork) | `adb uninstall $P` |
| 13 | `adb shell pm grant $P android.permission.RECORD_AUDIO` | microphone for the wake word | `pm revoke …` |
| 14 | `adb shell appops set $P WRITE_SETTINGS allow` | VACA controls brightness and screen timeout | `… default` |
| 15 | `adb shell cmd notification allow_dnd $P` | VACA's DND control | `cmd notification disallow_dnd $P` |
| 16 | `adb shell dumpsys deviceidle whitelist +$P`; `appops set $P RUN_IN_BACKGROUND allow`; `appops set $P RUN_ANY_IN_BACKGROUND allow` | exempt from battery optimisation and Doze so the wake word keeps running | `dumpsys deviceidle whitelist -$P` |
| 17 | `adb shell cmd package set-home-activity $P/com.msp1974.vacompanion.MainActivity` | the satellite is the launcher, so it **starts at boot** and is the kiosk screen (VACA has no boot receiver; starting as home is how it autostarts) | `cmd package set-home-activity com.android.launcher3/.uioverrides.QuickstepLauncher` |
| 18 | `adb shell dpm set-active-admin $P/com.msp1974.vacompanion.VACADeviceAdminReceiver` | device admin, so VACA can turn the screen off (its onboarding asks for it; don't tap through that screen blind, it has an "Uninstall app" button) | `dpm remove-active-admin …` |
| 19 | Onboarding on the device: keep the default UUID, **Continue**; deny camera | — | — |
| 20 | In HA: Settings → Devices → Add integration → View Assist Companion App, host = device IP (or `127.0.0.1` with the USB tunnel, #23), port `10800`; then on the device's HA entities: Assistant = the Odysseus pipeline, Wake word engine = `microwakeword`, Wake word, Wake word sound = None | pair the satellite | delete the config entry |
| 21 | Clock (only when there's no network for NTP): `adb root`; ``adb shell "date -u $(date -u +%m%d%H%M%Y.%S)"``; `adb unroot` | the clock had drifted 1.7 days with no network; HA auth and TLS need a correct clock | automatic time takes over on network |
| 22 | **Silent testing** (owner can't have sound in the room): `cmd media_session volume --stream {1,2,3,5} --set 0`; `cmd notification set_dnd none`; `appops set $P PLAY_AUDIO deny`; `settings put system {sound_effects_enabled,haptic_feedback_enabled,dtmf_tone,lockscreen_sounds_enabled} 0`; in HA: Do not disturb = on, voice/music/alarm volume = 0 | no sound at all: the app op mutes every player of the app, even if HA pushes volumes; DND silences the rest | `appops set $P PLAY_AUDIO allow`; `cmd notification set_dnd off`; volumes back; those settings back to `1` |
| 23 | USB tunnel to a test HA on the dev machine (no LAN exposure): `adb reverse tcp:18123 tcp:18123` (device → HA at `http://127.0.0.1:18123`); `adb forward tcp:10800 tcp:10800` (HA → satellite at `127.0.0.1:10800`) | end-to-end testing without opening ports; redo after every `adb root`/`unroot` or USB reconnect | `adb reverse --remove-all`; `adb forward --remove-all` |
| 24 | `adb root`; install `scripts/odysseus/gpio-privacy-button.kl` in `/data/system/devices/keylayout/` (done by `setup_device.sh`); reboot | privacy button = `MEDIA_RECORD`: it records and never puts the device to sleep ([Privacy button](#privacy-button-records-or-mutes-never-sleeps)) | `rm` the file, reboot |

Not granted on purpose: `CAMERA` (motion detection; the shutter is closed anyway) and
location (only needed for VACA's BLE proxy).

Still to do:

- Tailscale for use away from home: [tailscale.md](tailscale.md).
- Check that the foreground service survives a reboot. A reboot needs the owner's OK.

## Wake word, wake sound, feedback

- **Wake word:** "Hey Athena", bundled ([wake-word.md](wake-word.md)). In HA set the device's wake
  word threshold to **9.2** (a cutoff of 0.92); the default of 6 gives about 1.5 false wakes per
  hour. The phone setup sets it ([setup.md](setup.md)).
- **Wake sound:** `select.<device>_wake_word_sound` = **Ding** in HA. The app must be allowed to
  play audio (`appops set $P PLAY_AUDIO allow`, which the silent test setup, #22, denies), and
  VACA's do-not-disturb must be off. Use the device volume to keep it quiet.
- **On screen:** the listening indicator ([turn-card.md](turn-card.md#listening-indicator)).

## Privacy button: records (or mutes), never sleeps

On this LineageOS build, the microphone privacy button is two input devices:

| Device | Sends | Effect |
|---|---|---|
| `gpio-privacy-state` | `SW_MUTE_DEVICE` | Android sets its microphone-mute flag (the HAL logs `setMicMute 0 => 1`). **The microphone keeps working**: the wake word still answers while "muted" (owner, 2026-11-06). |
| `gpio-privacy-button` | `KEY_POWER` (scancode 116) | upstream: `Going to sleep due to power_button`, the screen turns off |

`scripts/odysseus/gpio-privacy-button.kl` maps scancode 116 to `MEDIA_RECORD` (flag `WAKE`), so
the button never puts the device to sleep and the app can catch it. It goes in
`/data/system/devices/keylayout/` (on `/data`, not `/system`) and is loaded at boot.
`scripts/odysseus/setup_device.sh` installs it (needs `adb root`, i.e. Developer options →
Rooted debugging) and says whether it is loaded. By hand:

```sh
adb root                                   # root is needed to write /data/system
adb push scripts/odysseus/gpio-privacy-button.kl /data/local/tmp/
adb shell 'mkdir -p /data/system/devices/keylayout \
  && cp /data/local/tmp/gpio-privacy-button.kl /data/system/devices/keylayout/ \
  && chown -R system:system /data/system/devices && chmod 755 /data/system/devices /data/system/devices/keylayout \
  && chmod 644 /data/system/devices/keylayout/gpio-privacy-button.kl \
  && restorecon -R /data/system/devices; rm /data/local/tmp/gpio-privacy-button.kl'
adb unroot
adb reboot                                 # ask the owner first; the layout loads at boot
# Check: dumpsys input shows the new KeyLayoutFile for gpio-privacy-button
adb shell dumpsys input | grep -A12 gpio-privacy-button | grep KeyLayoutFile
# Undo: adb root; adb shell rm /data/system/devices/keylayout/gpio-privacy-button.kl; adb reboot
```

What the button does is the setting Menu → Odysseus → Recording → **Privacy button**:

- **Record** (default): press = start or stop a recording, hold 1 s = voice note
  ([recording.md](recording.md#the-privacy-button)). The app ignores Android's mic-mute flag:
  no "Microphone off", recordings aren't refused or stopped by it. **To mute, use "Mic Mute" in
  the quick actions sheet** (or Home Assistant's mute); that still mutes and stops recordings.
- **Mute** (upstream behaviour, without the sleep): the app swallows the key, and the switch
  mutes. When the microphone is muted, the app shows a red "Microphone off" pill at the bottom
  left, driven by `AudioManager.ACTION_MICROPHONE_MUTE_CHANGED`, and the listening overlay is
  hidden. The switch is a toggle: if "Microphone off" shows right after choosing Mute (an odd
  number of presses in Record mode), press the button once.

With "Recording" off, the button mutes whatever the setting says. On devices without the button
(no input device named `gpio-privacy-button` or `gpio-privacy-state`, e.g. tablets) the setting
does nothing and is greyed out.

Why the app can't stop the mute itself: on Android 11 the microphone is muted when the switch
*or* an app *or* a restriction says so (`AudioService.setMicrophoneMuteFromSwitch`), so
`AudioManager.setMicrophoneMute(false)` can't clear the switch's mute. It doesn't need to: on
this device the flag doesn't silence our capture. The red light is probably wired to the switch
itself; check below.

### Owner's checks on the Echo

1. **Key events.** With the layout loaded (check above), run
   `adb shell getevent -lt /dev/input/eventN` for `gpio-privacy-button` (find N with
   `adb shell getevent -il | grep -B1 -A3 gpio-privacy`), then press once and hold once for 2 s.
   Look for:
   - `EV_KEY KEY_POWER DOWN` and `EV_KEY KEY_POWER UP` as **two lines with different
     timestamps**, about 2 s apart for the hold. Then press and hold both work with the screen on.
     If DOWN and UP have the same timestamp (or only one line comes), the button reports no hold:
     every press is a press, and voice notes come from the on-screen buttons.
   - on `gpio-privacy-state`: `EV_SW SW_MUTE_DEVICE 00000001` then `00000000` on the next press.
     That's the fallback the app uses with the screen off.
2. Repeat 1 with the screen **off** (Settings → Screen off, or let the screen timeout turn it
   off) to see whether the button reports at all then. Android 11 doesn't pass keys to the app
   while the display is off; the switch should still change.
3. `adb logcat | grep -E "Privacy button|Recorder"`. Screen on: press → `Privacy button: press
   (key up)` and a REC pill; press again → it stops. Hold 1 s → `Privacy button: hold (hold)`
   and a NOTE pill; letting go doesn't stop it; press → stops.
4. Screen off (really off, not the black screensaver): press → `Privacy button: press (switch)`,
   the screen wakes and REC shows. Press again → it stops. Hold → also only a press (known
   limit).
5. The device never goes to sleep on a press (no `Going to sleep due to power_button` in
   logcat), in either mode.
6. The red privacy light: note whether it toggles with each press in Record mode. If it does, it
   is wired to the switch and the app can't stop it (it then reads "on" during every other
   recording, and means nothing in Record mode).
7. "Hey Athena" still answers after an odd number of presses (switch "muted"), with and without
   a recording running.
8. Quick actions → Mic Mute: the wake word stops, a running recording stops, and Record is
   refused with "The microphone is muted: not recording". Unmute: it works again.
9. Setting → Mute: a press shows "Microphone off" (and no recording starts); press again: gone.
10. Another app in front (e.g. Settings): a press starts a recording (switch path; the pill shows
    once the app is back in front); a hold is only a press.

## Checking a device

`scripts/odysseus/verify_device.sh [--no-button] [--root] [serial]` checks an Echo over adb and
changes nothing on it. It prints:

- the app's version (and whether the build is debuggable), and whether it is running;
- the Odysseus settings: every `odysseus_*` SharedPreferences file (read with `run-as` on a
  debuggable build, else only with `--root`, which uses `adb root` like `setup_device.sh` and
  `adb unroot` afterwards; values of keys named like token, secret, password or key are shown as
  `<hidden>`), and the device id the hub knows it by (`uuid`, for the hub's
  `scripts/echo_verify.py --device-id`);
- the privacy button's key layout (`dumpsys input`: `KeyLayoutFile` of `gpio-privacy-button`);
- a guided `getevent` capture: "press once now…", then "hold for 2 s…" (8 s each,
  `VERIFY_WAIT` to change). It reports whether DOWN and UP came, how long the hold was as the
  kernel saw it (≥ 1 s: holds work), and whether `gpio-privacy-state` sent `SW_MUTE_DEVICE`.
  The app reacts to the presses as usual (in Record mode a recording and a voice note start):
  stop them afterwards. `--no-button` skips it;
- the last 60 log lines matching `Recorder|Kiosk|Today|Update|Announce|Privacy button`;
- a summary, one line per check, `PASS` or `CHECK` (look at it): app installed and running,
  linked to Odysseus, key layout loaded, press and hold seen, the mute switch reports, no
  `Going to sleep due to power_button`, the app logged the button, hello sent, the last
  announcements feed outcome `OK`, no update or recorder failure in the log.

It is the device half of the check; the hub half is the hub's `scripts/echo_verify.py`
(odysseus-expanded `docs/fork/voice-assistant.md#checking-a-server-for-the-echos`).
