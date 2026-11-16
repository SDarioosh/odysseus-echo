# Power use

Why the app keeps a tablet busy, what has been made cheaper, and what was left as it is because
changing it would change how the device behaves. Found by reading the code (2026-11); nothing
here has been measured on a device yet (see [Measuring](#measuring)).

## Less work per frame (no change in behaviour)

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

## The "stop" model runs only while something can be stopped

microWakeWord used to run the stop model beside the wake word on every frame. A "stop" acts only
while a response is spoken (the pipeline streams TTS; announcements too, they start at that stage)
or an alarm sounds (`Satellite.handleStopWord`); nothing else reacts to it. Now the stop model runs
only then (`odysseus/wakeword/StopWordGate.kt`), read from that state on every 10 ms frame.

- One detector is kept: the wake word model and the shared frontend never stop or reset.
- While paused, the last 1.38 s of features is kept. When stop is needed again, that history is
  replayed through the model first (outputs discarded), from the same stride boundary it would
  have been on. The model's state depends only on its last 89 frames (input `[1, 3, 40]`,
  checked with the TFLite interpreter), and the replay also refills its 5-score window, so it
  scores exactly as if it had never paused (`StopWordGateTest`). A "stop" said as the reply or
  alarm starts is still heard.
- A "stop" that finished just *before* a reply or alarm started could, in a race, stop it before;
  it can't now. The diagnostics overlay no longer shows stop scores while nothing can be stopped.

## Wyoming: no own ping; dead connections close in 5 s

Home Assistant's wyoming satellite pings the device 2 s after each pong and drops the connection
after 5 s without an event (`_PING_SEND_DELAY`, `_PING_TIMEOUT` in
`homeassistant/components/wyoming/assist_satellite.py`, since 2024.2); the device answers with
`pong`. The device's own ping every 3 s only fed its watchdog with HA's pongs, so it is gone.

The 1 s watchdog loop is replaced by a time limit on each read (`wyoming/WyomingClientHandler.kt`,
rule in `odysseus/wyoming/WyomingSilencePolicy`): 5 s after the last message, as before, but it
now really closes the socket. The old loop only set a flag while the read stayed blocked, so a
dead connection lasted until TCP gave up (about 15 minutes). After `pause-satellite` (muted in
HA) there is no limit; the server's 5-minute pause timeout still applies.

## Wi-Fi power save, and quieter device broadcasts

Upstream kept Wi-Fi out of power save for the app's whole life. Now `odysseus/power/WifiPerformance`
holds the lock only while a need is on (`WifiLockPolicy`):

| Need | While |
|---|---|
| VOICE | from the wake word (or an announcement, follow-up or continued conversation) while arbitrating or a pipeline runs, + 5 s; at most 10 min |
| ARBITRATION_MASTER | this device is the master and another device was heard in the last 95 s |
| SENDSPIN | a Sendspin stream plays, + 5 s (clock sync) |
| CAMERA | the RTSP stream has a viewer |

Android 10+ holds a high-performance and a low-latency lock (low latency applies with the screen on
and the app visible, high performance otherwise; both keep power save off); Android 8-9 only the
first. The dashboard, the music page and the Bluetooth proxy work in power save.

In power save the access point holds frames until the device wakes, and sends broadcasts only
after a DTIM beacon (up to about 300 ms). So arbitration reports now go by unicast to the master,
which stays awake, and decisions by unicast to each reporter, awake since its wake word (each with
a broadcast copy as a fallback). Keep-alive traffic is lower (`odysseus/power/MasterKeepAlivePolicy`):

| | Before | After |
|---|---|---|
| Master heartbeat | broadcast every 2 s | broadcast every 5 s |
| Presence | broadcast by every device every 10 s | unicast to the master every 30 s |
| Election loop | every 1 s | a timer that sleeps until its next step |
| Master silently gone | replaced in about 7.5 s | replaced in about 15.5 s (`master_query` after 12.5 s) |
| Master's app stops | replaced in about 7.5 s | at once (`master_resign`) |

Between a master vanishing without a word and its replacement, a wake word is answered 1.5 s late
(possibly by two devices), as before, and starts the election at once. The wire format changed:
all devices must run the same build. Details: [wake-word-arbitration](../wake-word-arbitration.md).

## A real screen-off for the screensaver

With device admin (`force-lock`) and no lock screen, the screensaver now turns the panel fully off
(`odysseus/screen/ScreenOff`, rules in `ScreenOffRules`) instead of black at 1% backlight. A touch
can't wake an off panel, so it does so only when neither a touch (Home Assistant's "screen saver
disable on touch", on by default) nor camera motion is set to end the screensaver, or when
Menu → Odysseus → Kiosk → **Screensaver turns the screen fully off** is on (default off).
Otherwise, and without device admin, it stays as before. The wake word, Home Assistant,
announcements, Sendspin pairing and the power or privacy button still wake it. Home Assistant
still sees "screen on + screensaver". Log: `Screensaver: panel off (...)` or
`Screensaver: black at 1% backlight (reason)`. Device setup: the runbooks
([echo-show-5](echo-show-5.md), [fire-hd-10-2021](fire-hd-10-2021.md), step 4: device admin and
the lock screen off, or Fire OS shows its lock screen after a wake).

## Left as it is, and why

| What | Cost | Why it wasn't changed |
|---|---|---|
| The dashboard WebView keeps running under the clock and home pages (`odysseus/home/HomeHost.kt`) | scripts, animations and camera cards keep drawing all day behind an opaque page | pausing it hides the page from Home Assistant's frontend, which then drops its websocket after a while; View Assist navigation and HA's own updates would stop. A proper fix moves that navigation to the app's own HA connection: a design change |
| Partial wake lock in screen-off mode (`device/ScreenUtils.kt`) | the CPU never sleeps | the wake word must keep listening with the screen off |
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
| `app/src/main/java/com/msp1974/vacompanion/odysseus/wakeword/StopWordGate.kt` | `StopWordRules`, `FeatureHistory`, `StopWordGate` (plain Kotlin, `StopWordGateTest`) |
| `app/src/main/java/com/msp1974/vacompanion/odysseus/wakeword/StopWordModels.kt` | the detector's gated stop models |
| `app/src/main/java/com/msp1974/vacompanion/odysseus/wyoming/WyomingSilencePolicy.kt` | when a silent Wyoming connection is closed (plain Kotlin, `WyomingSilencePolicyTest`) |
| `app/src/main/java/com/msp1974/vacompanion/odysseus/power/WifiPerformance.kt` | the one owner of the Wi-Fi lock |
| `app/src/main/java/com/msp1974/vacompanion/odysseus/power/WifiLockPolicy.kt` | when the lock is held, which mode (plain Kotlin, `WifiLockPolicyTest`) |
| `app/src/main/java/com/msp1974/vacompanion/odysseus/power/MasterKeepAlivePolicy.kt` | heartbeat, query and failover timing (plain Kotlin, `MasterKeepAlivePolicyTest`) |
| `app/src/main/java/com/msp1974/vacompanion/odysseus/screen/ScreenOffRules.kt` | when the screensaver turns the panel off (`ScreenOffPolicy`, `ScreenOffState`; `ScreenOffRulesTest`) |
| `app/src/main/java/com/msp1974/vacompanion/odysseus/screen/ScreenOff.kt` | locks the screen for the screensaver |

Hooks, each marked `Odysseus fork`: `wakeword/openwakeword/audio/AudioProcessor.kt`,
`audio/AudioDSP.kt`, `wakeword/microwakeword/MicroWakeWordEngine.kt`,
`wakeword/microwakeword/microwakeword/MicroWakeWord.kt`, `MicroWakeWordDetector.kt`,
`wakeword/WakeWordEngine.kt`, `satellite/SatelliteWakeWorkHandler.kt`, `satellite/Satellite.kt`,
`sendspin/SendspinPlayer.kt`, `sendspin/SendspinAudioOutput.kt`, `wyoming/WyomingClientHandler.kt`,
`ui/VAViewModel.kt`, `service/BackgroundTask.kt`, `broadcasts/PeerPresence.kt`,
`broadcasts/VacaUdpBroadcast.kt` (a large edit: a merge cost on upstream rebases), `MainActivity.kt`.
Fork files changed: `odysseus/ListeningIndicator.kt`, `odysseus/record/RecordingUi.kt`,
`odysseus/OdysseusSatellite.kt`, `odysseus/kiosk/KioskSettings.kt`, `odysseus/OdysseusSettingsLayout.kt`.
