# Kiosk: back from other apps, reload a failed dashboard

Two features for wall tablets and Echo Shows that nobody looks after: the device always ends
up back on the dashboard, and never sits on an error page.

## Settings

Menu → Odysseus → **Kiosk**. Stored on the device only, not sent to Home Assistant.

| Setting | Choices | Default |
|---|---|---|
| Reload a dashboard that failed to load | on / off | on |
| Back from other apps after | 1, 2, 5, 10, 30 min, Never | 10 min |

## Back from other apps

When another app is in front (opened from a dashboard link, the app launcher, or Android's
settings) for the chosen time, the app brings itself back to the front, on the clock or
dashboard as it was.

- The timer starts when the app leaves the front and stops when it comes back. Android can't
  tell an app whether someone is still using another app, so the time counts from leaving, not
  from the last touch: someone watching a video in another app is taken back after the chosen
  time too. Pick a longer time (or Never) on a device where that matters.
- **Not while the screen is off.** If the time runs out while the screen is off, the app comes
  back within 30 s of the screen turning on.
- Not after closing the app on purpose (Close app, a restart).
- MainActivity is `singleTask`, so coming back also closes anything opened on top of it in its
  own task.
- **Android 10 and later** only let an app open itself from the background in some cases.
  Being the device's home app (the setup sets this) should be one of them; "Display over other
  apps" is another. If Android refuses, logcat shows `Kiosk: couldn't come back to the front`
  and nothing else happens. Untested on a device so far.
- Logcat: `Kiosk: another app was in front for N s, coming back`.

## Reload a failed dashboard

When the dashboard fails to load, the app reloads it by itself:

- **What counts as failed**: the error page ("Unable to load the Home Assistant web page") is
  showing. A server error (HTTP 500 or higher) for the dashboard page itself, as a reverse proxy
  returns while Home Assistant restarts, now shows that error page too instead of the proxy's
  own page. Sign-in problems don't count: a reload can't fix those.
- It checks what the WebView shows (every 2 s) rather than only the page state, because after a
  failed load the sign-in step can still mark the page "authorised".
- **When**: 5 s after the failure, then twice as long each time (10 s, 20 s, …), up to every
  5 minutes. It starts again from 5 s when a page loads, when Wi-Fi comes back, and when the
  dashboard comes back on screen (after the screen was off).
- **No reload without Wi-Fi**: it waits for the network, then reloads soon after.
- Only while the dashboard is on screen (also under the clock page, where the dashboard stays
  live). A blanked screen doesn't reload.
- Upstream already reloads a crashed page, and a failed page when Home Assistant reconnects;
  this adds the retries in between.
- Logcat: `Kiosk: dashboard failed to load, reloading in N s`.

## Code

All in `app/src/main/java/com/msp1974/vacompanion/odysseus/kiosk/`:

| File | What |
|---|---|
| `KioskSettings.kt` | the two settings (SharedPreferences `odysseus_kiosk`) |
| `ReturnPolicy.kt` | when to come back (plain Kotlin, `ReturnPolicyTest`) |
| `ReturnToDashboard.kt` | watches MainActivity start/stop and brings it back |
| `ReloadBackoff.kt` | when to reload (plain Kotlin, `ReloadBackoffTest`) |
| `DashboardAutoReload.kt` | runs the reloads while the dashboard is on screen |

Hooks: `VACAApplication.kt` (installs `ReturnToDashboard`), `ui/layouts/WebViewLayout.kt`
(composes `DashboardAutoReload`), `utils/CustomWebViewClient.kt` (`onReceivedHttpError`: a
server error for the page shows the error page, like a connection error); settings in `odysseus/OdysseusSettingsLayout.kt`.

## To test on a device

1. Kiosk → Back from other apps after 1 min. Open another app (Android settings), leave it:
   the app is back within about a minute.
2. Same, but turn the screen off before the minute is up and on again after: the app comes
   back within 30 s of the screen coming on.
3. Stop Home Assistant (or unplug the network), reload from the quick actions: the error page
   shows, then logcat shows reloads at 5 s, 10 s, 20 s… Start HA again: the dashboard comes
   back by itself.
