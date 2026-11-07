# Wake word training clips

"Hey Athena" was trained only on synthetic Piper voices (`wake-word.md`), so it misses some real
voices. The Echo can collect real recordings of the household saying it and send them to the
Odysseus hub; the owner downloads them there and retrains the model with them.

Clips are private recordings. They go only to the linked Odysseus hub (the same link and token
as the voice assistant), are never logged, and must never be committed or uploaded anywhere.

## On the Echo

Menu → Odysseus → **Wake word training**:

- **Record clips (about 1 minute).** A full-screen guide asks for "Hey Athena" 10 times, each a
  bit differently (normal voice, quickly, quietly, from further away, looking away, slowly, from
  across the room). Each take is 3 s: say it once while the circle is green. The device doesn't
  answer while the guide runs. After each take it shows whether the wake word caught it and the
  model's score; at the end, how many it caught. Silent takes are dropped. Every take that has
  speech is kept, caught or not: both help, the missed ones most.
- **Catch missed wake words** (off by default). When the model scores a sound at 0.5 or more but
  the device doesn't wake, and the device then does wake within 8 s, that's almost always
  someone saying "Hey Athena", not being heard, and saying it again. The Echo then keeps both
  tries (about 2.6 s each: 2 s before the score's peak and 0.6 s after) as `missed` and `heard`
  clips. Nothing else is kept: the last 4 s of audio are held in memory only while this is on,
  and a near miss without a wake after it is forgotten. Off while the device is locked.
- The section shows how many clips were sent and how many wait; **Send now** retries at once.

Clips wait in `files/odysseus/wakeword_clips/` (at most 200; the oldest go first) until the hub
has them, and are deleted then. Only the microWakeWord engine feeds clips (Hey Athena is a
microWakeWord model); it needs the wake word running, so the guide says when the device is
muted, locked or not connected to Home Assistant.

### Code

`odysseus/wakeword/`:

- `WakeClipCatcher`: the rules (memory ring, near miss then wake, guided takes, silence check).
  Plain Kotlin, `WakeClipCatcherTest`.
- `WakeClipQueue`: the queue on disk and the upload (contract below). `WakeClipQueueTest`.
- `WakeClips`: the process-wide object: settings, the guided session, the upload loop.
- `WakeWordTrainingUi`: the settings section and the guide.

Hooks: `wakeword/microwakeword/MicroWakeWordEngine.kt` passes each frame and its score to
`WakeClips.onFrame`; `satellite/SatelliteWakeWorkHandler.kt` calls `WakeClips.onWake` on a wake
and doesn't wake while `WakeClips.isTraining`; `odysseus/OdysseusSettingsLayout.kt` shows the
section.

## Contract with the hub

`POST <voice base>/satellite/wakeword/clip` (the voice token), multipart:

| Field | |
|---|---|
| `audio` | WAV, 16 kHz mono 16-bit, at most 10 s |
| `clip_id` | made on the device (UUID); sending it again is a no-op (`"already": true`) |
| `device_id`, `device_name` | as in `/satellite/hello` |
| `wake_word` | the model id, `hey_athena` |
| `kind` | `guided`, `missed` or `heard` |
| `score`, `threshold` | the model's highest single score in the clip, and the device's threshold (0–1) |
| `prompt` | the guided take's style ("Quietly"); absent otherwise |
| `recorded_at` | epoch ms |

Answer `{"ok": true, "id", "already"}`. 401/403: relink; 404/405: an older hub (clips kept,
retried hourly); 507: the hub's store is full (retried); another 4xx drops that clip.

The hub side (`SDarioosh/odysseus-expanded`, `docs/fork/voice-assistant.md#wake-word-clips`)
lists the clips under Settings → Integrations → Voice assistant → Echo devices, plays and
deletes them, and exports them as a zip for training.

## Using the clips

1. In the hub, listen to a few `missed` clips and delete any that aren't the wake word.
2. Download the zip (**Download for training**) to the training machine.
3. See how the current model does on them, per threshold, and which clips it misses:

   ```bash
   pip install pymicro-wakeword
   python3 scripts/odysseus/wakeword/real_clips.py score hey_athena_clips.zip
   ```

   If nearly all real clips pass at a lower threshold (say 0.85), lowering the threshold in Home
   Assistant (`number.<device>_wake_word_threshold`, 8.5) is a quick fix; the false-wake rates per
   threshold are in `echo-show-5.md`.
4. Trim the clips and split them for training (the output must be outside the repository):

   ```bash
   python3 scripts/odysseus/wakeword/real_clips.py prepare hey_athena_clips.zip --out ~/mww/real_hey_athena
   ```

   It writes `train/` and `test/` (every 5th clip) as 16 kHz WAVs trimmed to the spoken wake
   word, plus `manifest.csv`.
5. Retrain (`wake-word.md`): make features from `real_hey_athena/train` with the same
   augmentation as the Piper clips (`make_features.py` with that folder as input; augmenting
   each real clip many times — rooms, background noise — is what makes a few hundred clips
   count), and add them to the training config as one more positive set, weighted up so the
   few real clips aren't drowned by 15,000 synthetic ones:

   ```yaml
   features:
     - features_dir: generated_augmented_features   # the Piper clips, as before
       sampling_weight: 2.0
       penalty_weight: 1.0
       truth: true
       truncation_strategy: truncate_start
       type: mmap
     - features_dir: real_augmented_features        # these clips
       sampling_weight: 3.0
       penalty_weight: 1.0
       truth: true
       truncation_strategy: truncate_start
       type: mmap
     # ...the negative sets as before
   ```

6. Check the new model on `real_hey_athena/test` with `real_clips.py score --model new.json`
   (the test clips were not trained on) and on the dinner-party false-wake set as before, then
   replace `hey_athena.tflite`/`.json` in `app/src/main/assets/microwakeword/wakeWords/`.

Roughly 50–100 clips per person who uses the Echo, from where they usually stand, are a good
first batch: five or ten runs of the guide.
