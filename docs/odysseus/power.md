# Power use

Why the app keeps a tablet busy, what has been made cheaper, and what was left as it is because
changing it would change how the device behaves. Found by reading the code (2026-11); nothing
here has been measured on a device yet (see [Measuring](#measuring)).

## What changed (no change in behaviour)

Each change produces the same audio, detections and screen as before, only with less work.

| Change | Before | After |
|---|---|---|
| openWakeWord's 10 s audio history (`wakeword/openwakeword/audio/AudioProcessor.kt`) | a boxed `ArrayDeque<Float>` of 160,000 samples, copied whole into a list on every 80 ms frame to read the last 1,760: about 12 MB of garbage a second | `odysseus/audio/SampleHistory`, a `FloatArray` ring that copies only the samples read. Same samples, checked against the old deque in `SampleHistoryTest` |
| openWakeWord's embedding input | about 2,400 new arrays per frame | one reused input, zeroed before each use as a new one would be |
| openWakeWord's mel and feature buffers | joined, then trimmed through a list | joined and trimmed in one copy (`plusKeepingLast`, same rows in the same order, `PlusKeepingLastTest`) |
| Sample conversions (`audio/AudioDSP.kt`) | `map { }` boxed every sample of every frame | plain loops, same values (`AudioDSPTest`) |
| microWakeWord's frame buffer (`wakeword/microwakeword/MicroWakeWordEngine.kt`) | a new direct buffer every 80 ms | one buffer reused while the frame size stays the same; the detector copies the audio out before it returns |
| Listening indicator and recording dot (`odysseus/ListeningIndicator.kt`, `odysseus/record/RecordingUi.kt`) | their pulse animations ran all the time, also while hidden, asking for a frame on every screen refresh | the animation lives inside the shown content, so it runs only while the bar or pill shows (and while it fades out) |
| Sendspin feedback thread (`sendspin/SendspinPlayer.kt`, `SendspinAudioOutput.kt`) | woke every 10 ms even with nothing playing | waits while no stream is started; starting a stream wakes it at once, so playback feedback is exactly as before |

One bug fix rides along: after a diagnostic recording finished playing, the microphone was
turned back on even when the user had muted it (`satellite/Satellite.kt`). It now stays muted.

## Left as it is, and why

These cost the most, but each would change behaviour, so they need a decision and a test on a
real device first.

| What | Cost | Why it wasn't changed |
|---|---|---|
| The dashboard WebView keeps running under the clock and home pages (`odysseus/home/HomeHost.kt`) | scripts, animations and camera cards keep drawing all day behind an opaque page | pausing it hides the page from Home Assistant's frontend, which then drops its websocket after a while; View Assist navigation and HA's own updates would stop |
| Wi-Fi lock in high-performance mode (`utils/Network.kt`) | the radio never uses power save | power save adds latency to waking up for Home Assistant and to streaming speech, most with the screen off |
| A Wyoming ping to Home Assistant every 3 s (`wyoming/WyomingClientHandler.kt`) and the master's UDP heartbeat every 2 s (`broadcasts/VacaUdpBroadcast.kt`) | the radio rarely idles | connection checks and master election depend on these timings; all devices must change together |
| The "stop" word model runs beside the wake word (microWakeWord) | twice the inference | "stop" ends alarms and responses; loading it only when needed resets the models' state at that moment, and a "stop" said right then could be missed |
| Partial wake lock in screen-off mode (`device/ScreenUtils.kt`) | the CPU never sleeps | the wake word must keep listening with the screen off |
| Screensaver and "screen off" modes keep the backlight at 1% (`device/ScreenUtils.kt`) | the panel stays lit all night | a real screen-off needs device-admin rights and changes how the screen wakes |
| TFLite interpreters with default thread settings | possibly more than one thread per inference | untested on the devices; a slower inference could delay detection |

## Measuring

```sh
P=com.msp1974.vacompanion.odysseus
adb shell dumpsys batterystats --reset        # then leave it a few hours
adb shell dumpsys batterystats $P             # per-app CPU, wake locks, Wi-Fi
adb shell top -H -p $(adb shell pidof $P)     # busiest threads
adb shell dumpsys gfxinfo $P                  # frames drawn, e.g. while the clock shows
```

Compare a build before and after a change on the same device, with the same wake word engine
(Settings → wake word engine; "Hey Athena" uses microWakeWord).

## Files

| File | What |
|---|---|
| `app/src/main/java/com/msp1974/vacompanion/odysseus/audio/SampleHistory.kt` | `SampleHistory` (the sample ring) and `plusKeepingLast` (plain Kotlin, `SampleHistoryTest`) |

Hooks, each marked `Odysseus fork`: `wakeword/openwakeword/audio/AudioProcessor.kt`,
`audio/AudioDSP.kt`, `wakeword/microwakeword/MicroWakeWordEngine.kt`,
`sendspin/SendspinPlayer.kt`, `sendspin/SendspinAudioOutput.kt`, `satellite/Satellite.kt`.
Fork files changed: `odysseus/ListeningIndicator.kt`, `odysseus/record/RecordingUi.kt`.
Tests: `app/src/test/.../odysseus/audio/SampleHistoryTest.kt`, `app/src/test/.../audio/AudioDSPTest.kt`.
