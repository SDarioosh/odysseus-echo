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
- Package `com.msp1974.vacompanion.odysseus`, version `0.14.0+odysseus.N`. `version.properties`
  is committed with each fork release so `versionCode` only goes up (adb refuses downgrades).

## Changes from upstream

Each change is one commit, and every code change is marked `Odysseus fork` in a comment:
`git grep -n "Odysseus fork"`.

| Change | Why | Files |
|---|---|---|
| Removed Firebase Analytics, Crashlytics, Google ML Kit face detection and Play services | telemetry to Google (events, device name, UUID, crash reports with settings breadcrumbs; ML Kit usage logging) | `build.gradle.kts`, `app/build.gradle.kts`, `gradle/libs.versions.toml`, `AndroidManifest.xml`, `utils/Logger.kt` (no-op `FirebaseManager`), `VACAApplication.kt`, `device/MotionDetectionEngine.kt`, `settings/Settings.kt` |
| No process-wide trust-all TLS | upstream disabled certificate checks for every HTTPS request; a general fix to offer upstream | `VACAApplication.kt` |
| No in-app updates | they would install the official, Firebase-enabled APK | `utils/Updater.kt` |
| Voice-turn timeout configurable (`pipeline_timeout`, default 60 s, was a fixed 20 s) | Odysseus agent turns with Athena tool calls on a local model take longer than 20 s | `satellite/SatelliteAudioPipeline.kt`, `settings/Settings.kt` |
| Own application id and version | never collides with or "updates" to the official build | `app/build.gradle.kts` |

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
