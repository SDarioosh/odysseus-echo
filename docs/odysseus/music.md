# Music page

A native Music page on the home screen: what plays in each room, volume, transport, moving music
between rooms, grouping rooms, and the Music Assistant (MA) library. Plan:
`docs/odysseus/plans/2026-11-music.md` (contracts I1–I3, C7, C8; phases E10–E15). The page and
its tabs are E13; library and search (E14) add tabs.

## What you see

The Music page is right of the Today page on the home screen (right of the clock when the Today
page is off): swipe left from the clock. It is offered only while the Music page is on, Home
Assistant is signed in and Music Assistant has at least one player.

- **A rail of tabs** on the left (icon and a short label); tap one to switch. The tab chosen is
  kept until the app restarts. Default: **Playing**.
- **Playing** (now playing): the selected room's player: big artwork, title, artist, progress,
  previous / play-pause / next, shuffle, repeat and volume; what plays now and next and "N in the
  queue"; a room chip to switch rooms. This device's own room may use the artwork Music Assistant
  pushes over Sendspin.
- **Rooms**: every Music Assistant room with what it plays and a volume control, this device
  first, marked "This Echo". Tap a room to select it; "Move here" moves what plays in another room
  to this one; "Group with…" joins rooms (only where the player can group).
- **Library**: playlists, albums, artists, radio, tracks, podcasts and audiobooks, sorted by
  recent, name or added, favourites only if you like; artists and albums open a detail list.
- **Search**: type two letters or more; results by kind.
- **The play menu** on any library or search item: play now, play next, add to queue, replace
  the queue, radio from this, in the selected room or another.
- **Tablets** (1100×600 dp and up): two panes, the tab on the left and the selected room's player
  on the right. The Echo Show 5 keeps one pane.
- **A line at the bottom** for a few seconds when an action fails: "Locked" while Lock mode is on
  (the lock screen covers the page anyway), "Home Assistant offline", or the action and a short
  reason.
- **Home Assistant offline**: the last state stays, greyed, with "Home Assistant offline" at the
  bottom.
- **Idle**: like any page, back to the clock after the idle time, unless "Stay while playing
  here" is on and something plays on this device.

## Settings

Menu → Odysseus → **Music**. Stored on the Echo (SharedPreferences `odysseus_music`), not sent
to Home Assistant.

| Setting | Choices | Default | Hub key (C7) |
|---|---|---|---|
| Music page | on / off (shown only while Music Assistant has players) | on | `music.enabled` |
| Stay while playing here | on / off: the page doesn't go back to the clock while something plays on this device | off | `music.stay_while_playing` |
| This device's player | Auto (shows the player found and how: player id, old player id, name, or a guess from the area), or one of the rooms | Auto | on-device only |

Set by Odysseus (`docs/odysseus/managed-settings.md`): the switch is disabled with "Set by
Odysseus" under it. A chosen player that Home Assistant no longer has is listed as "(gone)" with a
warning; Auto is used until another is chosen.

## How it works: the backend

The device talks to MA **through Home Assistant**, never to MA directly: HA's MA integration has
everything the page needs, the device already holds a HA token (admin, from the phone setup), and HA
is reachable over Tailscale where MA's LAN address isn't. Code: `app/src/main/java/com/msp1974/vacompanion/odysseus/music/`.

- `MusicBackend` (`MusicBackend.kt`, contract I2) is what the page talks to. Models: `Room`,
  `PlayerState` (`PlayerState.kt`, shared with `odysseus/player/`), `MediaItem`, `QueueSummary`,
  `LibraryPage`. Another backend later (MA's own API via the hub for the full queue, a radio-only
  backend) is a new class.
- `HaMusicBackend` implements it over `HaApi` (`odysseus/ha/`, contract I1). All JSON in and out is
  in `HaMusicJson` (pure, tested on fictional recorded answers in
  `app/src/test/resources/odysseus/music/`).

