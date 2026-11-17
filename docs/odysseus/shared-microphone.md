# One shared microphone, with raw audio

The app has one microphone for the whole process. The satellite (wake word, the listening
pipeline, "Check my voice") uses it, and so can other features such as the recorder, with or
without Home Assistant connected.

## Why

Upstream made a `MicrophoneAudioStream` for each `Satellite`, so the microphone existed only
while Home Assistant was connected, and its only flow carried processed audio: quiet frames
became silence (`MicGate`), then WebRTC (when on), the wake-sound suppressor and the gain. A
recording for Athena needs the microphone without Home Assistant, and the audio as the
microphone heard it.

## How it works

- `odysseus/audio/SharedMicrophone` owns the one `MicrophoneAudioStream`, created on first use
  with a process-wide coroutine scope. `Satellite` takes it from there (one hook line) instead
  of making its own.
- `MicrophoneAudioStream` has two flows of 16 kHz mono PCM16 frames, 1280 samples (80 ms) each:
  - `audio`: after processing, as before. The wake word, the pipeline and "Check my voice" use
    it, unchanged.
  - `raw`: the same frames before any processing (before the gate, WebRTC, the suppressor and
    the gain). `MicrophoneInput.readShort` passes a copy of each frame to a callback right after
    it is read (a hook), and the stream sends both together as a `MicFrame`.
- `odysseus/audio/MicFrameSharing` (plain Kotlin, tested in `MicFramesTest`) shares the capture
  as the two flows. **Who keeps the microphone open**: it is open while at least one coroutine
  collects `audio` **or** `raw`, opens with the first and is released after the last of both
  leaves. A subscriber to `raw` alone keeps it open. Screen off with nothing listening, it is
  closed as before.
- Each flow keeps 16 frames (about 1.3 s) per subscriber and drops the oldest for one that
  falls behind; neither replays. Processing still runs on every frame while the microphone is
  open, even when only `raw` is collected.
- `level` (the raw level for wake word arbitration and calibration) and `suppressor` belong to
  the one shared stream, as they did to each satellite's.

## Using the raw audio (for the recorder)

```kotlin
val mic = SharedMicrophone.get(context, deviceManager)   // or SharedMicrophone.raw(context, deviceManager)
val channel = Channel<ShortArray>(capacity, BufferOverflow.DROP_OLDEST)
val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
    mic.raw.collect { channel.trySend(it) }               // never suspends, so no frame is dropped
}
// ... read the channel on a background dispatcher, encode WAV chunks ...
job.cancel()                                              // stop listening; the mic closes if nothing else listens
```

- Collect undispatched into your own channel, as `audio/DetectionBuffer` does, so the
  subscription exists when the call returns and a slow encoder can't lose frames from the
  shared 16-frame buffer.
- Frames are fresh arrays that nothing else holds: keep them as they are.
- Without RECORD_AUDIO the microphone isn't opened and the flows stay empty (no error).
- A microphone that fails is reopened after 1 s. A recorder that only returns read errors
  (`ERROR_DEAD_OBJECT` after the audio server restarted: no exception, just empty reads) counts
  as failed after 200 empty reads in a row (2 s or more). Failures are counted in a row: a frame
  read resets the count (`restartOnFailure`, `EmptyReads` in `MicFrames.kt`, `MicRestartTest`).
- If the microphone fails five times in a row the stream gives up until every subscriber of
  both flows has left (as upstream did per satellite). A recorder that sees no frames for a
  few seconds should cancel and collect again, but only once the wake word has also let go
  (for example on a satellite restart), so this is rare.

## Files

- `odysseus/audio/SharedMicrophone.kt`: the one `MicrophoneAudioStream` of the process.
- `odysseus/audio/MicFrames.kt`: `MicFrame`, `MicFrameSharing` (who keeps the mic open),
  `restartOnFailure`, `EmptyReads` (pure; `MicFramesTest`, `MicRestartTest`).
- Hooks: `satellite/Satellite.kt` (takes the shared stream), `audio/MicrophoneInput.kt`
  (`readShort`: the raw copy), `audio/MicrophoneAudioStream.kt` (sharing; failures counted in a
  row, `restartOnFailure`, a dead AudioRecord detected by empty reads).

## Checking on a device

- Wake word, barge-in and "Check my voice" still work.
- `adb logcat | grep -i "microphone\|AudioRecord"`: one "Shared microphone created" per
  process run, one "Microphone opened" while listening, and never two AudioRecords at once,
  also after Home Assistant reconnects.
- Screen off with the wake word paused: "Microphone closed".
