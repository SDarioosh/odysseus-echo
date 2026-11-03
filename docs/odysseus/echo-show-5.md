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

## Satellite app: this fork of View Assist Companion (VACA)

VACA was chosen over the HA Companion app (minimal 2026.11.1):

- VACA ships `armeabi-v7a` libraries. It has its own on-device wake word (microWakeWord,
  openWakeWord) with **custom models served from HA** (for "Hey Athena"), a kiosk dashboard
  and media ducking, and is known to work on cronos.
- The HA Companion app's wake word is experimental and has only Okay Nabu, Hey Jarvis and
  Hey Mycroft. There is an open bug where it streams audio at ~1/7 real time on low-end
  tablets ([home-assistant/android#7683](https://github.com/home-assistant/android/issues/7683));
  this SoC is weaker than the one in that report.

Official VACA links Firebase Analytics, Crashlytics and Google ML Kit (see
[ODYSSEUS.md](../../ODYSSEUS.md)). Official VACA only starts Firebase when Google Play services
are present, and this device has none. This fork removes them anyway, so nothing depends on a
DNS block. The official app (0.14.0, GitHub release, sha256
`2cc9d8e2b61c1e54b96fd766dbcf90caa3bf03ec36224e109ce63fafc5a3525f`) was installed during the
device check, never started, and then uninstalled.

The official 0.14.0 release also lost the first ~0.4 s after the wake word. HA's VAD then
never saw speech start, the turn timed out, and the next request was dropped (HA logged its
audio as "Unexpected event from satellite"). Upstream `dev` fixed this, so the fork is based
on it (`0.14.0+odysseus.2`). Measured on the device after the fix (test HA, faster-whisper
`small-int8` on CPU, owner at 1 m): "what time is it" was answered by HA locally 1.85 s after
the owner stopped speaking; a question routed to the (mock) conversation agent took 1.67 s.

## adb commands (fresh device)

If more than one device is attached, use `adb -s <serial>`;
is attached. Everything below can be undone with adb (undo column). Steps 12–20 are
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
| 10 | Mic tests: `adb shell pm grant org.lineageos.recorder android.permission.READ_PHONE_STATE`; Recorder settings → "Record in high quality" on; `am start -n org.lineageos.recorder/.RecorderActivity`; `input tap 480 342` (record/stop); `adb pull "/sdcard/Music/Sound records/…wav"` then `adb shell rm …` | 16 kHz mic test clips through the normal capture path | revoke the permission |
| 11 | Music test: `adb push testmusic.ogg /sdcard/Music/` (public-domain US Coast Guard Band recording from Wikimedia Commons); `cmd media_session volume --stream 3 --set 10`; start recording **in the foreground first**, then `am start -a android.intent.action.VIEW -d file:///sdcard/Music/testmusic.ogg -t audio/ogg`; stop with `am startservice -n org.lineageos.recorder/.service.SoundRecorderService -a org.lineageos.recorder.service.STOP` | talking over playback | `adb shell rm /sdcard/Music/testmusic.ogg` |
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

Not granted on purpose: `CAMERA` (motion detection; the shutter is closed anyway) and
location (only needed for VACA's BLE proxy).

Still to do:

- Install the custom "Hey Athena" wake-word model (being trained; "Hey Jarvis" stands in).
- Tailscale for use away from home: [tailscale.md](tailscale.md).
- Check that the foreground service survives a reboot. A reboot needs the owner's OK.
- The device moved to another Wi-Fi network; the owner
  connected it on the device.

## "Hey Athena" wake word (0.14.0+odysseus.3)

The app bundles a custom microWakeWord model, `app/src/main/assets/microwakeword/wakeWords/hey_athena.{tflite,json}`.
It was trained locally with synthetic Piper voices (see `docs/odysseus/wake-word.md`).

**Threshold.** VACA's global "wake word threshold" setting overrides `probability_cutoff` in the JSON.
In HA, set the device's wake word threshold to **9.2** (that is 0.92) when you choose Hey Athena. The default of 6 (0.6) gives about 1.5 false wakes per hour.

| Threshold | Missed "Hey Athena" (held-out voices) | False wakes per hour (5.3 h dinner-party test set) |
|---|---|---|
| 0.94 | 6.9 % | 0 |
| 0.92 | 6.5 % | 0.19 |
| 0.78 | 3.6 % | 0.38 |
| 0.59 | 3.1 % | 1.5 |

**adb commands for the update:**

```sh
# Update in place. -r keeps the app's data, permissions, appops and pairing.
adb install -r odysseus-vaca-0.14.0+odysseus.3-debug.apk
# Check that the speaker is still blocked for the app after the update.
adb shell appops get com.msp1974.vacompanion.odysseus PLAY_AUDIO
# Bring the app back as the home screen.
adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
```

## Listening feedback (0.14.0+odysseus.4)

- **Visual:** the app draws its own overlay on top of the dashboard or the blank screen.
  - A blue bar along the bottom pulses with "Listening…", and pulses faster once you start speaking.
  - It turns violet with "Thinking…" while HA and Odysseus work, then green with "Answering…".
  - It needs no HA configuration.
- **Audio:** VACA's wake sound, set from HA: `select.<device>_wake_word_sound` = **Ding**.
  - The app must be allowed to play audio, and VACA's do-not-disturb must be off. Use the device volume to keep it quiet.
- **Barge-in:** saying "Hey Athena" while the device is still thinking or answering stops that turn and starts listening again.

```sh
# Allow the app to play the wake sound and answers again (the silent test setup had denied it).
adb shell appops set com.msp1974.vacompanion.odysseus PLAY_AUDIO allow
```

HA test-instance settings for this (made through the HA API, not adb):

| Entity | Value |
|---|---|
| `switch.<device>_do_not_disturb` | off |
| `select.<device>_wake_word_sound` | Ding |
| `number.<device>_wake_word_threshold` | 9.2 |

The app turns 9.2 into a cutoff of 0.91 (it rounds down). Owner choice: in the live test this caught all 7 wakes, with 1 false wake at 0.91.

**Follow-ups and "prefer handling commands locally".** When the conversation agent asks for a follow-up (`continue_conversation`), HA sends the next turn in that chat session *only* to that agent and skips local intents. This is `prepare_recognize_intent`, `_intent_agent_only`, in `assist_pipeline/default_pipeline.py`.
In the live test, a follow-up that Odysseus had asked for was interrupted, so a fresh "what time is it?" went to Odysseus instead of HA.
The Odysseus voice endpoint should therefore ask for a follow-up only when its answer really is a question.
