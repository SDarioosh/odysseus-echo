# Screenshot tests (JVM, no emulator)

Compose screens are rendered on the JVM with [Robolectric](https://robolectric.org) (native
graphics) and saved as PNGs with [Roborazzi](https://github.com/takahirom/roborazzi). No emulator
or device is needed, so it works in a sandbox without KVM. Use it to review how a UI change looks.

| Piece | Version |
|---|---|
| Roborazzi (Gradle plugin, `roborazzi`, `roborazzi-compose`) | 1.77.0 |
| Robolectric | 4.17, running Android 16 (SDK 36, `android-all-instrumented:16-robolectric-13921718-i7`) |

All of it is `testImplementation` (plus the `robolectricSdk` configuration and the Roborazzi
Gradle plugin): nothing ships in the APK. Versions are in `gradle/libs.versions.toml`.

## Run

```bash
scripts/odysseus/build.sh :app:recordRoborazziDebug -Pscreenshots    # render and save the PNGs
scripts/odysseus/build.sh :app:compareRoborazziDebug -Pscreenshots    # diff against the last recording
scripts/odysseus/build.sh :app:verifyRoborazziDebug -Pscreenshots     # fail when a screen changed
```

(In the sandbox, through the shared lock: `flock ~/tools/gradle.lock scripts/odysseus/build.sh ...`.)

- Images go to `app/build/outputs/roborazzi/` (under `build/`, so gitignored; never commit
  them), e.g. `player/row-playing-echo-dark.png`. A comparison writes `*_compare.png` beside them.
- The player screens (56 PNGs) take about 15 s of test time, ~30 s for the task when the app is
  already compiled. The first run also downloads the ~200 MB Android framework jar.
- `-Pscreenshots` runs **only** the screenshot tests (package
  `com.msp1974.vacompanion.screenshots`); without it they're excluded. So the plain
  `:app:testDebugUnitTest` stays as fast as before and never starts Robolectric.

How it's wired (`app/build.gradle.kts`, "screenshot tests"):

- `unitTests.isIncludeAndroidResources = true`, so the tests see the app's resources (the Fira
  Code fonts in `res/font`).
- Robolectric runs offline (`robolectric.offline`) on the framework jar Gradle fetches through
  the `robolectricSdk` configuration, so the download goes through Gradle's repositories (and the
  machine's mirror) rather than Robolectric's own fetcher. No repository is added to the project.
- SDK 36 under JDK 21 needs `--add-opens java.base/java.io` and
  `--add-exports java.base/jdk.internal.access`; the test task passes them.
- The test classes use `@Config(application = Application::class)`: the real `VACAApplication`
  (Hilt, services) isn't started.

## Screens

`Screen.EchoShow5` is 960x480 px at **mdpi** (960x480 dp). The Echo Show 5 (2nd gen) panel is
195 dpi; mdpi is the assumption here (check a device with `adb shell wm density`; at 213 dpi it
would be 721x360 dp, then add a `Screen("echo-tvdpi", 721, 360, "tvdpi")`). `Screen.Tablet` is
1280x800 px at mdpi. Every screen is rendered in the app's dark and light theme (`AppTheme`, i.e.
`odysseus/OdysseusTheme.kt`).

## Add a screen

Test classes live in `app/src/test/java/com/msp1974/vacompanion/screenshots/` and carry:

```kotlin
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class MusicShotsTest {
    @Test fun musicPage() = shots("music/page") { MusicPage(fakeState, onControl = { _, _ -> }) }
}
```

The helpers (`Shots.kt`):

- `shots(name, screens = Screen.all) { ... }`: every screen, dark and light, as
  `<name>-<screen>-<dark|light>.png`.
- `shot(name, screen, mode = Mode.DARK) { ... }`: one screen and theme.
- `shot(name, widthDp, heightDp, mode, density = "mdpi") { ... }`: any window size.
- `gradientArt(size = 256)`: fictional cover art (a gradient `Bitmap`).

- `SettledShot(screen, mode)`: a JUnit rule for screens that load their data in effects
  (`LaunchedEffect`, `produceState` collecting a flow). `shot` captures the first frame, before any
  effect ran; and Compose's UI dispatcher is process-wide, so after the first test in a JVM a
  plain activity doesn't recompose at all. The rule renders through the Compose test rule (its
  own frame clock), lets 500 ms of frame time pass, then captures: one screen and theme per
  test, with `ParameterizedRobolectricTestRunner` over `SettledShot.parameters()`. Example:
  `MusicPageShotsTest` (the Music page over a fake `MusicBackend`).

The content is drawn inside `AppTheme` on the theme's background, filling the window. Use only
fictional data ("Harbour Lights" by "The Fictional Band"). Code that reads the clock should take
a fixed one (as `FullPlayer(clock = { 0L })`), so images don't change from run to run.

## Files

- `app/src/test/java/com/msp1974/vacompanion/screenshots/`: `Shots.kt` (the helpers above), the
  `*ShotsTest.kt` classes (`PlayerShotsTest`, `LibraryShotsTest`, `SearchShotsTest`,
  `MusicPageShotsTest`, `MusicTabletShotsTest`, `ClockMusicShotsTest`) and their fixtures.
- `app/build.gradle.kts`: the Roborazzi plugin, `isIncludeAndroidResources`, `robolectricSdk`, and
  the test filter that runs these only with `-Pscreenshots`; versions in `gradle/libs.versions.toml`.
