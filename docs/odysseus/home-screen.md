# Home screen: clock, today, now playing, dashboard one swipe away

The Echo shows a clock when nobody is using it. The Home Assistant dashboard is one swipe
away, and the Echo goes back to the clock by itself.

## What you see

- **The clock page**: the weather in the top left (see below), the time, the date, and below them the song that is playing (title,
  artist, artwork) with previous, play/pause and next buttons. The artwork is pushed by Music
  Assistant over the Sendspin connection, so it also shows when the Echo reaches it over Tailscale
  (`sendspin/README.md`). The song comes from Music Assistant through the
  Sendspin player (Configuration → Sendspin). While the Echo plays nothing itself, it shows the
  song another speaker plays instead ([Music on other speakers](#music-on-other-speakers)). With
  nothing playing anywhere, the line is hidden.
- **Swipe right** (finger moves right) on the clock: the Home Assistant dashboard.
- **Swipe left** on the dashboard: back to the clock.
- **Swipe left** on the clock: the **Today page** (see below), when the device is linked to
  Odysseus. Swipe right to go back to the clock.
- **Swipe left** again: the **Music page** ([music.md](music.md)), when Home Assistant is signed
  in and Music Assistant has players (right of the clock when the Today page is off).
- **Idle**: after 2 minutes without a touch, the dashboard (or another page) goes back to the
  clock by itself; the Music page stays while something plays on this device when its "Stay
  while playing here" is on.
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
| Today page | on / off (shown only while linked to Odysseus) | on |
| Music page (Menu → Odysseus → Music, [music.md](music.md)) | on / off (shown only while Music Assistant has players) | on |
| Music on other speakers | on / off (also the dashboard's now-playing card) | on |
| Speaker to follow | Auto (this room first), or one of the listed media players (the Music page's list) | Auto |
| Clock style | a preset, then fonts, colours and elements (below) | Classic |

## Clock style

Menu → Odysseus → Home screen → **Clock style**. Stored on the Echo only (`home_clock_style` in
`odysseus_home`, JSON), not sent to Home Assistant. A change shows as soon as the menu closes.

**Presets** (each a complete style; pick one, then change anything):

| Preset | Look |
|---|---|
| Classic (default) | the clock as it was: the app's colours, Fira Code, time and date in the middle, the now-playing row under them |
| Midnight | near-black, thin Inter, blue accent; next event top right, Today headline under the date, now playing as a pill bottom right |
| Paper | warm off-white, Playfair Display time, weekday above and the date in italics; a Today column on the right (headline, next two events, to-dos to tick off); now playing as one text line |
| Sunset | purple-to-orange gradient, Outfit; time bottom left, a "next up" card top right (the Today headline when nothing timed is left), now playing as a pill top left, large weather bottom right |
| Terminal | Fira Code green on black: `$ date`, `$ next`, `$ today --todo` (to-dos as `[ ]`), and a status bar along the bottom with now playing and the open counts |
| Bold split | a giant Bebas Neue time on the left, a Today card on the right (headline, events, to-dos, Athena's action items), now playing mini bottom left |
| Album | while music plays the blurred cover art is the background and a big player fills the bottom (with progress); DM Serif time top left |
| Nightstand | Classic's layout and font (weather, time, date, now playing) in dim red on black; the night look's default |

"Undo my changes to …" goes back to the picked preset. "(changed)" shows when the style differs from it.

**Theme**: clock font (Fira Code, Inter, Manrope, Outfit, Playfair Display, DM Serif Display,
Bebas Neue; all SIL OFL, licences in `assets/licenses/`), clock weight (thin to medium), text font,
colours (background, an optional gradient end, text, secondary text, accent, cards; picking any
colour switches off the app's own colours), cover art as the background while music plays, and
dimming (off, 80, 60, 40 %).

**Now playing from**: *This device* (Sendspin, default) or *Selected room*, the Music page's room
(Music Assistant through Home Assistant, [music.md](music.md)), so a tablet that plays nothing
itself can show the kitchen's music. It applies to every now-playing look and to the cover-art
background. It's stored in the style JSON (`nowPlayingFrom`: `device`/`room`; missing or unknown
means This device) and kept when a preset or the night look is picked. A tap on the element opens
the Music page. `clock/ClockNowPlaying.kt` turns either source into one `ClockMusic` and owns the
20 s "Paused" linger (per source and room: switching drops the previous track at once); it
follows the room only while the clock shows it and the style draws music (a visible now-playing
element or the cover-art background). With *Selected room* and no Music page, the setting says
"Needs the Music page and Home Assistant". Sendspin's playback speed moves the progress
(`PlayerState.rate`).

**Elements**, each on/off, at one of nine places (a 3×3 grid: top/middle/bottom × left/centre/right),
with a size (S, M, L, XL) and a look where it has several. Elements at the same place stack top to
bottom; ↑ moves one above the one before it.

| Element | Looks |
|---|---|
| Time | plain; weekday above |
| Date | Friday, November 6; FRIDAY · NOVEMBER 6; FRIDAY; November 6; italic (in the clock font); Fri 2026-11-06 |
| Weather | badge (as before); one line; large; in words |
| Now playing | row (as before); pill; mini; text line; bottom bar (full width, along the edge); big player |
| Today's headline | plain (accent); italic (clock font). The Today insight's headline, else its first focus item |
| Next event | compact ("IN 29 MIN / Dentist · 3:30"); card (with the event after it and the open counts); one line |
| Today panel | card; column (with a rule on its left); terminal. Headline, the next two events, up to 3–4 to-dos (tap to mark done, as on the Today page), "and N more", the action items count |
| To-do summary | lines ("Dentist in 29 min", "3 to-dos · 2 action items"); chip |
| Status line | Wi-Fi / Home Assistant problems and do not disturb, only when there is one |
| Record button | the recorder's clock button (when turned on under Recording) |
| Update notice | a downloaded Odysseus update |

**Night look** (off by default; Clock style → Night look): the clock switches to a night preset
(Nightstand unless another is picked) while the room is dark, read from the Echo's ambient light
sensor, and back when the light returns.

- **Dark means**: at or below 2, 5 (default), 10 or 20 lux. The menu shows the live reading to pick from.
- **When**: after 60 s at or below that level it turns on; after 10 s at or above three times the
  level (and at least 8 lux more) it turns off. In between nothing changes, so a passing shadow or the
  screen's own glow doesn't flip it. The rules are `home/clock/NightRules.kt` (plain Kotlin, tested).
- **Lowest screen brightness at night** (on by default once the night look is on): a window
  brightness override while the night look shows on the clock; it goes back when the clock leaves
  the screen (e.g. a swipe to the dashboard) or the night look ends. Home Assistant's brightness and
  auto-brightness settings aren't touched.
- Your own style is kept; the night preset is used as it ships. Logcat: `Night: on (3.0 lux, dark at 5)`.
- `home/clock/NightMode.kt` reads the sensor (only while the feature is on, or the menu is open) and
  holds the state; `HomeHost` composes its driver.

The Odysseus elements use the Today page's data (`TodayRepository`), so they show only while the
device is linked to Odysseus and the Today page is on; nothing extra is fetched. The next event is
one going on now, else the next timed event today (all-day events are skipped).

How it works: `home/clock/ClockStyle.kt` (the model, JSON, `normalized()`: every element type once,
values clamped, unknown fonts and variants fall back), `ClockPresets.kt`, `ClockToday.kt` (next
event, counts; plain Kotlin), `ClockTheming.kt` (hands the style's colours and text font to
`MaterialTheme`, so shared pieces like the weather badge and the Done rows follow it; the
background, with the cover art blurred by drawing a 12×12 copy scaled up, since Android 11 has no
blur effect), `ClockElements.kt` (one composable per element) and `ClockStyleSettingsSection.kt`.
The now-playing looks are the shared player pieces of `odysseus/player/` (`RowPlayer`, `PillPlayer`,
`MiniPlayer`, `LinePlayer`, `BarPlayer`, `FullPlayer`, built from `Artwork`, `Transport`,
`TitleArtist`, `Progress`), drawn over the neutral `music/PlayerState`: `ClockElements` turns the
Sendspin state into one (`toPlayerState`) and sends their controls back as the same Sendspin
commands (`commandFor`); it keeps the clock's own parts, `rememberArtwork`, `nowPlayingVisible`
and the 20 s "Paused" linger.
`ClockPage` lays the elements out in three full-height columns (left, middle, right): each
column's top elements, then its middle ones centred in the space left, then its bottom ones, so
elements on the same side never overlap (a column that is too full pushes its bottom ones off the
screen); the bottom bar is drawn outside the padding.

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
- **Read-only**, through the app's Home Assistant client ([home-assistant-client.md](home-assistant-client.md),
  with the access token the Echo already holds for the dashboard): templates (the entity list;
  condition, temperature, unit and `sun.sun`) and `weather.get_forecasts` with its response (the
  forecast; "not supported" is HTTP 400 over REST, an error over the websocket). Nothing is
  changed in Home Assistant.
- **Failures** hide nothing else: a failed refresh keeps the last reading for up to an hour, then
  the badge disappears. Logcat shows `Weather: ...` lines.

## Music on other speakers

The Echo's Sendspin player only hears about its own Music Assistant group, so music playing on
other speakers never reached it: the clock and the now-playing card stayed empty. Now, while the
Echo itself plays nothing, they show what another speaker plays, and the buttons control that
speaker. The speakers are the Music page's list of media players ([music.md](music.md), "Which
media players are rooms"): every Music Assistant player, and Home Assistant's other media players
(Cast, Sonos, TVs…) while they are on, a native twin of an MA player and the Echos' own VACA media
players left out.

On the clock this applies while its style's **Now playing from** is *This device*; with
*Selected room* the clock follows the Music page's room instead. The Music page itself always
treats only the Echo's own Sendspin player as "this device" (`HomePageContext.nowPlaying`); the
other speakers reach the clock through `clockNowPlaying` / `onClockCommand`.

- **Which speaker** (*Auto*, `OtherPlayersRules.pick`, pure, `OtherPlayersRulesTest`): listed
  speakers that are playing or buffering, or paused in the last 15 minutes (counted from when Home
  Assistant reported the pause, kept while it stays paused), with a title or artist. The Echo's own
  room (the HA `area` the integration pushes) first, then playing before paused, then a Music
  Assistant player before another, then the one updated last. A song playing on several players (a
  group) counts once. *Speaker to follow* can pin one listed player instead; it shows only while
  that one plays or was paused in the last 15 minutes. A player chosen before that is now hidden
  as an MA player's twin no longer shows: choose the MA one.
- **The Echo's own music wins**: while its Sendspin player plays, nothing changes. While it is
  paused, another speaker that is playing takes over the line and the card.
- **Buttons**: play, pause, next, previous, shuffle and repeat, as far as the player supports them
  (`supported_features`), through the Music backend's `control` (`media_player.media_play`,
  `media_pause`, `media_next_track`, `media_previous_track`, `shuffle_set`, `repeat_set`;
  `HaMusicJson.controlCall`). Not while locked (`MusicLocked`). The new state comes back over the
  websocket.
- **Live**: while the app is in front and the screen is on, and the clock or the dashboard's
  now-playing card is turned on; not while locked, not with the setting off. `OtherPlayers.Driver`
  then collects `HaMusicBackend.rooms` and `.live` (which keeps them live: the rooms template every
  minute and Home Assistant's websocket on every media player, the same subscription the Music page
  uses), and picks again on every change and every 30 s. A speaker turned on shows at its next
  state; one newly added to Home Assistant within a minute. Whatever stops it forgets the speaker,
  so an old song doesn't flash up when the screen comes back. (It used to be a template every 4 s.)
- **Other Echos**: another Echo's Sendspin player is a speaker like any other (often in this
  room), so it can show here and its buttons control it. The Music page's rule that its room guess
  never claims another Echo's player (`isOtherVacaPlayer`) is about which player is *this*
  device, which this feature never decides; this Echo's own music always wins (above).
- **Artwork**: the player's `entity_picture_local` or `entity_picture`, through Home Assistant's own
  media proxy (it carries its own access token). A picture URL on another host (Music Assistant's
  LAN address) may not load over Tailscale; the music-note placeholder shows then.
- **Time**: the upstream card draws `SendspinNowPlaying` on `elapsedRealtime`; the one conversion
  from Home Assistant's wall clock is `OtherPlayersRules.toNowPlaying` (`player/PlayerClock`).
- **Home Assistant**: no change and no re-pairing. It goes through the app's Home Assistant
  client (`HaClient`, [home-assistant-client.md](home-assistant-client.md)) with the access token
  the Echo already holds for the dashboard. The template needs an administrator; for another user
  the list comes from `/api/states` (no areas, so "this room first" doesn't apply).
- **Failures**: Home Assistant unreachable (the rooms template failing) for over 30 seconds hides
  the speaker. Logcat shows `Other players: ...` lines.

## Today page

Page 1, right of the clock. From Odysseus (`GET <voice base>/today`, the same base URL and
voice token as Menu → Odysseus; contract C1 in
[plans/2026-11-today-updates-announce-record.md](plans/2026-11-today-updates-announce-record.md)).
To-dos and action items can be marked done from it (below); everything else is read-only.

- **Top**: "Today", the date, and the headline of Odysseus's Today insight (without a headline,
  its first focus item; with neither, nothing).
- **Left, Calendar**: today's events, time (in the device's time zone) or "All day", title and
  place. Events that have ended are dimmed.
- **Right, To-do**: to-dos due today or overdue (an "Overdue" or "Today" tag), then pinned
  undated ones. **From conversations**: Athena's open action items.
- **"and N more"** where a section doesn't fit, from the hub's open counts. How many rows fit is
  worked out from the screen height (calendar rows of about 40 dp, to-do and action item rows
  48 dp because they can be tapped; titleLarge text, readable from across a kitchen on the Echo
  Show 5's 960×480 screen); action items keep at least one row.
- **Empty sections** say so ("Nothing on the calendar", "Nothing due", "No open action items").
- **Refresh**: when the home screen comes up, every 15 minutes while it's on (counted from the
  last try, failed or not), within 5 minutes after midnight, when the network comes back, and
  when the page comes into view (unless the data is under a minute old). Stops while the screen
  is blank.
- **Failures**: the last result stays for up to 2 hours, with "Odysseus unreachable · Updated
  8:15" at the bottom (also while the device has no network); after that, or with nothing
  fetched yet, the page says "Can't reach Odysseus" and why ("Odysseus refused the token (401): link the device again", "HTTP 503",
  or the network error). A result from before midnight is dropped; midnight and
  "Today"/"Overdue" follow the hub's day (the offset of its `generated` time and its `date`), so
  a device left on another time zone still shows the current result. Logcat shows `Today: ...`
  lines.
- **Events over midnight**: one that began yesterday shows when it ends ("–6:00"), or "All day"
  when it doesn't end today either.
- **Not linked**, or the setting off: the page isn't in the list, so the clock is the last page.
  Linking or unlinking in Menu → Odysseus takes effect when the menu closes.

### Done

Each to-do and action item row starts with a ring. A tap anywhere on the row (48 dp tall, the
whole column wide, so it's easy to hit from arm's length) marks it done. The tap is the
confirmation: no spoken "yes" (contract C5 in
[plans/2026-11-done-followup-settings-verify.md](plans/2026-11-done-followup-settings-verify.md)).

- **At once** the ring fills with a tick and the title is struck through (optimistic), and the
  device sends `POST <voice base>/todos/done {"ref", "device_id", "title"}` (`title`: the
  row's text, so a hub whose line moved since finds it by its text) or
  `/action-items/done {"id", "device_id"}`; `device_id` is the device's id (`config.uuid`, as in
  the announcements' hello).
- **Done**: the row shows "Done · Undo" for 10 s. A tap on the row undoes it
  (`/todos/undo {"ref"}`, `/action-items/undo {"id"}`, also with `device_id`): "Undoing…", then
  the row is open again. "Too late to undo" (409) leaves it done. After the 10 s the row goes and
  the Today data is refreshed; a refresh that comes earlier doesn't move it (the row is kept in
  place until its window ends).
- **Already done** (`"already": true`): "Already done" for 5 s, no Undo.
- **Failure**: the row comes back open, with a short reason in place of its due tag for 5 s
  ("Can't reach Odysseus", "Not found in Odysseus" for any 404, "Athena unavailable", "Token refused",
  "Odysseus busy, try again" for a 503 `busy`, "Changed in Odysseus" for a 409 `changed`: the
  line was edited or removed in Odysseus since Today was fetched, "Odysseus error (n)" for
  anything else), and the Today data is refreshed. "Changed in Odysseus" is a
  done's answer; "Too late to undo" is the undo's (both 409, told apart by `error`).
  Tap again to retry.
- A row the hub sent without a `ref` or `id` has a dimmed ring and can't be tapped.
- Logcat: `Today: done todo:41:2 -> ok`, `Today: undo ai_17 -> TOO_LATE (Too late to undo)`
  (refs and ids only, never titles).

The rules (optimistic state, undo window, keeping a done row in place) are in
`odysseus/done/DoneMarks.kt`, plain Kotlin and unit-tested; `today/TodayDone.kt` sends the
requests, runs the clock and refreshes. A reminder card's Done
([announcements.md](announcements.md)) uses the same `odysseus/done/` client.

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
- After the idle time without a touch, any other place goes back to page 0, unless the page
  shown is idle-exempt now (`HomePage.idleExempt()`, the Music page while music plays here); the
  idle time starts again when the exemption ends.
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
2. Add it to `HomePages.all` (a getter, so a page can depend on a setting, as Today does). Its
   place in the list is its place on screen.
3. If it needs data the pages don't have yet, add a field to `HomePageContext` and fill it in
   `HomeHost`. Pages never read the view model directly.

Pages must not handle horizontal swipes; `HomeHost` does. Navigation, idle return and the
animation need no change.

`HomeHost` composes the page shown and the one after it (so a swipe can reveal it), so a page that
follows something live (a Home Assistant subscription) should stay idle while it is out of sight:
read `LocalHomeStripPosition` (the strip's position, null outside `HomeHost`) in a
`derivedStateOf`, as the Music page does (`MusicPageLive`).

## Files

| File | What |
|---|---|
| `odysseus/home/HomeNavigator.kt` | navigation rules and idle deadline, plain Kotlin |
| `odysseus/home/HomeScreenController.kt` | the process-wide state; hook target for the dashboard's gestures |
| `odysseus/home/HomeSettings.kt` | on/off and idle time (SharedPreferences `odysseus_home`) |
| `odysseus/home/HomePage.kt` | `HomePage`, `HomePageContext`, the page list `HomePages` |
| `odysseus/home/ClockPage.kt` | the clock page: lays out the style's elements |
| `odysseus/home/clock/ClockStyle.kt` | the clock style model, JSON and `normalized()`. Plain Kotlin |
| `odysseus/home/clock/ClockPresets.kt` | the eight presets |
| `odysseus/home/clock/ClockToday.kt` | next event, open counts, headline for the clock. Plain Kotlin |
| `odysseus/home/clock/ClockTheming.kt` | fonts, colours handed to Material, the background |
| `odysseus/home/clock/ClockElements.kt` | one composable per element and look |
| `odysseus/player/` | the shared player pieces over `music/PlayerState`, Sendspin mapping, `PlayerClock`, `PlayerMetrics`, `VolumeBar`, `RoomChip` |
| `odysseus/home/clock/NightRules.kt`, `NightMode.kt` | the night look: when (plain Kotlin), light sensor, backlight |
| `odysseus/home/clock/ClockStyleSettingsSection.kt` | Menu → Odysseus → Home screen → Clock style |
| `res/font/clock_*.ttf` | the clock fonts (SIL OFL, `assets/licenses/*-OFL.txt`) |
| `odysseus/home/weather/WeatherClient.kt` | the Home Assistant calls for the weather (over `HaApi`) |
| `odysseus/home/weather/WeatherForecasts.kt` | today's high/low and condition → icon, plain Kotlin |
| `odysseus/home/weather/WeatherRepository.kt` | the process-wide weather state and refresh |
| `odysseus/home/weather/WeatherBadge.kt` | the badge in the clock's top left |
| `res/drawable/odysseus_mdi_*.xml` | weather icons from @mdi/js 7.4.47 (`assets/licenses/MDI-LICENSE.txt`) |
| `odysseus/home/players/OtherPlayersRules.kt` | music on other speakers: which speaker, pause time, buttons → controls, the one `PlayerState` → `SendspinNowPlaying` adapter. Plain Kotlin |
| `odysseus/home/players/OtherPlayers.kt` | the process-wide state, the `Driver` over the Music list (`HaMusicBackend.rooms` / `.live`, [music.md](music.md)), and where a button press goes |
| `odysseus/home/today/TodayClient.kt` | contract C1: the call (through `HubHttp`, faked in tests) and lenient parsing |
| `odysseus/home/today/TodayStore.kt` | what the page shows: not linked, loading, unreachable, ready/stale; the 2 h rule. Plain Kotlin |
| `odysseus/home/today/TodayFormat.kt` | times in the device's zone, due tags, "and N more", what fits. Plain Kotlin |
| `odysseus/home/today/TodayRepository.kt` | the process-wide state and refresh; `OdysseusLink` (the device's `HubHttp`) for the call |
| `odysseus/home/today/TodayRefresh.kt` | the refresh timer, composed by `HomeHost` |
| `odysseus/home/today/TodayPage.kt` | the page, with its Done rows |
| `odysseus/home/today/TodayDone.kt` | the Done buttons: requests, the undo clock, refresh afterwards |
| `odysseus/done/DoneApi.kt` | contract C5: paths, bodies, what an answer means. Plain Kotlin |
| `odysseus/done/DoneClient.kt` | the Done/Undo requests (through `HubHttp`, `OdysseusLink` on the device) |
| `odysseus/done/DoneMarks.kt` | the optimistic Done state of the rows and the undo window. Plain Kotlin |
| `odysseus/home/today/TodaySettingsSection.kt` | the "Today page" setting |
| `odysseus/home/music/` | the Music page (files in [music.md](music.md#files)) |
| `odysseus/home/HomeHost.kt` | draws the layers, swipes, animation, idle timer |
| `odysseus/OdysseusSettingsLayout.kt` | the Home screen settings |
| `MainActivity.kt`, `ui/layouts/WebViewLayout.kt` | hooks (marked `Odysseus fork`) |
| `app/src/test/.../odysseus/home/HomeNavigatorTest.kt` | tests of the rules |
| `app/src/test/.../odysseus/home/clock/{ClockStyle,ClockToday,NightRules}Test.kt` | tests of the style model, presets, JSON and the clock's Today labels |
| `app/src/test/.../odysseus/home/weather/WeatherClientTest.kt` | tests of the weather calls (a fake `HaApi`) and rules |
| `app/src/test/.../odysseus/home/players/OtherPlayersRulesTest.kt` | tests of the other-speaker picks, pause times, buttons and the adapter |
| `app/src/test/.../odysseus/home/today/Today{Client,Store,Format}Test.kt` | tests of the Today parsing, staleness and layout rules |
| `app/src/test/.../odysseus/done/Done{Api,Marks}Test.kt` | tests of the Done requests, answers and row states |
