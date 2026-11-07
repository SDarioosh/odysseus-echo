# Music page

A native Music page on the home screen: what plays in each room, volume, transport, moving music
between rooms, grouping rooms, and the Music Assistant (MA) library. Plan:
`docs/odysseus/plans/2026-11-music.md` (contracts I1–I3, C7, C8; phases E10–E15). This document
starts with the backend (E12); the page itself (E13) and library and search (E14) extend it.

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
   page shows it as one ("Kitchen speaker?").
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