| Backend call | Home Assistant |
|---|---|
| `rooms` | the rooms template (`HaMusicJson.ROOMS_TEMPLATE`, fixed text, no user input) at once, every minute and when a player appears or goes away (at most every 10 s); between runs `HaApi.watch()` on the players for names, availability and group members |
| `player(id)` | `watch()` attributes: title, artist, album, duration, position (+ `media_position_updated_at`), shuffle, repeat, volume, mute, `entity_picture_local` ?: `entity_picture` (a HA path, loaded with `HaApi.bytes`), `supported_features` → `can` |
| `queue(id)` | `music_assistant.get_queue` (return_response, keyed by entity id); "No active queue" → null |
| `library` | `music_assistant.get_library` `{config_entry_id, media_type, order_by, favorite, offset, limit}`; `favorite` is sent only when true (MA reads `false` as "not favourites"); `hasMore` while a full page came back |
| `search` | `music_assistant.search` `{config_entry_id, name, media_type: [...], limit}`; blank text asks nothing |
| `play` | `music_assistant.play_media` `{entity_id, media_id: uri, media_type, enqueue: play/next/add/replace, radio_mode}` |
| `control` | `media_player.media_play` / `media_pause` / `media_next_track` / `media_previous_track` / `media_seek` / `shuffle_set` / `repeat_set` / `volume_set` / `volume_mute`; `GROUP` = `join` |
| `transfer` | `music_assistant.transfer_queue` target = to, `source_player` = from, `auto_play: true` (same room: nothing) |
| `group` / `ungroup` | `media_player.join` (`group_members`, the leader left out) / `media_player.unjoin` |

