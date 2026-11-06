# Updates from Odysseus

The app updates itself from the owner's own Odysseus hub, never from the internet. Upstream's
GitHub updates stay off (`utils/Updater.kt`, `inAppUpdatesEnabled = false`): they would install
the official, Firebase-enabled APK. This is a separate path in `odysseus/update/`.

The hub side is in odysseus-expanded (`fork/voice/app_release.py`, `scripts/echo_release.py`).
The contract between the two is C2 in
[plans/2026-11-today-updates-announce-record.md](plans/2026-11-today-updates-announce-record.md).

## How it works

1. **Check.** `GET <voice base>/app/latest` with the voice token (Menu → Odysseus link), a
   minute after the app starts, then once a day, and on **Check now**. A check that failed, or
   found an update it couldn't download yet, is retried after an hour.

   ```json
   {"app": "odysseus-echo", "version_name": "0.14.0+odysseus.30", "version_code": 1400030,
    "size": 31457280, "sha256": "9f2c...", "notes": "Kiosk settings; Today page."}
   ```

   A 404 means the hub has no release. The device compares **`version_code`** with its own
   (`BuildConfig.VERSION_CODE`), never the name: the semver library ignores `+odysseus.N`.
2. **Download.** A newer version is downloaded from `GET <voice base>/app/apk` with the Bearer
   token into the app's private storage (`files/odysseus_update/`), not with Android's
   DownloadManager, which can't send the token safely and would leave the file readable by
   other apps.
   - Only over Wi-Fi (or Ethernet).
   - A download that was cut off resumes with `Range: bytes=N-` at the next try; a hub that
     answers 200 instead of 206 is fine, it starts over.
   - The file must match the announced size and sha256, or it is deleted.
   - After a failed download the same version is tried again at most once a day. **Check now**
     tries again at once.
   - Older downloads are deleted. The file is kept until the version is installed.
3. **Offer.** "Update ready: <version>" with **Install** in Menu → Odysseus → **Updates**, and a
   small banner at the top right of the clock page. The × hides the banner for that version;
   the settings still offer it.
4. **Install.** Before Android sees it, the app checks the APK again: its sha256, that it is
   this app's package, that its version is the one the hub announced and newer than the
   installed one, and (Android 9+) that it is signed with the same key. Then it is handed to
   Android's `PackageInstaller` in a session, and **Android asks to confirm**. The app is the
   home app and a device admin, not the device owner, so it can't install silently (decided in
   the plan). Android stops the app to replace it; as the home app it comes straight back. On a
   tablet where it isn't the home app, open it again.

An update keeps everything: settings, the Odysseus link and its token, the Home Assistant
sign-in, the Sendspin pairing. That holds only while the new APK is signed with the same key
as the installed one; see [build.md](build.md#release-builds).

## Install unknown apps

Android lets an app install other APKs only once "Install unknown apps" is allowed for it.
Without it, **Install** opens that screen (Settings → Apps → special access → Install unknown
apps → Odysseus Echo); allow it, come back and press **Install** again. The device setup
(`scripts/odysseus/setup_device.sh`) allows it over adb:

```bash
adb shell appops set com.msp1974.vacompanion.odysseus REQUEST_INSTALL_PACKAGES allow
```

On Android 11 (the Echo Show 5's LineageOS 18.1) Android may restart the app when this is
changed in its Settings screen; the adb line avoids that.

## Logs

`adb logcat | grep "Update:"`:

- `Update: up to date (1400025, hub 1400025)`, `Update: the hub has no release`
- `Update: 0.14.0+odysseus.30 (1400030) offered; download GO` (or `NOT_WIFI`, `FAILED_RECENTLY`)
- `Update: 0.14.0+odysseus.30 downloaded and verified`
- `Update: install session 12 for 0.14.0+odysseus.30 committed`, then `Update: installer status -1`
  (Android's prompt), `3` (cancelled), `5` (different key or conflict)
- after the restart: `Update: asked Android to install 1400030; running 1400030 (updated)`

## Publishing a release

The steps are in [build.md](build.md#publishing-an-update): build a release APK, check it,
copy it to the server, run `scripts/echo_release.py` on the hub. Each release needs a higher
`N` in `odysseusVersionName`, or the device sees nothing new.

## Code

| File | What |
|---|---|
| `odysseus/update/AppRelease.kt` | contract C2's JSON, checks a release is usable |
| `odysseus/update/UpdatePolicy.kt` | when to check, whether to download, resume point, APK checks, installer messages (tested) |
| `odysseus/update/UpdateClient.kt` | `/app/latest` and the download: resume, size and sha256 (tested with a fake hub) |
| `odysseus/update/LinkUpdateHttp.kt` | HTTP over `OdysseusLink.openGet` (base URL and voice token) |
| `odysseus/update/UpdateManager.kt` | the schedule and state (Compose), started from `VACAApplication` |
| `odysseus/update/UpdateInstaller.kt` | `PackageInstaller` session and `UpdateInstallReceiver` (manifest, not exported) |
| `odysseus/update/UpdateSettings.kt`, `UpdateBanner.kt` | Menu → Odysseus → Updates; the clock banner |

Tests: `UpdatePolicyTest`, `UpdateClientTest` (`./gradlew :app:testDebugUnitTest`).

## Not done

- Updating while nobody is there to tap Install (needs device-owner mode; decided against).
- On Android 12 and later, once this app has installed itself, later updates could skip the
  prompt (`setRequireUserAction` with `UPDATE_PACKAGES_WITHOUT_USER_ACTION`). The Echo runs
  Android 11, so this isn't used.
- Checking after the hub greeting (`satellite/hello`) comes with the announcements phase (E3).
