# Home screen: clock, now playing, dashboard one swipe away

The Echo shows a clock when nobody is using it. The Home Assistant dashboard is one swipe
away, and the Echo goes back to the clock by itself.

## What you see

- **The clock page**: the weather in the top left (see below), the time, the date, and below them the song that is playing (title,
  artist, artwork) with a play/pause button. The song comes from Music Assistant through the
  Sendspin player (Configuration → Sendspin). With nothing playing, the line is hidden.
- **Swipe right** (finger moves right) on the clock: the Home Assistant dashboard.
- **Swipe left** on the dashboard: back to the clock.
- **Idle**: after 2 minutes without a touch, the dashboard goes back to the clock by itself.
- **Menu from the clock**: two-finger swipe up.
- The listening indicator and the turn card show over the clock as they do over the dashboard.

## Settings

Menu → Odysseus → **Home screen**. Stored on the Echo only, not sent to Home Assistant.

| Setting | Choices | Default |
|---|---|---|
| Home screen | on / off (off: the dashboard only, as upstream) | on |
| Back to the clock after | 1, 2, 5, 10 min, Never | 2 min |
| Weather | on / off | on |
| Weather entity | Auto, or one of Home Assistant's `weather.*` entities | Auto |

## Weather

The clock page's top left shows Home Assistant's icon for the current condition (the same
Material Design Icons as Home Assistant's weather card, with its night variants for sunny and
partly cloudy after sunset), the temperature in whole degrees, and today's high and low small
under it.

- **Source**: a Home Assistant `weather.*` entity. *Auto* takes the first available one by entity
  id; the setting lists every weather entity Home Assistant reported at the last refresh.
- **High and low**: today's entry of the daily forecast (the first entry if the forecast starts
  tomorrow); without a daily forecast, today's entries of the twice-daily one. If the entity has
  neither, only the temperature shows.
- **Refresh**: when the clock comes up, every 15 minutes while the home screen is on, when Home
  Assistant reconnects and when the setting changes.
- **Read-only**, with the access token the Echo already holds for the dashboard:
  `POST /api/template` (the entity list; condition, temperature, unit and `sun.sun`) and
  `POST /api/services/weather/get_forecasts?return_response` (the forecast). Nothing is changed in
  Home Assistant.
- **Failures** hide nothing else: a failed refresh keeps the last reading for up to an hour, then
  the badge disappears. Logcat shows `Weather: ...` lines.

## How it works

Two layers:

1. **The dashboard** (the upstream `WebViewScreen`) is always composed underneath, so Home
   Assistant stays connected and live while the clock is showing. Navigation and voice
   commands from Home Assistant still reach it.
2. **Native pages** (`HomePage`, the clock first) are drawn on top. Hiding them reveals the
   dashboard.

Where the screen is, left to right:

```
[Dashboard]  ⟵ swipe right ⟶  [page 0: clock]  [page 1]  …
             ⟵ swipe left  ⟶
```

`HomeNavigator` holds the rules, in plain Kotlin with unit tests (`HomeNavigatorTest`):

- Swipe right shows what is to the left; swipe left shows what is to the right.
- Swipe right on the dashboard, or left on the last page, does nothing and isn't consumed.
- After the idle time without a touch, any other place goes back to page 0.
- Home screen off, or no pages: always the dashboard; no swipe is consumed.

`HomeScreenController` is the one process-wide holder of that state, as Compose state. It
survives the screen going blank. `HomeHost` draws the layers, slides the pages in and out,
reports every touch as activity and runs the idle timer.

On the dashboard, a one-finger left or right swipe is caught by the existing
`WebViewGestureDetector` and handed to `HomeScreenController.onDashboardGesture`. A swipe that
moves the home screen is **not** sent to Home Assistant as a gesture event. Trade-off: a
dashboard card that scrolls sideways (a horizontal stack, a swipe card) may also move the
home screen when the swipe is long. Turn the home screen off if a dashboard needs those.

## Adding a page

1. Write an `object` implementing `HomePage` in `odysseus/home/` (an `id` and a
   `Content(context, modifier)` composable that fills the screen).
2. Add it to `HomePages.all`. Its place in the list is its place on screen.
3. If it needs data the pages don't have yet, add a field to `HomePageContext` and fill it in
   `HomeHost`. Pages never read the view model directly.

Pages must not handle horizontal swipes; `HomeHost` does. Navigation, idle return and the
animation need no change.

## Files

| File | What |
|---|---|
| `odysseus/home/HomeNavigator.kt` | navigation rules and idle deadline, plain Kotlin |
| `odysseus/home/HomeScreenController.kt` | the process-wide state; hook target for the dashboard's gestures |
| `odysseus/home/HomeSettings.kt` | on/off and idle time (SharedPreferences `odysseus_home`) |
| `odysseus/home/HomePage.kt` | `HomePage`, `HomePageContext`, the page list `HomePages` |
| `odysseus/home/ClockPage.kt` | the clock page |
| `odysseus/home/weather/WeatherClient.kt` | the Home Assistant calls for the weather (testable `HaHttp`) |
| `odysseus/home/weather/WeatherForecasts.kt` | today's high/low and condition → icon, plain Kotlin |
| `odysseus/home/weather/WeatherRepository.kt` | the process-wide weather state and refresh |
| `odysseus/home/weather/WeatherBadge.kt` | the badge in the clock's top left |
| `res/drawable/odysseus_mdi_*.xml` | weather icons from @mdi/js 7.4.47 (`assets/licenses/MDI-LICENSE.txt`) |
| `odysseus/home/HomeHost.kt` | draws the layers, swipes, animation, idle timer |
| `odysseus/OdysseusSettingsLayout.kt` | the Home screen settings |
| `MainActivity.kt`, `ui/layouts/WebViewLayout.kt` | hooks (marked `Odysseus fork`) |
| `app/src/test/.../odysseus/home/HomeNavigatorTest.kt` | tests of the rules |
| `app/src/test/.../odysseus/home/weather/WeatherClientTest.kt` | tests of the weather calls and rules |
