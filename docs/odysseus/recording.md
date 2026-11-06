# Recording conversations and voice notes for Athena

The Echo records conversations and voice notes and sends them to
[Athena](https://github.com/SDarioosh/Athena) through the owner's Odysseus hub. Athena then
transcribes and summarizes them like the T-Watch's recordings. The code is in
`app/src/main/java/com/msp1974/vacompanion/odysseus/record/`.

## Using it

- **Tap** the record button to start a recording. Tap again to stop it.
- **Hold** the record button for 1 s to start a voice note. You can let go after that; letting
  go doesn't stop the note. A tap or another hold stops it. A note stops by itself after 5
  minutes.
- **Hold during a recording**: the recording session ends and the note is recorded as its own
  session. When the note stops, a new recording session starts by itself, with no sound
  (unless a recording may not start then, e.g. the Echo was unlinked meanwhile: the reason is
  shown and nothing resumes).
- A recording stops by itself after 4 hours.
- A second tap within 1 s of the last one is ignored.
- Nothing starts while the microphone is muted (the quick actions "Mic Mute", Home Assistant's
  mute, or the privacy switch when it is set to Mute). The reason is shown on screen. Muting
  while recording stops the recording.
- The wake word ("Hey Athena") keeps working during recordings and voice notes. Both listen to
  the same microphone.

Where the button is:

- **Quick actions sheet**: the "Record" button (it reads "Stop" or "Stop note" while recording).
- **Clock page**: a small round button in the bottom right. Setting: "Record button on the
  clock" (on by default).
- **The red pill**: tapping it works the same as tapping the button, so it stops a recording.
- **The Echo Show's privacy button** (the microphone button on top): press and hold, like the
  on-screen button. See [The privacy button](#the-privacy-button).

While anything records:

- a red **REC 12:34** or **NOTE 0:42** pill shows at the top of every screen (dashboard, clock,
  connection screen, blank screen);
- the screen is woken and kept on;
- a short tone plays at the start, and two falling tones at the end. They don't play in
  do-not-disturb.

These devices have no vibration, so the sounds and the pill are the only feedback.

## Settings: Menu → Odysseus → Recording

| Setting | Default | What it does |
|---|---|---|
| Recording | on | Off hides the record buttons and refuses presses. Turning it off stops a running recording. |
| Record button on the clock | on | The small button on the clock page |
| Privacy button | Record | Record: the Echo Show's privacy button records (below). Mute: it only mutes, as before. Greyed out on devices without the button |
| *(status)* | | "3 chunks waiting (2.7 MB)", and why uploads wait (hub unreachable, relink needed) |
| Upload now | | Tries at once, skipping the retry wait. Also use it after relinking. |

The settings are stored on the device (SharedPreferences `odysseus_record`). They aren't sent
to Home Assistant.

## How it works

```
press/hold ──> RecorderController ──> RecorderStateMachine (pure)
                     │ effects: start/end session, sounds, refusal
                     v
           RecordingCapture ── SharedMicrophone.raw (before any processing)
                     │ ChunkAssembler: 30 s chunks, WAV 16 kHz mono PCM16
                     v
           ChunkQueue (files/odysseus/recordings/) ──> RecordingUploader ──> hub relay ──> Athena
```

- **RecorderController** is one object per process. The foreground service installs it
  (`service/ForegroundService.kt`, one hook line). That way it runs whether or not Home
  Assistant is connected, with the screen off and the activity in the background. The
  buttons and the pill only read `RecorderController.state` and call `press()`/`hold()`.
- **RecorderStateMachine** is plain Kotlin and holds the gestures, limits and debounce
  (`RecorderStateMachineTest`). The controller feeds it time since boot (`clockMs()`), not the
  wall clock, so a clock change can't stop a recording or stretch a note.
- **RecordingCapture** collects `SharedMicrophone.raw` undispatched into its own channel (as
  `audio/DetectionBuffer` does), so no frame is lost while the encoder works. See
  [shared-microphone.md](shared-microphone.md). The audio is raw: before the gate, WebRTC, the
  wake-sound suppressor and the gain. If no frames arrive for 5 s (the microphone gave up), it
  listens again.
- **ChunkAssembler** cuts 30 s chunks. A chunk's `timestamp` is the session start plus the
  audio before it (80 ms per frame), in ISO-8601 UTC with ms. If the microphone stops for a
  while, the timestamps after the gap come early by that much.
- **ChunkQueue** writes each chunk to app-private storage before it is uploaded. It writes the
  sidecar `NNNNNN.json` (session, sequence, kind, timestamp) first, then `NNNNNN.wav`, each
  under a temporary name and then renamed. When a session ends, an `end` file records its
  chunk count. A session folder is `<created ms>-<session id>`, with `session.json` inside.
  The queue survives restarts. When the app starts, any session left unfinished by a killed run
  is marked finished and sent as it is (a `sent` file keeps the highest sequence already
  delivered, so a session whose chunks all went still gets its `/end`). Files are synced before
  the rename; a sidecar that can't be read (power cut) is rebuilt from `session.json` and the
  file name.
- **Queue cap**: 500 MB. Past that, the oldest *finished* sessions are deleted first, and a
  warning shows on screen and in the settings. A session that is still recording is never
  deleted.

## Upload: contract C4

The audio goes only to the hub the Echo is linked to (Menu → Odysseus): the same origin as the
voice base URL (`https://<hub>/api/chat/voice/v1` → `https://<hub>`), with the same token.

- `POST <hub>/api/codex/athena/chunk`, multipart: `audio` (the WAV), `device` = `echo-<device
  id>` (the VACA device id, `config.uuid`), `session_id` (16 random lowercase hex digits),
  `sequence` (from 0), `timestamp`, `codec=wav`, and `kind=note` for voice notes only.
- `POST <hub>/api/codex/athena/sessions/<session_id>/end` after the session's last chunk.
- A 404 on `/api/codex/athena/*` is tried once on `/api/codex/sound/*` (an older hub). If
  that works, the older name is used until the app restarts.
- Chunks of a session still recording are uploaded as they come. Sessions go oldest first,
  and chunks by sequence.

| Answer | Class | What the device does |
|---|---|---|
| 200 with `"ok": true` (a duplicate counts) | ok | deletes the chunk |
| 5xx, 408, 429, network error, 200 without ok | retry | stops the pass and tries again after 1, 2, 5, 10, then every 15 min. New chunks don't cut the wait short; "Upload now" does |
| 401 / 403 | auth | stops uploads and shows **"Relink this Echo with Odysseus to upload recordings"**. They start again once the token changes (relink) or on "Upload now" |
| other 4xx | rejected | keeps the file as `NNNNNN.wav.rejected`, skips it, logs it |

The token needs the `athena:ingest` scope. Echo tokens made by the hub's `/echo/link` have it
(hub change H4). An Echo linked before H4 has a voice-only token and gets 403: relink it from
the phone (Menu → Odysseus → Set up with phone).

## The privacy button

On the Echo Show 5 (2nd gen) the microphone button on top is the record button (setting
**Privacy button = Record**, the default). Hardware details, the key layout and the owner's
checks: [echo-show-5.md](echo-show-5.md#privacy-button-records-or-mutes-never-sleeps).

- **Press**: start a recording, press again to stop. **Hold 1 s**: voice note. The same
  gestures and rules as the on-screen button.
- **The button no longer mutes.** Mute with "Mic Mute" in the quick actions sheet (or Home
  Assistant's mute): that still stops recordings and refuses new ones. The first time the
  button records, a message says so once.
- While the button records, Android's microphone-mute flag (which the button flips on every
  press) is ignored: no "Microphone off" pill, no refusal. On this device the flag doesn't cut
  the microphone, so the wake word and the recording keep working whatever it says.

How a press reaches the app (`odysseus/record/RecordButton.kt`, logic in
`RecordButtonGestures.kt`, plain Kotlin, `RecordButtonGesturesTest`):

1. **Key path** (press and hold). The key layout turns the button's `KEY_POWER` into
   `MEDIA_RECORD`. `MainActivity.dispatchKeyEvent` passes it to `RecordButton` (one hook). Down
   starts a 1 s timer; still down at 1 s → `hold()`; up before that → `press()`; the up after a
   hold does nothing; key repeats are ignored. Only keys from the input device
   `gpio-privacy-button` are taken; a `MEDIA_RECORD` from anything else passes through.
2. **Switch path** (press only), automatic. Every press also flips Android's mic-mute flag
   (`ACTION_MICROPHONE_MUTE_CHANGED`, a receiver registered process-wide from
   `RecorderController.install`). A change counts as a press unless a key event covers it: it
   waits 300 ms for a key down, and is ignored while a key is down or within 1.5 s of the last
   key event. So with working keys a press is counted once, and where keys don't arrive the
   switch still works.

When does which path work:

| Situation | Press | Hold (voice note) |
|---|---|---|
| Screen on, the app in front (normal: it is the home app) | key | key |
| Display off (Screen off from HA or the screen timeout) | switch; the recorder wakes the screen | no: a hold is a press |
| Black screensaver (display on) | key | key |
| Another app in front (e.g. Settings) | switch: the recording starts, the pill shows once the app is back in front (kiosk return, [kiosk.md](kiosk.md)) | no: the key goes to that app (unhandled, Android hands it to the media session), the switch makes it a press |
| Button reports no separate down/up (owner's `getevent` check) | key or switch | no: voice notes come from the on-screen buttons |
| Key layout not installed (no root) | switch; but the button's `KEY_POWER` also puts the device to sleep | no |

Android 11 passes no key to the app while the display is off, and doesn't wake the device for a
media key (the layout's `WAKE` flag doesn't change that), so a hold needs the screen on.

## API for the hardware button

```kotlin
import com.msp1974.vacompanion.odysseus.record.RecorderController
import com.msp1974.vacompanion.odysseus.record.RecorderStateMachine

RecorderController.press()   // a short press (released before HOLD_MS)
RecorderController.hold()    // held for RecorderStateMachine.HOLD_MS (1 s): call it while still held;
                             // the release after a hold must call nothing
RecorderController.state     // StateFlow<RecorderUiState>: recorder (Idle / Recording / Note), queue, problems
```

- Both are safe from any thread and return at once. Refusals (muted, recording off, not
  linked, no microphone permission) are shown as a toast and logged. They need no handling.
- `RecorderController` must be installed first (the foreground service does it at start).
  Before that, a press is refused with "The recorder isn't ready yet".
- The on-screen buttons do the same timing with `Modifier.recordGestures()` in
  `RecordingUi.kt`.

## Privacy

- Nothing records unless a person pressed something. The recorder never starts by itself;
  after a note it only resumes a recording the person started.
- The red pill shows on every screen while anything records, and the screen is kept on. When
  recording starts, the screen is woken (event `odysseusRecordingStarted`, `MainActivity`).
  If the activity is not in front (another app was opened), the pill can't show until it comes
  back (see [kiosk.md](kiosk.md)).
- Audio goes only to the owner's hub, over the same link as the voice turns. It is kept on the
  device only until the hub has it. Chunks the hub refused stay in the queue folder (counted in
  the settings), and the cap deletes them with their session.

## Sounds

`assets/odysseus/record_start.wav` and `record_stop.wav` are made from sine waves by
`scripts/odysseus/make_record_sounds.py`:

```bash
python3 scripts/odysseus/make_record_sounds.py app/src/main/assets/odysseus
```

They play through the app's sound-effect player (notification usage), not the satellite's, so
they work without Home Assistant.

## Tests

```bash
./gradlew :app:testDebugUnitTest --tests 'com.msp1974.vacompanion.odysseus.record.*'
```

- `RecorderStateMachineTest`: every gesture in every state, debounce, refusals, the limits.
- `ChunkAssemblerTest`: chunk timing, splitting at the joins, the timestamp format, and the WAV
  header the hub relay checks.
- `UploadRulesTest`: contract C4's result table, the retry waits, the hub origin, session ids,
  and the queue cap.
- `RecordingCaptureTest`: one capture against a fake microphone, and a resubscribe racing a stop.
- `RecordingQueueTest`: the queue against a fake hub (order, `/end`, retry, auth, rejected,
  the 404 fallback, sessions left by a killed run, half-written files, the cap).
- `RecordingTextTest`: the pill and status wording.
- `RecordButtonGesturesTest`: the privacy button's press and hold from key down/up, repeats,
  stale timers, the mute-switch fallback and how a key covers it, and the Record/Mute rules.

## Checking on a device

1. Tap Record in the quick actions sheet. The REC pill counts up and the start tone plays. Talk
   for a minute, then tap the pill. In Athena, a conversation from device `echo-<id>` appears.
2. Hold the clock's record button for 1 s and say "remind me to water the plants at 6". Tap to
   stop. In Athena, a voice note appears, and the reminder is in Odysseus.
3. Start a recording, turn Wi-Fi off for a few minutes, then turn it back on. Menu → Odysseus →
   Recording shows chunks waiting, and then everything arrives (or tap "Upload now").
4. Say "Hey Athena" during a recording. It answers, and the recording goes on.
5. `adb logcat | grep Recorder` shows the start, the chunks and the uploads.
6. The privacy button: the checks in
   [echo-show-5.md](echo-show-5.md#owners-checks-on-the-echo).
