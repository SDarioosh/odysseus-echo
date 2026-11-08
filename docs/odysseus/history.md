# History: tests and findings on the Echo Show 5

What was tried on the Echo Show 5, by build, with what it showed. The current state is in
[echo-show-5.md](echo-show-5.md) and the feature docs; this file keeps the findings, not the
step-by-step logs.

## Device check and choice of the satellite app (2026-11-03)

- The satellite app is this fork of View Assist Companion (VACA), not the HA Companion app
  (minimal 2026.11.1). VACA ships `armeabi-v7a` libraries, has its own on-device wake word
  (microWakeWord, openWakeWord) with custom models served from HA, a kiosk dashboard and media
  ducking, and is known to work on `cronos`. The HA Companion app's wake word is experimental
  (only Okay Nabu, Hey Jarvis, Hey Mycroft) and has an open bug where it streams audio at ~1/7
  real time on low-end tablets
  ([home-assistant/android#7683](https://github.com/home-assistant/android/issues/7683)); this
  SoC is weaker than the one in that report.
- Official VACA links Firebase Analytics, Crashlytics and Google ML Kit. It starts Firebase only
  when Google Play services are present, and this device has none. The fork removes them anyway,
  so nothing depends on a DNS block ([ODYSSEUS.md](../../ODYSSEUS.md)). The official 0.14.0 APK
  (GitHub release, sha256 `2cc9d8e2b61c1e54b96fd766dbcf90caa3bf03ec36224e109ce63fafc5a3525f`) was
  installed during the check, never started, and uninstalled.
- Mic clips were recorded with LineageOS Recorder (high-quality WAV, `READ_PHONE_STATE` granted
  over adb), and with a public-domain music file playing at volume 10/15. The recording had to be
  started in the foreground first: Android 11 records silence when capture starts in the
  background. The results are in [echo-show-5.md](echo-show-5.md#mic-measurements).

## 0.14.0+odysseus.2: based on upstream `dev`

The official 0.14.0 release lost the first ~0.4 s after the wake word. HA's VAD then never saw
speech start, the turn timed out, and the next request was dropped (HA logged its audio as
"Unexpected event from satellite"). Upstream `dev` fixed this, so the fork is based on it.
Measured after the fix (test HA, faster-whisper `small-int8` on CPU, owner at 1 m): "what time
is it" was answered by HA 1.85 s after the owner stopped speaking; a question for the (mock)
conversation agent took 1.67 s.

## 0.14.0+odysseus.3: "Hey Athena"

The bundled model and its threshold table: [wake-word.md](wake-word.md). The update was an
`adb install -r` (keeps data, permissions, app ops and pairing); the `PLAY_AUDIO` app op set for
silent testing survived it.

## 0.14.0+odysseus.4: listening feedback and barge-in

- First version of the listening indicator and of barge-in
  ([turn-card.md](turn-card.md#listening-indicator), [turn-card.md](turn-card.md#barge-in)).
- Test HA settings: do-not-disturb off, wake sound Ding, threshold 9.2. The app then still
  truncated 9.2 to a cutoff of 0.91; it caught all 7 wakes, with 1 false wake. Later builds round
  to the nearest value ([wake-word.md](wake-word.md#threshold)).
- A barge-in during "Thinking…" left the satellite deaf until it reconnected. Cause, in HA:
  `homeassistant/components/wyoming/assist_satellite.py` clears its run flag on any run's
  `RUN_END`. Fixed in odysseus.5 (below).
- When the agent asks for a follow-up, HA sends the next turn only to that agent and skips local
  intents. A follow-up Odysseus had asked for was interrupted, so a fresh "what time is it?" went
  to Odysseus instead of HA ([follow-up.md](follow-up.md#home-assistants-own-continue-conversation)).

## 0.14.0+odysseus.5: barge-in waits for HA

The new turn starts only once HA has ended the interrupted run (`odysseus/HaRunGate.kt`); the
satellite no longer goes deaf.

## 0.14.0+odysseus.6: voice actions card

Tested against a stand-in for Odysseus's actions feed on the host (fictional actions), reached
over `adb reverse`. A turn started from HA's wake button showed the four fictional actions after
the turn ended: three added (to-do with due time, list line, event) and one completed (an Athena
item, struck through). Findings for driving the Echo over adb:

- The menu's two-finger "swipe up" can't be sent with `adb shell input` (one finger). It was
  injected with root `sendevent`. `adb root` restarts adbd, so every `adb reverse`/`forward`
  has to be added again.
- The screen is rotated 90° against the touch panel's coordinates.
- With the keyboard up, taps land on the wrong field: type the URL, press Tab (keyevent 61),
  then type the token.

## 0.14.0+odysseus.14: barge-in tells Odysseus

Before, an Odysseus answer that took longer than the 15 s the gate waits meant the new question
was dropped and the device went idle, and a wake word after 15 s of "Thinking…" wasn't a
barge-in at all (the old answer played). Since then the app also calls
`POST <base>/satellite/interrupt`. Reproduced and checked in the hub's sandbox (a real Home
Assistant and an emulator of this code): `docs/fork/sandbox.md#voice-barge-in` in
`SDarioosh/odysseus-expanded`.

## 0.14.0+odysseus.16: app microphone processing off

Upstream VACA (since 2026-10-30) runs WebRTC noise suppression, gain control and a high-pass
filter. On top of the Echo's own front-end it distorted speech: the owner was misheard at 1 m in
a quiet room, while unprocessed clips of the same microphone were word-perfect. The fork turned
it off by default ([echo-show-5.md](echo-show-5.md#microphone-processing)).

## 2026-11-06: the privacy switch doesn't cut the microphone

The owner found that the wake word still answers while the privacy switch is "muted". This made
the button free to become the record button ([recording.md](recording.md#the-privacy-button)).
