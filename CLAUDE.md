# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

View Assist Companion App (VACA / package `com.msp1974.vacompanion`) — an Android app that turns a tablet/kiosk device into a Home Assistant "View Assist" satellite: it shows a kiosk WebView pointed at a Home Assistant dashboard, and runs an always-listening voice satellite (wake word detection → audio streaming → TTS playback) speaking the [Wyoming protocol](https://github.com/rhasspy/wyoming) over a local TCP server that Home Assistant connects to.

## Build / lint / run

This is a Gradle Android project (Kotlin, Jetpack Compose, Hilt DI). Use the Gradle wrapper; there is no separate lint/test command setup beyond the Android Gradle Plugin defaults. Unit tests (JUnit4, plain Kotlin) are in `app/src/test/`: `./gradlew :app:testDebugUnitTest`.

```
./gradlew assembleDebug              # build debug APK
./gradlew assembleRelease            # build release APK (needs KEYSTORE_FILE/KEYSTORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD env vars)
./gradlew lint                       # Android lint
./gradlew printVersionName           # prints app/build.gradle.kts versionName (used by CI/env)
```

On Windows use `gradlew.bat` instead of `./gradlew`.

Screenshot tests (Odysseus fork, Roborazzi + Robolectric on the JVM, no emulator): `./gradlew :app:recordRoborazziDebug -Pscreenshots` renders Compose screens to `app/build/outputs/roborazzi/` (not committed); they run only with `-Pscreenshots`. Add one with `shots("name") { ... }` in `app/src/test/.../screenshots/`; see [docs/odysseus/screenshots.md](docs/odysseus/screenshots.md).

Version (Odysseus fork): set once, as `odysseusVersionName` (`0.14.0+odysseus.N`) in `app/build.gradle.kts`; bump `N` for a release. `versionCode` is derived from it (`major*10^7 + minor*10^5 + patch*10^3 + N`) — don't set it by hand. Upstream's `version.properties` counter (which incremented on every Gradle run) is removed; if a rebase brings it back, delete it again.

There are 3 Gradle modules (`settings.gradle.kts`):
- `:app` — the application itself.
- `:microfeatures` — an Android library with a JNI/CMake component (`src/main/cpp`, TensorFlow Lite + kissfft) providing the "micro frontend" (audio → spectrogram features) used by the microWakeWord engine.

## High-level architecture

### Two independent "screens" driven by one Activity

`MainActivity` hosts a single Compose tree that switches between:
- **ConnectionScreen** — shown before the satellite/webview is up (status text, connection state).
- **WebViewScreen** — the actual kiosk view once the satellite has started, loading the Home Assistant dashboard URL via a `CustomWebView`. `SettingsLayout` overlays this when the on-device settings menu is open.
- **BlackScreen** — shown when the screen is intentionally blanked (screensaver / `screenSaver` config flag).

State is centralized in `VAViewModel` (`ui/VAViewModel.kt`) via a single `vacaState` `StateFlow`, and cross-cutting settings/events flow through `APPConfig.eventBroadcaster` (an `EventNotifier`/`EventListener` pub-sub — see `utils/Events.kt`), which both `MainActivity` and `Satellite` subscribe to for things like screen mode, dark mode, mute state, etc. Don't wire new cross-component state through direct references between `MainActivity`/`Satellite`/`VAViewModel` — go through `config.eventBroadcaster` or `BroadcastSender` (`broadcasts/BroadcastSender.kt`, backed by `LocalBroadcastManager` + explicit `Context.registerReceiver`) the way existing code does.

### DI and app-wide singletons

Hilt (`@HiltAndroidApp` on `VACAApplication`, `@AndroidEntryPoint` on `MainActivity`/`VAForegroundService`). The one hand-wired module is `di/AppModule.kt`, providing the single `DeviceManager` singleton. `DeviceManager` (`device/DeviceManager.kt`) is the root object graph: it owns `APPConfig` (`settings/Settings.kt` — the mutable, `SharedPreferences`-backed app config, with `Delegates.observable` properties that fire `eventBroadcaster` notifications on change), `DeviceInfo`, `SensorManager`, and `AuthenticationManager` (HA long-lived token auth), and exposes `StateFlow`s for network/server/sensor/UI status.

### Wyoming server → Satellite → AudioPipeline (the core voice flow)

This is the part most feature work touches. Layers, outside-in:

1. **`wyoming/WyomingTCPServer`** (abstract, instantiated anonymously in `VAForegroundService`/`BackgroundTask`) — raw Ktor TCP server + mDNS/Zeroconf advertisement (`wyoming/Zeroconf.kt`) that Home Assistant discovers and connects to. Owns the client connection map, handles the Wyoming handshake events (`describe`/`info`, `capabilities`, `ping`), and on `run-satellite` creates a `Satellite` instance and forwards all other packets to it. Only one `Satellite` runs at a time; it re-pairs (`handleSatelliteTakeover`) if a new client reconnects with a different `clientId`. Devices are "paired" to the first HA server IP that starts a satellite (`config.pairedDeviceID`) and reject other IPs.
2. **`satellite/Satellite`** (abstract, instantiated anonymously in `WyomingTCPServer`) — the per-connection satellite session. Owns the `SatelliteWakeWorkHandler` (wake word listening loop), a `SatelliteAudioPipeline` (only one active at a time, representing one voice interaction), `SatelliteMediaManager` (music/sound-effect/voice/alarm players), `SatelliteAudioLog` (in-memory diagnostic conversation/audio log surfaced in the on-device diagnostics overlay), and custom Wyoming "events" (`event_type: action|settings|capabilities` — VACA's own extension on top of stock Wyoming, handled in `customEventHandler`/`handleAction`). Handles wake-word-detected → start pipeline, and routes settings/actions coming from the HA custom integration (play/pause media, alarms, toast messages, manual wake, refresh).
3. **`satellite/SatelliteAudioPipeline`** (abstract, instantiated anonymously per-interaction in `Satellite.startAudioPipeline`) — a state machine (`PipelineStage`: STARTING → LISTENING → VOICE_STARTED/STOPPED → AWAITING_RESPONSE → AWAITING_TTS → STREAMING_TTS → ENDED) driving one ASR→intent→TTS round trip over Wyoming messages, plus a watchdog timer that force-ends stuck pipelines. `pipelineStage` is monotonic (can't move backwards). Mic audio is pumped in via `sendMicAudio`; TTS audio comes back and is written to `SatelliteMediaManager.voicePlayer`.
4. **Wake word engines** (`wakeword/`) — `WakeWordEngine` is a thin selector over two implementations of abstract `WakeWordEngineProvider`: `microwakeword/MicroWakeWordEngine` (TFLite models + the native microfrontend from `:microfeatures`) and `openwakeword/OpenWakeWordEngine` (ONNX/TFLite via `ml/OnnxModelRunner` / `TfliteModelRunner`, selectable per `WakeWordEngineModel.OPENWAKEWORD` vs `OPENWAKEWORD_RT`). `SatelliteWakeWorkHandler` wraps whichever engine is active and normalizes it to `AudioResult`/`WakeWordDetection` callbacks consumed by `Satellite`. Audio capture itself goes through `audio/MicrophoneInput`. Odysseus fork: the microphone is one process-wide `MicrophoneAudioStream` from `odysseus/audio/SharedMicrophone` (not one per `Satellite`), with `raw` frames beside the processed `audio` (see the Odysseus fork section). Its gain control and noise suppression are WebRTC's audio processing (AGC2 + noise suppression + high-pass), run natively via the `:webrtc-apm` module and wrapped by `audio/WebRtcMicProcessor` (the only app file that touches that module); the platform's own AGC/noise-suppression effects are deliberately not attached, only its echo canceller. WebRTC's output level is fixed, so the `mic_gain` setting (+-10 dB) is applied afterwards as a plain output gain (`WebRtcMicProcessor.applyGainDb`). The only other thing that alters mic gain is the optional `suppress_wake_sound` setting: `audio/MicSuppressor` turns the mic right down (-60 dB) from just before the wake sound plays until shortly after it ends, so a spoken wake sound isn't transcribed (`Satellite.playWakeWordDetectionSound`). `audio/MicLevelMonitor` measures the raw microphone level before any processing,, and supplies the wake word level used in arbitration and calibration (loudest 80 ms frame over the last 2 s). It doesn't change any audio. `audio/AudioDSP` holds small PCM conversion helpers.

Wyoming wire format lives in `wyoming/Packet.kt` (`WyomingPacket`, JSON header + optional binary payload) and `wyoming/WyomingTypes.kt` (event name constants, `SatelliteState`, custom event type constants). `WyomingInfoBuilder`/`WyomingCapabilitiesBuilder` construct the `info`/`capabilities` response payloads describing this device to HA.

### WebView ↔ native bridge

`utils/CustomWebView` + `utils/CustomWebViewClient` host the HA dashboard; `jsinterface/JavascriptInterface.kt` is the `@JavascriptInterface`-annotated bridge exposed into the page for JS-to-native calls (e.g. reading device info/settings from dashboard-side custom cards).

### Wake word arbitration (several devices hear one wake word)

Full description in [docs/wake-word-arbitration.md](docs/wake-word-arbitration.md); keep it in step with the code. In brief:

- Devices talk over UDP broadcast (`broadcasts/VacaUdpBroadcast`) and elect one master. Arbitration is **by wake word**: every device listening for a wake word that heard it, in any room, reports to the master, which picks one using `broadcasts/WakeWordArbiter` (pure, unit-tested): the loudest **calibrated** mic level if it clearly beats the next (3 dB), else the device that last answered (5 min), else the highest score, else the lowest device id. **Ducking is separate and still follows the area / custom-zone duck group** (`APPConfig.duckGroupKey`).
- Devices announce themselves every 10 s (`presence`, `broadcasts/PeerPresence`): a device with no other device listening for its wake word answers at once without arbitrating, and the master decides early once every such device has reported instead of waiting out the 150 ms window. A peer unheard for 35 s is dropped, so a device that doesn't announce itself (an older version) can lead to two answers; keep all devices on the same build.
- On detection every candidate device (`Satellite.handleWakeWordDetection`) plays the wake sound, starts an `audio/DetectionBuffer` (a subscription to the mic keeping frames from that moment) and reports. The winner starts the pipeline, which streams the kept audio and then live audio in order (`SatelliteAudioPipeline` is given `buffer.frames` in place of the live mic flow); losers cancel the buffer. Only the winner wakes the screen. A report arriving after the decision is told it lost at once.
- Everything under Settings → Experimental → Voice assistant is stored on the device in SharedPreferences and survives a restart (the app logs them as `Experimental settings: ...` when wake word detection starts). Two of them: `wake_word_arbitration` (on by default; off = the device answers every wake word it hears and neither reports nor reports interruptions) and the ducking scope `duck_scope` (`area` default, `zone`, or `disabled` = it neither sends nor reacts to `duck`; a disabled device has a blank duck group). The custom ducking zone, "Suppress wake sound" and "Microphone calibration" settings are in the same group.
- **Only devices that reported hearing the wake word take part in a decision.** A device that didn't hear it is never asked to stop or counted as busy. A device that hears the wake word while it is itself responding (`Satellite.isResponding`) interrupts at once (stops its pipeline, starts no new one, doesn't wait for a decision) and, if other devices listen for that wake word, reports with `responding = true`: the arbiter's rule 0 makes a responding reporter always win, so the others that heard it stand down. A device that interrupts isn't remembered as the last to answer.
- `wakeword/DetectionRearmGate` (tagged `REARM-FILTER`, removable on its own) ignores the rest of a wake word that has already fired: further detections are dropped until the score falls under half the threshold or the engine pauses for 300 ms.
- The level compared is the raw mic level before processing (`audio/MicLevelMonitor`, loudest 80 ms frame over the last 2 s) plus `APPConfig.wakeLevelOffsetDb`, set by the Microphone calibration wizard (`audio/WakeLevelCalibration` for the logic, `ui/components/MicrophoneCalibrationDialog`; Settings → Experimental → Voice assistant; SharedPreferences, not sent to HA). The wizard names the wake word and takes 5 detections in a normal voice (their median is offset to -30 dB, giving the calibration offset); while it runs detections only record a reading.

### Foreground service lifecycle

`MainActivity` starts `service/VAForegroundService` (a `LifecycleService`), which in turn creates and starts `service/BackgroundTask` (the `WyomingTCPServer` subclass) — this is what keeps the Wyoming server/satellite alive while the Activity is backgrounded or the screen is off. `config.backgroundTaskStatus`/`config.backgroundTaskRunning` track this so `MainActivity.onResume` can detect and restart a dead background task.

### Settings/config persistence

`APPConfig` wraps default `SharedPreferences` plus a mix of transient in-memory fields. Most user-facing settings (wake word, thresholds, screen behavior, custom sounds, etc.) are `Delegates.observable` properties — changing one automatically notifies `eventBroadcaster` listeners (UI, `Satellite`, `MainActivity` screen handling) rather than requiring manual wiring. Server-driven settings arrive as a JSON blob over the custom Wyoming `settings` event and are applied via `APPConfig.processSettings`.

### Sendspin player

Optional (setting `APPConfig.sendspinEnabled`, key `sendspin_enabled`, settable on-device in Configuration or pushed by HA). `service/BackgroundTask` starts `sendspin/SendspinManager`, which runs a `SendspinPlayer` (native sendspin-cpp engine from the `:sendspin` module + `SendspinAudioOutput` AudioTrack) and advertises it over mDNS via `SendspinAdvertiser`; Music Assistant connects to it. It ducks via audio focus and the `voicePlaybackEnded` event, and publishes track info as the `sendspinNowPlaying` event, which `ui/components/NowPlayingOverlay` shows over the web view (transport buttons go back as `sendspinCommand` events). `APPConfig.sendspinPreferredServer` (key `sendspin_preferred_server`, HA-pushed URL like `http://192.168.200.240:8095`, blank = Auto) restricts the player to the server at that IPv4 address (Odysseus fork: or Home Assistant's address, `sendspin/SendspinAllowedAddresses`): the native bridge (`sendspin_jni.cpp`) finds the peer IPs of inbound sockets on the listening port and disconnects any other server right after its hello, since the library has no accept-time hook and doesn't expose peer addresses. Odysseus fork: encryption and pairing (sendspin-cpp v0.9.5), see `sendspin/README.md` "Encryption and pairing".

### Bluetooth proxy

Optional (setting `APPConfig.bluetoothProxyEnabled`, key `bluetooth_proxy_enabled`, settable on-device in Configuration or pushed by HA). `service/BackgroundTask` starts `btproxy/BluetoothProxyManager`, which makes the device look like an ESPHome Bluetooth-proxy node to Home Assistant: `BtProxyApiServer` (pure-JVM ESPHome native API server on port 6053, **plaintext framing only** - no Noise encryption, so no key must be set in HA; hand-rolled protobuf in `ProtoIo`/`ApiMessages`), `BleScanner` (raw advertisement batching/dedupe), `GattManager` (connect/services/read/write/notify, 3 slots, per-connection op queue, synthetic ATT handles) and `BtProxyAdvertiser` (`_esphomelib._tcp` mDNS). Needs `BLUETOOTH_SCAN`+`BLUETOOTH_CONNECT` (or `ACCESS_FINE_LOCATION` pre-Android 12), requested via the `OPEN_PERMISSION_SCREEN` broadcast. Known limit: Android stops unfiltered BLE scans while the screen is off.

### Custom/downloadable assets

Wake sounds, alarms, and custom wake word models can be supplied by the HA-side integration and are fetched via `utils/CustomFileDownloader` / `satellite/SatelliteCustomFilesHandler` into app-private storage, then indexed via `data/AvailableWakeSounds`, `data/AvailableAlarms`, `wakeword/AvailableWakeWords`.

## Odysseus fork

What the fork changes and why: [ODYSSEUS.md](ODYSSEUS.md). Each feature's doc in `docs/odysseus/` lists its files under `## Files`. Device: [echo-show-5.md](docs/odysseus/echo-show-5.md); a new Echo: [new-echo-runbook.md](docs/odysseus/new-echo-runbook.md); building and signing: [build.md](docs/odysseus/build.md); old tests: [history.md](docs/odysseus/history.md).

### Rules

- Fork code lives in `app/.../odysseus/` (plus `scripts/odysseus/`, `docs/odysseus/`). A hook in an upstream file is a one-liner marked `Odysseus fork` in a comment that calls into `odysseus/` (`git grep -n "Odysseus fork"` finds them all). `Satellite` goes through `odysseus/OdysseusSatellite`, the one fork object it holds; `MainActivity` composes the fork's overlays through `odysseus/OdysseusOverlays`. A new feature gets one row in ODYSSEUS.md and its files in its doc's `## Files` section.
- `odysseus/ha/` is the one way new code reaches Home Assistant: `HaClient.get(deviceManager)` is an `HaApi` (contract I1 in `HaApi.kt`). The websocket is open only while `watch()` is collected; one token refresh per call on a 401 (`withAuthRetry`).
- Every request to the Odysseus hub goes through `OdysseusLink` (`send`; only the streamed APK uses `openGet`). Features ask through the `HubHttp` seam (`OdysseusLink` on the device, fakes in tests). The device and the hub are deployed together: no fallbacks for older hubs.
- Anything new that listens, plays or shows must check `LockMode.isLocked`.
- Don't read `AudioManager.isMicrophoneMute` directly; `odysseus/record/RecordButton` decides whether Android's mic-mute flag counts (`effectiveMicMute`).
- Buttons, the REC pill and the hardware button only call `RecorderController.press()` / `hold()` and read `RecorderController.state`.
- To add a home page, implement `HomePage`, add it to `HomePages.all`, and pass any new data through `HomePageContext` (pages don't read the view model).
- To add a Music tab, add one entry to `MusicTabs.all`. Tabs read `MusicRepository`, `LocalHomePageContext` and `rememberPlayerState`/`rememberRoomArt`, and change things only through `MusicActions`.
- The fork's settings stores declare each field once on an `odysseus/settings/PrefStore` (`var x by store.bool("pref_key", default, managed = "c6.key")`). `managed = …` is all a hub-manageable setting needs in code (it makes `ManagedKeys.all` and the binding); a new store also goes into `ManagedBindings.stores`. Its menu control is `ManagedSwitchRow(…, managedKey = key)` or `SecondsChips(…, key)` (`managed/ManagedControls.kt`), disabled with "Set by Odysseus" while managed. Never change an existing SharedPreferences key.
- Rules and timings go in plain Kotlin classes with unit tests (`*Rules`, `*Policy`); the Android side stays thin.
- Updates install only over a build signed with the same release key ([build.md](docs/odysseus/build.md)). Upstream's `utils/Updater.kt` stays disabled.

### Features

| Feature | Entry point | Doc |
|---|---|---|
| Home screen over the live dashboard: clock, weather, other speakers | `odysseus/home/HomeHost`, `HomeNavigator`, `HomeScreenController` | [home-screen](docs/odysseus/home-screen.md) |
| Today page and Done buttons | `home/today/TodayRepository`, `done/DoneClient` | [home-screen](docs/odysseus/home-screen.md#today-page) |
| Music page (Music Assistant through HA) | `home/music/MusicPage`, `music/MusicRepository` | [music](docs/odysseus/music.md) |
| Home Assistant client | `odysseus/ha/HaClient` | [home-assistant-client](docs/odysseus/home-assistant-client.md) |
| Voice turns: indicator, turn card, satellite post, timings, thinking sound, barge-in | `odysseus/OdysseusTurn`, `TurnCard`, `BargeIn`, `HaRunGate` | [turn-card](docs/odysseus/turn-card.md) |
| Answer "yes" without the wake word | `odysseus/followup/FollowUp` | [follow-up](docs/odysseus/follow-up.md) |
| Shared microphone with raw audio; app processing off by default | `odysseus/audio/SharedMicrophone`, `odysseus/MicProcessing` | [shared-microphone](docs/odysseus/shared-microphone.md) |
| Recorder and privacy button | `odysseus/record/RecorderController`, `RecordButton` | [recording](docs/odysseus/recording.md) |
| Announcements and reminder cards | `odysseus/announce/Announcements` | [announcements](docs/odysseus/announcements.md) |
| Updates from Odysseus | `odysseus/update/UpdateManager` | [updates](docs/odysseus/updates.md) |
| Settings managed by Odysseus | `odysseus/managed/ManagedSettings`, `ManagedBindings` | [managed-settings](docs/odysseus/managed-settings.md) |
| Kiosk: back from other apps, reload a failed dashboard | `odysseus/kiosk/ReturnToDashboard`, `DashboardAutoReload` | [kiosk](docs/odysseus/kiosk.md) |
| Lock mode | `odysseus/lock/LockMode`, `SatelliteLock` | [lock-mode](docs/odysseus/lock-mode.md) |
| Phone setup (QR code) | `odysseus/EchoSetup` | [setup](docs/odysseus/setup.md) |
| Sendspin pairing, Tailscale | `sendspin/SendspinSecurity`, `SendspinAllowedAddresses` | [sendspin/README.md](sendspin/README.md), [tailscale](docs/odysseus/tailscale.md) |
| Look (theme, icons) | `odysseus/OdysseusTheme` | [look](docs/odysseus/look.md) |
| "Hey Athena" wake word | `assets/microwakeword/wakeWords/hey_athena.*` | [wake-word](docs/odysseus/wake-word.md) |

## Conventions to follow

- Long-lived per-connection/per-interaction objects (`Satellite`, `SatelliteAudioPipeline`, the anonymous `WyomingTCPServer`/wake-word-handler subclasses) are written as **abstract classes with callback methods** (`onEvent`, `onStateChange`, `onFinish`, etc.) instantiated as anonymous objects at their call site, rather than passed-in interface/lambda parameters. Match this pattern when extending these areas rather than introducing a parallel callback-interface style.
- Cross-cutting state changes propagate via `Event`/`EventListener`/`EventNotifier` (`utils/Events.kt`) through `config.eventBroadcaster`, or via `BroadcastSender` string-constant broadcast actions for coarser app-level signals (satellite started/stopped, version mismatch, close app, etc.). Prefer these existing channels over new direct cross-references between `MainActivity`, `Satellite`, and the UI layer.
- `Timber` is the logging facade everywhere (planted in `VACAApplication`); there's also a thin `utils/Logger` wrapper used in some older code — prefer `Timber` directly for new code, matching most of the codebase.
