# Building and installing (no root, no Android Studio)

## Toolchain

Installed under `~/tools` without sudo:

| Component | Version |
|---|---|
| JDK | Temurin 21 (`~/tools/jdk-21`) |
| Android SDK cmdline-tools | latest (`~/tools/android-sdk`) |
| Platforms | android-36, android-35 |
| Build tools | 36.0.0 |
| NDK | 28.2.13676358 |
| CMake | 3.22.1 (SDK package) |

`~/tools/android-env.sh` exports `JAVA_HOME`, `ANDROID_HOME`, `ANDROID_SDK_ROOT` and `PATH`.
It does not put the SDK's platform-tools ahead of the system `adb`.

```bash
echo "sdk.dir=$HOME/tools/android-sdk" > local.properties
```

## Build

The build is kept below about 12 GB of RAM: Gradle gets 3 GB, the Kotlin daemon 2 GB, and
it runs at most 6 workers.

```bash
scripts/odysseus/build.sh assembleDebug     # app/build/outputs/apk/debug/odysseus-vaca-<version>-debug.apk
scripts/odysseus/build.sh assembleRelease   # needs KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD
python3 scripts/odysseus/check_apk_privacy.py <apk>   # must pass before installing
```

Every build bumps `version.properties` (`VERSION_CODE`). That is upstream behaviour; commit it
only with a release.

## Install over adb

```bash
adb install -r <apk>
```

This fork has its own package id (`com.msp1974.vacompanion.odysseus`), so it installs next
to the official VACA app rather than over it. The device setup (permissions, launcher, Doze
exemption) is in [echo-show-5.md](echo-show-5.md) and `scripts/odysseus/setup_device.sh`.