- `config_entry_id` comes with the rooms template (run once first if needed).
- **Not an admin token**: HA refuses the template (401/403); the rooms then come from
  `GET /api/states` (players with `mass_player_type`), without areas, identifiers or the config
  entry (so no ID match and no library until the token is an admin's).
- **Lock mode**: `play`, `control`, `transfer`, `group` and `ungroup` throw `MusicLocked` while
  `LockMode.isLocked`, before anything is sent. Reading (rooms, player, queue, library, search) works.
- **Offline**: a failed rooms run keeps the last rooms and sets `HaMusicBackend.reachable` to false
  (the page greys them).
- **Images** (C8): `entity_picture*` are HA paths. Library and search `image` URLs point at MA's LAN
  `imageproxy` or a streaming CDN, so they become `<voice base>/music/image?u=<url-encoded>&size=256`
  (`HaMusicJson.hubImageUrl`): the hub fetches them. Not linked to Odysseus, or no image: null, the
  page shows a placeholder. The device never loads images from the internet.

### This device's own player (contract I3)

`matchSelf` in `SelfPlayer.kt` (pure, `SelfPlayerTest`), on every rooms update. Rules in order:

0. **Override**: the on-device setting "This device's player" (`MusicSettings.selfOverride`), while
   it is still one of the rooms. If it isn't, the rules below carry on and the match says
   `staleOverride`.
1. **ID**: a player whose HA device identifiers contain `["music_assistant", <Sendspin client id>]`
   (MA's player id is the Sendspin client id). The normal case; survives renames. The client id
   is remembered (`MusicSettings.lastClientId`) for while Sendspin is off.
2. **LEGACY_ID**: `["music_assistant", "vaca-<uuid>"]` (the id before Sendspin v0.9 derived it from
   the device key), only if that player is available.
3. **NAME**: exactly one player named `VACA <uuid> Sendspin Player` (any case).
4. **AREA**: exactly one player of type `player` in this device's area (`config.area`). A guess; the
   page shows it as one ("Kitchen speaker?"). Other devices' Sendspin players are left out (Music
   Assistant's default name `VACA … Sendspin Player`, or a `vaca-` or 43-character key id that isn't
   this device's), so two Echos in one room never claim each other's player.
5. Otherwise none: no "Play here"; the room picker offers to set the override.

The room the page starts on (`RoomChoice.defaultRoom`): this device's room, else the first room in
`config.area`, else the room used last (`MusicSettings.lastRoom`), else the first room.

### State and settings

- `MusicRepository` (process-wide, Compose state): `install(context, backend, signedIn, area)` or
  `installHa(context, ha, config)`; `rooms`, `selfRoom`, `selectedRoomId` (`select()` persists it),
  `available` (HA signed in and at least one room), `reachable`. `attach()` / `detach()` start and
  stop live rooms (the page calls them when it shows / hides); `probe()` checks, at most every
  10 min, whether there is anything to offer while the page isn't shown;
  `setSelfOverride()` changes the override and finds this device's player again.
- `MusicSettings` (SharedPreferences `odysseus_music`): `enabled` (C7 `music.enabled`, default on),
  `stayWhilePlaying` (C7 `music.stay_while_playing`, default off), `selfOverride`, `lastRoom`. The two
  C7 keys are hub-managed (`docs/odysseus/managed-settings.md`).

## How it works: the page

Code: `app/src/main/java/com/msp1974/vacompanion/odysseus/home/music/`.

- `MusicPage` (`HomePage`, id `music`) is in `HomePages.all` after Today while
  `MusicSettings.enabled && MusicRepository.available`. It draws the rail from `MusicTabs.all`
  (`MusicTabs.selectedId`, process-wide Compose state), the selected tab's content, and the
  message line. It provides the home screen's `HomePageContext` to the tabs as
  `LocalHomePageContext` (e.g. `.nowPlaying`, Sendspin's now playing). Nothing on the page scrolls
  or drags horizontally: the home screen owns horizontal swipes.
- **Idle** (C7): `MusicPage.idleExempt()` is true while "Stay while playing here" is on and this
  device plays (Sendspin playing, or its own MA player's state says playing). `HomeNavigator`
  doesn't go back to the clock from a page that is idle-exempt; the idle time restarts when it
  stops.
- **Refresh**: `MusicRefresh`, composed by `HomeHost`, installs `MusicRepository` over the app's
  one Home Assistant client (`ensureMusicInstalled`, once per process), calls `attach()` while the
  page is in view and `detach()` when it isn't (or the screen blanks), and while it isn't,
  `probe()`s Home Assistant at once, every 10 min, and when Home Assistant or the network comes
  back, so the page appears once Music Assistant has rooms. When the rooms it had are gone (Home
  Assistant restarted and Music Assistant is still loading) it asks again after 30 s, 60 s,
  120 s… up to 10 min (`MusicProbeSchedule`, tested), so the page comes back soon.
- **Off-screen**: `HomeHost` also composes the page next to the one shown (e.g. while the clock
  shows). Out of sight the page draws only its rail on an empty surface and follows no player
  (`MusicPageLive`, tested, from `LocalHomeStripPosition`): its tabs come in once 2 % of it is on
  screen or the home screen is going to it, so the first frames of a swipe may show the empty
  surface and the states arrive a moment later.
- **Actions** (`MusicActions`): `run(label) { backend -> … }` runs on a process-wide scope, so an
  action finishes even when the page goes away; `control`, `transfer`, `group`, `ungroup`,
  `select` and `queue` wrap it. A failure becomes `MusicActions.message` for 3 s
  (`musicActionMessage`, pure and tested): `MusicLocked` → "Locked", an `IOException` → "Home
  Assistant offline", a refused token → "sign in again", else the label and a short reason.
- **Live data** (`MusicLive.kt`): `rememberPlayerState(roomId, sendspin)` collects
  `backend.player(roomId)` while composed (this device's room gets Sendspin's pushed artwork);
  `rememberRoomArt(state)` loads `artUrl` through `HaApi.bytes` (8 bitmaps cached), else the pushed
  artwork (never the previous track's art while a new one loads). The volume bar shows the last
  volume it sent until the player reports it back (2 s at most), so quick taps add up. Home
  Assistant's states put `positionAtMs` on the wall clock, Sendspin's on
  `elapsedRealtime`: pass `MusicActions.clockFor(state)` to `FullPlayer` / `Progress`.

### Now playing

`NowPlayingTab.kt`; the rules are in `NowPlayingRules.kt` (pure, `NowPlayingRulesTest`).

- **Layout**: the room chip on top. Wide screens put the art on the left (square, as tall as fits,
  at most 45% of the width) and on the right: title, artist · album, progress and times, then
  shuffle / previous / play-pause / next / repeat, volume and the queue. Narrow or portrait screens
  use one column. Text is 1.3x from 900 dp wide.
- **Controls**: only those the player's `can` has, sent through `MusicActions.control`. Repeat
  cycles off → all → one.
- **Which state**: MA's player state while it has a track. For this device's room, Sendspin's own
  state (`toPlayerState()`, commands through `onSendspinCommand`, pushed artwork only) while
  Sendspin is active. Otherwise "Nothing playing in <room>", with its volume and "Move here from
  <room>" (`MusicActions.transfer`) when another available room plays (not a group containing
  this one) and this room is available.
- **Queue**: `MusicActions.queue` again when the room or the track changes: "Next: <title> —
  <artist>", "N in the queue". Hidden when the room has no queue.
- **Room chip**: "<room> · This Echo ▾" ("This Echo?" when guessed from the area). A tap opens a
  vertical list, this device first; a pick calls `MusicActions.select`.

### Rooms

`RoomsTab.kt`; the rules are in `RoomsRules.kt` (pure, `RoomsRulesTest`). Every room, this device
first with "This Echo" ("This Echo?" when guessed from the area): artwork, name, area when it
differs, and what plays ("Title — Artist", " · Paused"; else "Idle", "Off" or "Unavailable",
greyed), play/pause and a compact volume bar. One column below 840 dp, 2 below 1200 dp, else 3;
it scrolls vertically only. Tap a room to select it and open **Playing**. The ⋮ menu:
**Move music here** (to an available room, from a room that plays and isn't in its group, nor a
Music Assistant group player containing it; with several, pick one), **Group with…** (ticks for
the other available rooms whose player can group; unticked members are taken out; not on a room
that is a member of another room's group), **Leave group** / **Ungroup**. Group leaders show "+N rooms", Music
Assistant group players "Group of N". The tab collects each room's player state once.

Known limits: `PlayerState` has no Home Assistant state text, so "Off" is inferred (no title, no
controls, no volume); a group's leader is taken to be the first of `group_members`; no seeking on
the progress bar yet.

### Library

`LibraryTab.kt`; rules in `LibraryRules.kt` (pure, `LibraryRulesTest`); sections in
`LibrarySections.kt` (adding a type is one line). Top bar: section ▾, sort ▾ (Recent =
`last_played_desc` first, Name, Added), Favourites. Pages of 50 (`MusicBackend.library`); MA gives
no total, so there is more while a page comes back full, and the next offset counts every item MA
returned. The next page loads when the last visible item is within 15 of the end; a failure stops
paging until "Try again". Lists, filters and scroll positions are kept per list in `LibraryState`
(until restart); a list older than 10 min starts again when shown. Rows on the Echo (two to a
line from 580 dp), cards on screens at least 560 dp tall and wide (at least 180 dp each; tracks
stay rows). Artists and albums open a detail view (Back, Play, More…).

**Detail** (`MusicBackend.detail`, `HaMusicDetail`): HA's MA actions have no "albums of" or
"tracks of" call. An artist is one `search` by its name (albums and tracks, 25 each), keeping
items whose artists include it (uri or loose name, case and "(version)" ignored). An album is a
`search` for tracks (50) plus `get_library` tracks with `search` = the album name (100), keeping
tracks on that album: the same album uri, or the same loose name and one of the album's artists
(so another artist's namesake album isn't mixed in). Items are listed once per uri and exact name
with its version ("Intro" and "Intro (Reprise)", a song and its "(Live)" stay apart), in the order
MA returns them: HA's MA answers carry no track or disc numbers. Limits: it may miss tracks, and
a namesake album by the same artist is mixed in. Playlists' tracks can't be listed through HA, so playlists (and
radio, podcasts, audiobooks) open the play menu.

### Search

`SearchTab.kt`: a field on top (the keyboard comes up below it; the results scroll above it),
`MusicBackend.search` 400 ms after the last keystroke from 2 characters, 10 per kind, grouped
Artists, Albums, Tracks, Playlists, Radio, Podcasts, Audiobooks. "Search" on the keyboard hides
it. The last results stay while the page is away (`SearchState`).

### Play menu

`PlayMenu.kt` (rules pure, `PlayMenuTest`): Play now (`play`), Play next (`next`), Add to queue
(`add`), Replace queue (`replace`), Radio from this (`play` + `radio_mode`, tracks and artists
only); a station only plays. "in <room>": the selected room, else this device's room ("(here)"),
else the first available; Change lists the available rooms, this device first. Through
`MusicActions.run`, so Lock → "Locked". Play now, Replace and Radio select the room and open
Playing; Next and Add say "Plays next in X" / "Added to the queue in X".

### Images

`MusicImages.kt` (`MusicImagesTest`): an in-memory LRU of decoded bitmaps capped at 16 MB
(`ByteLru`) over a 200-file disk cache in `cacheDir/music_images/` (`DiskCache`, least recently
used out, keyed by the sha256 of the URL; trimmed on the first write and every 20th after, so it
can briefly hold up to 219). Disk, hashing and decoding run on `Dispatchers.IO`. A thumbnail
starts again from its own URL when a row or detail is reused for another item. Library and search thumbnails are hub URLs (C8,
`/music/image`), fetched only when they start with the linked voice base, with the Odysseus
bearer, no redirects, `image/*` up to 5 MB. HA paths (`/api/…`) go through `HaApi.bytes`.
Anything else isn't fetched. Decoded down to 64/128/256/512 px; at most 4 loads at once; a
failed URL isn't retried for 5 min. No URL, no hub link or a failure → the kind's icon
(`MediaThumb`).

### Tablets

From 1100×600 dp (`MusicLayout`, pure, `MusicLayoutTest`) the page has two panes: the selected tab
on the left and a compact Now playing for the selected room on the right
(`NowPlayingTab(compact = true)`), beside every tab but Playing, which keeps the whole width. The
Echo Show 5 (960×480 dp, or 721×360 at tvdpi) stays one pane. Beside the pane
(`LocalMusicSidePane`), Rooms picks columns by card width (≥360 dp each, at most 3), and a tap on
a room only selects it. On tablets the Playing tab is one centred block (room chip above the art),
with text 1.3× (1.6× from 1500×900 dp).

## Files

| File | What |
|---|---|
| `odysseus/music/` | the backend: `MusicBackend`, `HaMusicBackend`, `HaMusicJson`, `SelfPlayer`, `RoomChoice`, `MusicRepository`, `MusicSettings`, `PlayerState` |
| `odysseus/player/` | the shared player pieces (`FullPlayer`, `Progress`, `VolumeBar`, `RoomChip`, …) |
| `odysseus/home/music/MusicPage.kt` | the page: rail, tab content, message line, idle exemption, `LocalHomePageContext` |
| `odysseus/home/music/MusicTabs.kt` | `MusicTab`, the registry `MusicTabs.all`, the selected tab |
| `odysseus/home/music/NowPlayingTab.kt`, `RoomsTab.kt` | the two tabs; their pure rules in `NowPlayingRules.kt`, `RoomsRules.kt` |
| `odysseus/home/music/LibraryTab.kt`, `SearchTab.kt`, `LibrarySections.kt`, `LibraryRules.kt` | Library and Search tabs, their sections, paging, grid and search rules |
| `odysseus/home/music/PlayMenu.kt` | the play menu and its rules |
| `odysseus/home/music/MediaItemRow.kt` | item rows and cards, top-bar chips, the artist/album detail view |
| `odysseus/home/music/MusicImages.kt` | thumbnails: memory + disk cache, C8/HA loading, `MediaThumb` |
| `odysseus/home/music/MusicLayout.kt` | one or two panes by size, columns beside the pane |
| `odysseus/music/HaMusicDetail.kt` | `MediaDetail`, artist/album detail over `search`/`get_library` |
| `odysseus/home/clock/ClockNowPlaying.kt` | the clock's now playing from this device or the selected room |
| `odysseus/home/music/MusicActions.kt` | actions, error messages, `clockFor` |
| `odysseus/home/music/MusicLive.kt` | `rememberPlayerState`, `rememberRoomArt`, the art cache |
| `odysseus/home/music/MusicRefresh.kt` | install, attach/detach, probe |
| `odysseus/home/music/MusicSettingsSection.kt` | Menu → Odysseus → Music |
| `odysseus/home/HomePage.kt`, `HomeNavigator.kt`, `HomeScreenController.kt`, `HomeHost.kt` | the page list, `idleExempt`, `MusicRefresh` |
| `app/src/test/.../odysseus/home/music/Music{Tabs,Actions}Test.kt`, `home/HomeNavigatorTest.kt` | tests of the registry, error texts, clocks, art cache and the idle exemption; also `{Library,PlayMenu,MusicImages,MusicLayout}*Test.kt`, `music/HaMusicDetailTest.kt`, `clock/ClockNowPlayingTest.kt`, screenshots in `screenshots/` |

## Adding a tab

1. Write `@Composable fun MyTab(modifier: Modifier = Modifier)` in `odysseus/home/music/`. Read
   `MusicRepository` (rooms, selected room, this device's room, reachable), `LocalHomePageContext`
   and the helpers above; change things only through `MusicActions`.
2. Add one entry to `MusicTabs.all`: `MusicTab("my_tab", "Short", Icons.Rounded.Something) {
   MyTab(it) }`. Its place in the list is its place on the rail; keep labels to one short word.
   The rail has four tabs now (Playing, Rooms, Library, Search), about as many as fit the Echo
   Show 5.

No other file changes.

## Adding a backend

1. Implement `MusicBackend` (`odysseus/music/MusicBackend.kt`): live `rooms` and `player(id)`,
   throw `MusicLocked` from every call that changes what plays while `LockMode.isLocked`, and an
   `IOException` (or `HaException`) when the server can't be reached, so the page says so.
2. Install it with `MusicRepository.install(context, backend, signedIn, area)` in place of
   `installHa` (`ensureMusicInstalled` in `MusicRefresh.kt`). The page, the tabs and the settings
   don't change. `selfRoom` and `reachable` come from `HaMusicBackend` today; another backend that
   knows them should offer the same flows.
