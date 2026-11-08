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
  the Echo Show; see [docs/odysseus/history.md](docs/odysseus/history.md)). Use the HA integration **v0.14.0** (its
  only later change is a translation). The fork on the 0.14.0 release is kept on branch
  `odysseus-on-0.14.0`.
- Package `com.msp1974.vacompanion.odysseus`, version `0.14.0+odysseus.N`, set once as
  `odysseusVersionName` in `app/build.gradle.kts`. `versionCode` is worked out from it
  (`major*10^7 + minor*10^5 + patch*10^3 + N`, e.g. 1400016), so bumping `N` for a release (or
  rebasing onto a newer upstream) is all it takes for it to go up; adb refuses downgrades.
  Upstream's `version.properties` counter is removed.

## Changes from upstream

Each change is one commit, and every code change is marked `Odysseus fork` in a comment:
`git grep -n "Odysseus fork"`. Which files each feature touches is in its doc's `## Files`
section.

| Change | Why | Doc |
|---|---|---|
| Removed Firebase Analytics, Crashlytics, ML Kit face detection and Play services (`build.gradle.kts`, `app/build.gradle.kts`, `gradle/libs.versions.toml`, `AndroidManifest.xml`, `utils/Logger.kt` no-op `FirebaseManager`, `VACAApplication.kt`, `device/MotionDetectionEngine.kt`, `settings/Settings.kt`) | telemetry to Google: events, device name, UUID, crash reports with settings, ML Kit usage logs | [history](docs/odysseus/history.md#device-check-and-choice-of-the-satellite-app-2026-11-03) |
| No process-wide trust-all TLS (`VACAApplication.kt`) | upstream turned off certificate checks for every HTTPS request (general fix, worth offering upstream) | — |
| Upstream's GitHub updates off (`utils/Updater.kt`) | they would install the official Firebase build | [updates](docs/odysseus/updates.md) |
| Voice-turn timeout setting `pipeline_timeout`, default 60 s, was a fixed 20 s (`satellite/SatelliteAudioPipeline.kt`, `settings/Settings.kt`) | agent turns with Athena tool calls on a local model take longer | — |
| Own application id and version; `versionCode` derived from `versionName` (`app/build.gradle.kts`) | never collides with the official build; upstream's counter changed on every Gradle run | [build](docs/odysseus/build.md) |
| Bundled "Hey Athena" microWakeWord model | the Odysseus wake word, trained locally | [wake-word](docs/odysseus/wake-word.md) |
| Wake word training: guided "Hey Athena" takes and, optionally, missed wakes, sent to the hub for retraining | the model was trained on synthetic voices only and misses real ones | [wake-word-training](docs/odysseus/wake-word-training.md) |
| Threshold rounds to the nearest value (`utils/Helpers.kt`) | upstream truncated: 9.2 became 0.91 (general fix, worth offering upstream) | [wake-word](docs/odysseus/wake-word.md#threshold) |
| "Auto" orientation checks for a real accelerometer or gyroscope (`device/ScreenUtils.kt`; `setup_device.sh` locks landscape) | cronos declares an accelerometer it lacks, so the screen stuck in portrait (general fix, worth offering upstream) | — |
| App microphone processing off by default | the Echo's front-end already cleans the audio; doing it twice distorts speech | [echo-show-5](docs/odysseus/echo-show-5.md#microphone-processing) |
| One shared microphone for the app, with raw audio beside processed audio | the recorder needs the mic without Home Assistant, and before processing | [shared-microphone](docs/odysseus/shared-microphone.md) |
| Listening indicator: "Listening…", "Thinking…", "Answering…" and a glowing bar | feedback on a quiet or muted device, without HA setup | [turn-card](docs/odysseus/turn-card.md#listening-indicator) |
| Barge-in: a wake word during a turn ends it at Odysseus and starts a new turn once HA has ended the old run; "Hey Athena, never mind" ends a turn unanswered | upstream only stopped the turn; HA's satellite went deaf if the new run started first; after a barge-in Odysseus answered "never mind" out loud | [turn-card](docs/odysseus/turn-card.md#barge-in) |
| Turn card: what a turn added or completed, its sources, and the question Odysseus waits on | HA passes only the spoken answer to the satellite | [turn-card](docs/odysseus/turn-card.md#the-turn-card) |
| Satellite post and timings: device, area, wake word, optional command audio, stage times | HA tells the agent nothing about the satellite; find the slow step | [turn-card](docs/odysseus/turn-card.md#the-satellite-post-and-check-my-voice) |
| Thinking sound after 5 s without an answer | a long turn otherwise sounds like the device stopped listening | [turn-card](docs/odysseus/turn-card.md#thinking-sound) |
| Answer "yes" without the wake word | a confirmation needed "Hey Athena, yes" when HA didn't keep listening | [follow-up](docs/odysseus/follow-up.md) |
| One hub HTTP path: `OdysseusLink.send` behind the `HubHttp` seam | one tested way to the hub instead of a copy per feature | [turn-card](docs/odysseus/turn-card.md#why-the-device-asks-odysseus-itself) |
| Set up an Echo from a phone with one QR code | typing on the Echo's screen is impractical | [setup](docs/odysseus/setup.md) |
| Home screen: clock styles, weather and the song playing here or elsewhere, dashboard one swipe away | a glanceable idle screen with the dashboard close | [home-screen](docs/odysseus/home-screen.md) |
| Today page: calendar, to-dos and Athena's items from Odysseus | a day overview in the kitchen without asking | [home-screen](docs/odysseus/home-screen.md#today-page) |
| Done buttons on Today rows and reminder cards, with Undo | tick things off without speaking | [home-screen](docs/odysseus/home-screen.md#done) |
| Music page: rooms (every media player; Music Assistant's with library, queue, move and group), player, library and search through Home Assistant; two panes on tablets | control the music in every room from the screen | [music](docs/odysseus/music.md) |
| One Home Assistant client (`odysseus/ha/`: REST and websocket) | live states for the Music page; one tested client instead of one per feature | [home-assistant-client](docs/odysseus/home-assistant-client.md) |
| Kiosk: back from other apps, reload a failed dashboard | unattended screens should always end up on the dashboard | [kiosk](docs/odysseus/kiosk.md) |
| Lock mode: locked outside work hours, PIN to unlock | an Echo in an office shouldn't listen or play after hours | [lock-mode](docs/odysseus/lock-mode.md) |
| Updates from the owner's Odysseus hub | never from the internet; upstream's would install the Firebase build | [updates](docs/odysseus/updates.md) |
| Announcement and reminder cards; hello to the hub | show what was said; the hub learns each Echo's name and area | [announcements](docs/odysseus/announcements.md) |
| Recorder: conversations and voice notes, queued and uploaded to Athena | record for Athena from the Echo, offline-safe | [recording](docs/odysseus/recording.md) |
| Privacy button records (press) and takes voice notes (hold), never sleeps | on cronos it sent KEY_POWER and didn't cut the mic | [recording](docs/odysseus/recording.md#the-privacy-button) |
| Settings managed by Odysseus | set each Echo from the hub's admin page | [managed-settings](docs/odysseus/managed-settings.md) |
| Odysseus look: colours, Fira Code, sailboat icons, name "Odysseus Echo" | the device is part of Odysseus | [look](docs/odysseus/look.md) |
| Sendspin over Tailscale: HA's address and trusted ids pass, art over the connection, goodbye `restart` | MA connects from a 100.x address and can't serve LAN image URLs | [tailscale](docs/odysseus/tailscale.md#music-assistant-sendspin-over-tailscale) |
| Sendspin encryption and pairing (sendspin-cpp v0.9.5) | MA will drop unencrypted legacy clients | [sendspin](sendspin/README.md#encryption-and-pairing-odysseus-fork-sendspin-cpp-v095) |
| Device check script `scripts/odysseus/verify_device.sh` | verify an Echo over adb in one run | [echo-show-5](docs/odysseus/echo-show-5.md#checking-a-device) |
| Screenshot tests on the JVM (Roborazzi, Robolectric) | review UI changes without an emulator | [screenshots](docs/odysseus/screenshots.md) |

What's lost: face-detection motion mode (pixel-difference motion detection still works),
in-app updates from GitHub (updates now come from the owner's Odysseus hub instead), and
upstream's crash reports.

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
