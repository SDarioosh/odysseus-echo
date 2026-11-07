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

Screenshots of Compose screens without an emulator: [screenshots.md](screenshots.md).

`versionCode` comes from `versionName` (`odysseusVersionName` in `app/build.gradle.kts`):
`0.14.0+odysseus.16` is 1400016. Bump `N` with each release; nothing else needs committing, and
every build of a commit gets the same code (`./gradlew printVersionCode` shows it). Upstream's
`version.properties` counter is gone.

Release builds are minified by R8; debug builds aren't. Every module whose native code looks up
Kotlin classes, methods or fields by name (JNI) needs a keep rule in its `consumer-rules.pro`
(`microfeatures`, `sendspin`, `webrtc-apm`), or the release build fails at runtime with
`NoSuchMethodError` where the debug build works. A new JNI module gets one too, and a change
that only touches native code should be checked on a release build before it goes to devices.

## Install over adb

```bash
adb install -r <apk>
```

This fork has its own package id (`com.msp1974.vacompanion.odysseus`), so it installs next
to the official VACA app rather than over it. The device setup (permissions, launcher, Doze
exemption) is in [echo-show-5.md](echo-show-5.md) and `scripts/odysseus/setup_device.sh`.

## Release builds

Updates from Odysseus ([updates.md](updates.md)) install only over a build signed with the
**same key**. Debug builds are signed with each build machine's own `~/.android/debug.keystore`,
so a debug build from another machine (or after that file is lost) can't update the app. The
Echos therefore run release builds signed with one release key, kept by the owner.

### The release key (once)

```bash
keytool -genkeypair -v -keystore ~/keys/odysseus-echo.jks -alias odysseus-echo \
  -keyalg RSA -keysize 4096 -validity 36500
```

Keep the keystore and its passwords outside the repository and back them up: without them no
future update installs, and every device must be uninstalled again. Never commit them.

```bash
export KEYSTORE_FILE=~/keys/odysseus-echo.jks KEYSTORE_PASSWORD=... KEY_ALIAS=odysseus-echo KEY_PASSWORD=...
scripts/odysseus/build.sh assembleRelease   # app/build/outputs/apk/release/*.apk
```

### Moving a device from a debug build to a release build (once per device)

Android refuses to install a release build over a debug build (different key), so this needs
one uninstall, which **deletes the app's data**: settings, the Odysseus link, the Home
Assistant sign-in, the Sendspin pairing, the microphone calibration.

```bash
adb uninstall com.msp1974.vacompanion.odysseus
scripts/odysseus/setup_device.sh <release apk>   # installs and sets permissions again
```

Then set the Echo up again with the phone QR code (Menu → Odysseus → Set up with phone,
[setup.md](setup.md)): it redoes the Odysseus link and the Home Assistant sign-in. In Home
Assistant the device may need to be re-added or its old entry removed; pair it again in Music
Assistant if Sendspin was paired. From then on, every update keeps all of this.

## Publishing an update

1. Bump `N` in `odysseusVersionName` (`app/build.gradle.kts`), commit, and build:

   ```bash
   scripts/odysseus/build.sh assembleRelease
   ./gradlew -q printVersionName printVersionCode
   ```

2. Check it:

   ```bash
   python3 scripts/odysseus/check_apk_privacy.py app/build/outputs/apk/release/*.apk
   ```

3. Copy it to the server (any path the hub's admin account can read):

   ```bash
   scp app/build/outputs/apk/release/*.apk <server>:/tmp/odysseus-echo.apk
   ```

4. On the server, publish it in Odysseus (odysseus-expanded's `scripts/echo_release.py`, run as
   described in that repo's deploy docs), with the version printed in step 1:

   ```bash
   python scripts/echo_release.py /tmp/odysseus-echo.apk \
     --version-name 0.14.0+odysseus.30 --version-code 1400030 --notes "What changed"
   ```

   It refuses a lower `version_code` than the one already published (unless `--force`).

The devices find it at their next daily check, or at once with Menu → Odysseus → Updates →
**Check now**, download it over Wi-Fi and offer **Install**.
