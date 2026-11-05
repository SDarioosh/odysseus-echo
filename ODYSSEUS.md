# Odysseus fork of View Assist Companion App

This is a fork of [View Assist Companion App](https://github.com/msp1974/ViewAssistCompanionApp)
(VACA, Apache-2.0, by Mark Parker). It turns an Amazon Echo Show 5 (2nd gen, LineageOS) into
a wake-word voice satellite. The satellite talks to Home Assistant's Assist pipeline, and the
pipeline uses [Odysseus](https://github.com/SDarioosh/odysseus-expanded) as its conversation
agent, so you can ask Odysseus questions by voice and get answers from
[Athena](https://github.com/SDarioosh/Athena).

The fork exists for one reason: **nothing may leave the owner's network**. Everything else
stays as close to upstream as possible, so new VACA releases can be rebased onto it.

## Base

- Branch `odysseus`, rebased onto upstream `dev` at `5b42d23` (2026-10-31: 0.14.0 plus the
  post-release speech-buffering and wake-word race fixes). The 0.14.0 release itself clipped
  the start of a command and lost back-to-back requests with Home Assistant 2026.11 (seen on
  the Echo Show; see docs/odysseus/echo-show-5.md). Use the HA integration **v0.14.0** (its
  only later change is a translation). The fork on the 0.14.0 release is kept on branch
  `odysseus-on-0.14.0`.
- Package `com.msp1974.vacompanion.odysseus`, version `0.14.0+odysseus.N`, set once as
  `odysseusVersionName` in `app/build.gradle.kts`. `versionCode` is worked out from it
  (`major*10^7 + minor*10^5 + patch*10^3 + N`, e.g. 1400016), so bumping `N` for a release (or
  rebasing onto a newer upstream) is all it takes for it to go up; adb refuses downgrades.
  Upstream's `version.properties` counter is removed.

## Changes from upstream

Each change is one commit, and every code change is marked `Odysseus fork` in a comment:
`git grep -n "Odysseus fork"`.

| Change | Why | Files |
|---|---|---|
| Removed Firebase Analytics, Crashlytics, Google ML Kit face detection and Play services | telemetry to Google (events, device name, UUID, crash reports with settings breadcrumbs; ML Kit usage logging) | `build.gradle.kts`, `app/build.gradle.kts`, `gradle/libs.versions.toml`, `AndroidManifest.xml`, `utils/Logger.kt` (no-op `FirebaseManager`), `VACAApplication.kt`, `device/MotionDetectionEngine.kt`, `settings/Settings.kt` |
| No process-wide trust-all TLS | upstream disabled certificate checks for every HTTPS request; a general fix to offer upstream | `VACAApplication.kt` |
| No in-app updates | they would install the official, Firebase-enabled APK | `utils/Updater.kt` |
| Voice-turn timeout configurable (`pipeline_timeout`, default 60 s, was a fixed 20 s) | Odysseus agent turns with Athena tool calls on a local model take longer than 20 s | `satellite/SatelliteAudioPipeline.kt`, `settings/Settings.kt` |
| Own application id and version; `versionCode` derived from `versionName`, `version.properties` removed | never collides with or "updates" to the official build; the upstream counter went up on every Gradle run and a fresh clone could build a code lower than the one installed | `app/build.gradle.kts` |
| Bundled "Hey Athena" microWakeWord model | Odysseus wake word, trained locally (`docs/odysseus/wake-word.md`) | `assets/microwakeword/wakeWords/hey_athena.*` |
| Wake word during a turn starts a new turn (barge-in) | upstream only stops the turn, so the question said right after the wake word was lost | `satellite/Satellite.kt` |
| Barge-in waits for HA to end the interrupted run, and drops that run's answer | HA's Wyoming satellite clears its "run in progress" flag on any run's end, so starting the new run first left the satellite deaf until it reconnected | new `odysseus/HaRunGate.kt`; hooks in `satellite/Satellite.kt`, `wyoming/WyomingTCPServer.kt` |
| Barge-in asks Odysseus to end the interrupted turn at once (`POST /satellite/interrupt`), also after a long "Thinking…" | HA ends the interrupted run only once the agent has answered; an Odysseus turn that looks things up takes longer than the 15 s the gate waits, so the new question was dropped. After 15 s of "Thinking…" a wake word wasn't a barge-in at all, and the old answer played | new `odysseus/BargeIn.kt`; `OdysseusLink.interruptTurn`; hooks in `satellite/Satellite.kt` |
| Voice actions card: what a turn added or completed, fetched from Odysseus with the voice token; Menu → Odysseus to link | HA passes only the spoken answer to the satellite (`docs/odysseus/turn-card.md`) | new `odysseus/OdysseusLink.kt`, `TurnCard.kt` (was `VoiceActionsCard.kt`), `OdysseusSettingsLayout.kt`; hooks in `MainActivity.kt`, `ui/layouts/SettingsLayout.kt` |
| Turn card: also the turn's sources (up to 3 chips, opened in an in-app overlay with a close button) and the confirmation question Odysseus waits on; `GET /turns/latest`, `/actions` on an older hub | voice contract v1 §6: the screen shows what a turn did and where the answer came from; changes by voice need a spoken "yes" (`docs/odysseus/turn-card.md`) | new `odysseus/VoiceTurnApi.kt`; `odysseus/TurnCard.kt`, `OdysseusLink.kt`; hook in `MainActivity.kt` (renamed composable) |
| Satellite post: at `VOICE_STOPPED` the device posts device, area, wake word and (setting "Check my voice") the command's audio as WAV ≤ 15 s to `POST /satellite/turn` | HA tells the agent nothing about the satellite; Odysseus uses room context and Athena's speaker check (`docs/odysseus/turn-card.md`) | new `odysseus/OdysseusTurn.kt`, `TurnAudioRecorder.kt`; hooks in `satellite/Satellite.kt` (`startAudioPipeline`) |
| Turn timings: each voice turn's stage times (ms after the wake word) are posted to `POST /satellite/timing` when it ends, with flags for the thinking sound, a barge-in and the voice check; numbers only | find which step of a turn is slow (speech to text, Odysseus, text to speech) and measure each improvement; Odysseus joins them to its own times (`docs/odysseus/turn-card.md#timings`) | new `odysseus/TurnTimings.kt`; `odysseus/OdysseusTurn.kt`, `OdysseusLink.postTimings`, `odysseus/BargeIn.kt` (no new upstream hooks) |
| Thinking sound: one soft tone after 5 s waiting for an answer, once per turn, not muted/DND/barge-in | a long agent turn otherwise sounds like the device stopped listening | new `odysseus/ThinkingChime.kt`, `assets/odysseus/thinking.wav` (made by `scripts/odysseus/make_thinking_sound.py`); runs from `odysseus/OdysseusTurn.kt` |
| Privacy button only mutes (no sleep), "Microphone off" on screen | the button also sends KEY_POWER on LineageOS 18.1 cronos | `scripts/odysseus/gpio-privacy-button.kl` (installed in /data); `odysseus/ListeningIndicator.kt` |
| "Auto" orientation checks for a real accelerometer/gyroscope, not the declared feature | LineageOS 18.1 cronos declares an accelerometer it lacks, so "auto" requested SENSOR, overrode the rotation lock and left the screen portrait (general fix, worth offering upstream) | `device/ScreenUtils.kt`; `scripts/odysseus/setup_device.sh` locks rotation 0 (landscape) |
| App microphone processing off by default (setting "App microphone processing" under Experimental → Voice assistant) | the Echo Show 5's own front-end already removes noise and levels speech; upstream's WebRTC noise suppression and gain control on top of it distort speech, and Whisper misheard at 1 m in a quiet room | new `odysseus/MicProcessing.kt`; hooks in `audio/MicrophoneInput.kt` (`readShort`), `ui/layouts/config/ExperimentalConfig.kt`, `ui/layouts/config/AudioConfig.kt` (labels) |
| Sendspin: trusted Music Assistant server ids (`sendspin_trusted_server_ids`, on-device only) accepted from any address | with one MA server HA always pushes its LAN URL as the preferred server, so the player refused MA connecting over Tailscale from its 100.x address (`docs/odysseus/tailscale.md`) | `sendspin/src/main/cpp/sendspin_jni.cpp`, `sendspin/.../NativeSendspin.kt`, `sendspin/SendspinPlayer.kt`, `settings/Settings.kt` |
| Sendspin: encrypted connections and pairing with Music Assistant (sendspin-cpp v0.8.0 → v0.9.5): device key and paired-server records in `files/sendspin/` (not backed up), six-digit pairing code shown on screen, "Allow unpaired servers" (`sendspin_unpaired_access`, on-device, default on), paired servers accepted from any address; new player id derived from the device key | MA showed the player as "connected without encryption (legacy mode)", couldn't pair it, and will drop legacy clients (`sendspin/README.md` "Encryption and pairing") | `sendspin/src/main/cpp/CMakeLists.txt`, `sendspin_jni.cpp`, new `blob_store.h` (+ host test `sendspin/src/test/cpp/blob_store_test.cpp`), `sendspin/.../NativeSendspin.kt`; app: new `sendspin/SendspinSecurity.kt`, `odysseus/SendspinPairingOverlay.kt`; `sendspin/SendspinPlayer.kt`, `SendspinManager.kt`, `settings/Settings.kt`, `ui/layouts/config/ServicesConfig.kt`, hooks in `MainActivity.kt`; `res/xml/data_extraction_rules.xml`, `backup_rules.xml` |
| Threshold rounds to nearest | upstream truncated: 9.2 became 0.91 (general fix, worth offering upstream) | `utils/Helpers.kt` |
| Set up an Echo from a phone with one QR code: Odysseus link, Home Assistant sign-in (admin), the Echo adds itself to Home Assistant and sets wake word, threshold and wake sound; QR on the connection screen until HA is signed in (`docs/odysseus/setup.md`) | typing on the Echo's screen is impractical | new `odysseus/EchoSetup.kt`, `EchoSetupPages.kt`, `EchoSetupRules.kt`, `HaSetupClient.kt`, `EchoSetupPanel.kt`, `HaClientId.kt`; hooks in `ui/layouts/ConnectLayout.kt`, `device/authentication/AuthenticationManager.kt` (stored client id for refresh), `app/build.gradle.kts` (`odysseusDefaultUrl`); ZXing core (Apache-2.0) in `gradle/libs.versions.toml` |
| On-screen listening indicator ("Listening…", "Thinking…", "Answering…" + glowing bottom bar) | visible feedback on a quiet or muted device, independent of the HA dashboard | new `odysseus/ListeningIndicator.kt`; hooks in `satellite/Satellite.kt`, `MainActivity.kt` |
| Home screen: a clock with the date and the playing song (Sendspin, play/pause) over the dashboard; swipe right for the Home Assistant dashboard, swipe left or 2 min idle back to the clock; Menu → Odysseus → Home screen (on/off, idle time) (`docs/odysseus/home-screen.md`) | a glanceable idle screen for the kitchen Echo, with the dashboard one swipe away | new `odysseus/home/` (`HomeNavigator.kt`, `HomeScreenController.kt`, `HomeSettings.kt`, `HomePage.kt`, `ClockPage.kt`, `HomeHost.kt`); hooks in `MainActivity.kt`, `ui/layouts/WebViewLayout.kt`; settings in `odysseus/OdysseusSettingsLayout.kt`; test `HomeNavigatorTest.kt` |
| Odysseus look: the web app's colours (dark and light theme, following the `dark_mode` setting), Fira Code font and small corners; the Odysseus sailboat as launcher, splash, notification and connection-screen logo; app name "Odysseus Echo" | the device is part of Odysseus and should look like it | new `odysseus/OdysseusTheme.kt`, `res/drawable/odysseus_*.xml`, `res/values/odysseus_colors.xml`, `res/font/fira_code_*.ttf` (Fira Code 6.2, OFL-1.1, licence in `assets/licenses/FiraCode-OFL.txt`); hooks in `ui/theme/Theme.kt`, `ui/layouts/ConnectLayout.kt`, `SettingsLayout.kt`, `ConfigurationLayout.kt` (section colours), `CustomFilesLayout.kt`, `ui/components/SettingsTiles.kt`, `service/ForegroundService.kt`, `res/values/themes.xml`, `res/values-v29/themes.xml`, `res/values/strings.xml`, `res/mipmap-*`; upstream's `main_logo.png`, `splash_image.png` and launcher layers removed |

What's lost: face-detection motion mode (pixel-difference motion detection still works),
in-app updates, and upstream's crash reports.

## Build

See [docs/odysseus/build.md](docs/odysseus/build.md) (toolchain without root, signing,
installing over adb).

## Rebasing on a new upstream release

```bash
git fetch upstream
git rebase --onto <new upstream commit> 5b42d23 odysseus
git grep -n "firebase\|crashlytics\|mlkit\|gms" -- '*.kts' '*.toml' '*.kt' '*.xml'   # must be empty except "Odysseus fork" comments
./gradlew assembleRelease
python3 scripts/odysseus/check_apk_privacy.py app/build/outputs/apk/release/*.apk
```
