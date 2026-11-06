# Plan: Today page, updates from our own server, announcements, recording button

Written 2026-11-06. Four features for odysseus-echo (Echo Show 5 and Android tablets), split
into phases. Each phase is one Claude Code session, one repo, one PR. Each phase brief below
stands on its own: paste it into a new session for that repo.
Where a brief says `[paste contract Cn]`, paste that section from "Shared contracts" below
(or, if the session has this repo, point it at this file).

Repos:
- **echo**: `SDarioosh/odysseus-echo` (this app; default branch `odysseus`)
- **hub**: `SDarioosh/odysseus-expanded` (Odysseus; default branch `barvis`)
- Athena (`SDarioosh/Athena`) needs **no changes**: its ingest already takes WAV and `kind=note`.

## Order

```
Phase 0  owner, on the Echo: can the mute switch trigger anything?     (no code)
Phase 1  hub H1 Today route  | hub H2 app releases | hub H4 Echo token + WAV | echo E4 shared mic
Phase 2  echo E1 Today page  | echo E2 updates     | hub H3 announcements    | echo E5 recorder
Phase 3  echo E3 announcement card                 | echo E6 mute switch trigger
```

Sessions in the same phase can run at the same time. A later phase needs the PRs it names
merged first; E1, E2 and E3 can be written earlier against the contracts below with fakes,
but test them against a merged hub.

## Decisions taken in this plan (change them before starting if you disagree)

1. **The Echo's token stays narrow.** New device data goes through new routes under the voice
   API (`/api/chat/voice/v1/...`, scope `voice`), shaped for the device. The only other scope
   the Echo gets is `athena:ingest`, for recordings. It doesn't get calendar or to-do scopes.
2. **Recordings are uploaded as WAV, not Opus.** 16 kHz mono PCM16, 30 s per chunk (960 KB).
   Athena accepts WAV, and so there's no audio encoder to build or ship (Android's Opus
   encoder needs Android 10; tablets on 8 or 9 have none). The hub relay allows `wav` (H4).
3. **The button works like the T-Watch Ultra.** A short press starts or stops a recording. A
   hold of about 1 s **starts** a voice note. Letting go doesn't stop it: a press or another
   hold ends it, and it stops by itself after 5 minutes. Starting a note during a recording
   ends the recording, records the note, then starts a new recording.
4. **Updates are hosted by the hub, installed with one tap.** Android shows its install prompt;
   a silent install would need device-owner mode, which we decided against.
5. **Speaking stays with Home Assistant.** The hub asks HA to announce on the right
   satellites. The Echo shows a card for the same message, from a feed it polls.

## Owner's answers (2026-11-06)

- **Mute switch**: it doesn't cut the microphone in hardware (the wake word still worked while
  muted), so E6 goes ahead with the switch. Whether the button reports separate down and up
  events is still to be seen with `getevent` on the device (Phase 0, step 1).
- **Today page**: on by default, with a per-device off switch.
- **Wake word**: "Hey Athena" keeps working during recordings **and** voice notes (this replaces
  E5's "pause wake word during a voice note").

## Shared contracts (hub and echo build against these)

### C1. Today for devices: `GET /api/chat/voice/v1/today`

Scope `voice`. Read-only. Times in ISO-8601 with offset; the device shows them in its own time zone.

```json
{
  "date": "2026-11-06",
  "generated": "2026-11-06T07:45:00-05:00",
  "headline": "Two meetings this afternoon; the boiler service is due.",
  "focus":     [{"ref": "todo:41:2", "title": "Call the plumber", "why": "Due today"}],
  "events":    [{"uid": "abc", "title": "Dentist", "start": "2026-11-06T15:00:00-05:00",
                 "end": "2026-11-06T15:30:00-05:00", "all_day": false, "location": "Main St"}],
  "todos":     [{"ref": "todo:41:2", "title": "Call the plumber", "due": "2026-11-06", "list": "Home"}],
  "action_items": [{"id": "ai_17", "title": "Send Sam the photos", "source": "athena"}],
  "counts":    {"todos_open": 9, "action_items_open": 4}
}
```

- `events`: today only, recurring events expanded, sorted by start, at most 10.
- `todos`: open items due today or overdue, then undated pinned ones, at most 8.
- `action_items`: Athena's open items, at most 5; `[]` when Athena is down (never an error).
- `focus`, `headline`: from the Today insight; `[]` and `null` when there's none yet.
- 200 always when authorised. Each section is empty rather than failing.

### C2. App releases: `GET /api/chat/voice/v1/app/latest` and `GET /api/chat/voice/v1/app/apk`

Scope `voice`.

```json
{"app": "odysseus-echo", "version_name": "0.14.0+odysseus.30", "version_code": 1400030,
 "size": 31457280, "sha256": "9f2c...", "notes": "Kiosk settings; Today page."}
```

- `latest` → 404 `{"error": "no release"}` when none is uploaded.
- `apk` → the file (`application/vnd.android.package-archive`, `Content-Length`, `ETag` =
  sha256). Range requests are optional.
- The device compares **`version_code`** (never the name: semver ignores `+odysseus.N`).

### C3. Device announcements feed: `GET /api/chat/voice/v1/announcements?device_id=...&since=<epoch ms>`

Scope `voice`.

```json
{"announcements": [
  {"id": "an_102", "at": 1762434000000, "kind": "reminder",
   "title": "Take the bins out", "text": "Reminder: take the bins out",
   "target": {"area": "kitchen"}, "ref": "todo:41:3", "expires_at": 1762437600000}
]}
```

- `kind`: `reminder` or `announcement`.
- Only announcements meant for this device: targeted at all devices, its area, or its
  `device_id`. The hub learns a device's area and name from `satellite/turn` and from
  `POST /api/chat/voice/v1/satellite/hello` (below).
- Kept 24 h. A device shows each `id` once.
- `POST /api/chat/voice/v1/satellite/hello`: form `device_id`, `device_name`, `area`,
  `app_version_code`. Sent on start and hourly. Response `{"ok": true}`.

### C4. Recording upload (existing hub relay, plus WAV)

- `POST /api/codex/athena/chunk`, scope `athena:ingest`, multipart:
  - `audio`: the WAV file
  - `device`: `echo-<device id>`
  - `session_id`: 16 lowercase hex digits, random
  - `sequence`: from 0, no gaps
  - `timestamp`: chunk start, ISO-8601 UTC with ms
  - `codec=wav`
  - `kind`: `note` for voice notes, left out for recordings
- `POST /api/codex/athena/sessions/{session_id}/end` after the last chunk.
- Results, as in Athena's `docs/api/ingest-client.md`:

| Answer | Class | Device does |
|---|---|---|
| 200 with `"ok": true` (a duplicate counts) | ok | delete the chunk |
| 5xx, 408, 429, network error, 200 without ok | retry | retry later in order: 1, 2, 5, 10, 15 min |
| 401 / 403 | auth | stop uploads until relinked |
| other 4xx | rejected | keep the file, skip it, log it |
| 404 on both relay names for a chunk | relay missing | stop uploads, keep every chunk (none rejected), say "Odysseus can't take recordings (relay missing)", retry hourly and on "Upload now" |
| 3xx (redirects aren't followed: the token stays with the hub) | retry | as retry |

- On a 404 for `/api/codex/athena/*`, retry once on `/api/codex/sound/*` (older hub). A 404 on
  both for a chunk is the hub's problem (row above); a 404 on both for
  `/sessions/{id}/end` (session not found) is that end's refusal, as for other 4xx.

---

## Phase 0 (owner, no code): what does the mute switch send?

On the Echo Show 5 (LineageOS 18.1, root over adb). This takes about 15 minutes.

```bash
adb shell getevent -lt
```

Press the privacy button briefly, then hold it for 2 s. Note for each press:

1. Which input devices report (`gpio-privacy-state` `SW_MUTE_DEVICE`, `gpio-privacy-button`
   `KEY_POWER`?), and whether the button reports **both** down and up (`DOWN`, then `UP` with
   the time between).
2. Does the red light come on, and does recording stop? With the Echo muted, run:

```bash
adb shell dumpsys audio | grep -i -A2 "mic mute"
```

Then say "Hey Athena": is the microphone actually dead?

3. Repeat 1 with the screen off.

Paste the output into a new issue on odysseus-echo. Then:
- **The button reports down/up and the mic still works after a press** → E6 as written.
- **Every press cuts the mic in hardware** → the switch can't record. E6 uses the volume
  buttons instead (or is dropped), and the on-screen button from E5 is the main trigger.

---

## Phase 1

### H1 (hub): Today route for devices

> Repo `SDarioosh/odysseus-expanded`. Read `AGENTS.md` and follow it (fork code in `fork/`,
> small marked hooks only, FORK.md and `docs/fork/files.md` updated, focused tests).
>
> Add `GET /api/chat/voice/v1/today` for the Echo devices, scope `voice` (same auth as the
> other routes in `fork/voice/routes.py`, `voice_owner`). It returns contract C1 below for the
> token's owner.
>
> Build it from what exists, no new data:
> - the Today insight (`fork/insight/today.py`, `today_store.public()`), for `headline` and `focus`;
> - today's calendar events with recurring events expanded (the code behind
>   `GET /api/codex/calendar/events`, `_event_to_dict` in `routes/calendar_routes.py`);
> - open to-do items (`fork/hub/codex_todos_json.py`): due today or overdue first, then pinned
>   undated, at most 8;
> - Athena's open action items (the code behind `GET /api/codex/athena/action-items?status=open`),
>   at most 5, `[]` if Athena is down or slow (time-box it to 3 s).
>
> Each section fails on its own into an empty list and a log line; the route returns 200
> whenever the token is valid. No model call in the request path. Put it in a new file
> `fork/voice/today_route.py` with the shaping in a pure function that's unit-tested
> (fictional data only), and document it in `docs/fork/voice-assistant.md`'s endpoint table.
>
> [paste contract C1]
>
> Done: focused tests pass (`python -m pytest -q tests/fork/test_voice_today.py` plus the voice
> tests); PR into `barvis` with a Server verification section (curl the route with an Echo
> token; expected shape).

### H2 (hub): host the Echo app's releases

> Repo `SDarioosh/odysseus-expanded`. Read `AGENTS.md` and follow it.
>
> The Echo app (odysseus-echo) updates itself from the hub. Add:
> - `GET /api/chat/voice/v1/app/latest` and `GET /api/chat/voice/v1/app/apk`, scope `voice`, as
>   contract C2 below. The file is streamed from disk, not read into memory.
> - Storage: `<DATA_DIR>/echo_app/` holding `odysseus-echo.apk` and `release.json` (contract C2's
>   fields). Path from `src/constants.py`'s data dir helpers.
> - `scripts/echo_release.py <apk> --version-name ... --version-code ... [--notes ...]`, run by the
>   owner on the server. It copies the APK into place atomically (temp file then rename), works
>   out size and sha256, and refuses a `version_code` lower than the current one unless `--force`.
>   It doesn't read the APK's own manifest; the owner passes the version (the Echo build prints
>   it: `./gradlew printVersionName printVersionCode`).
> - Optional, if small: an admin-only upload form on the existing voice assistant settings card.
>   Otherwise leave it for later and say so in the PR.
>
> Code in `fork/voice/app_release.py`; tests for the JSON, 404 when absent, the file response
> headers, and the script's version check (fictional bytes, not a real APK).
>
> [paste contract C2]
>
> Done: tests pass; PR into `barvis` with Server verification (run the script with a test
> file into a temp data dir, curl both routes).

### H4 (hub): Echo token can upload recordings; relay accepts WAV

> Repo `SDarioosh/odysseus-expanded`. Read `AGENTS.md` and follow it.
>
> The Echo app will record conversations and voice notes and upload them through the hub's
> Athena relay (`POST /api/codex/athena/chunk`, `fork/athena/relay_routes.py`,
> `fork/athena/call_recorder.py`). Two changes:
>
> 1. **Scopes for Echo tokens.** `/echo/link` (`fork/voice/echo_link.py`) mints voice-only
>    tokens. Make it mint `voice` + `athena:ingest`. `prune_echo_tokens` today deletes only old
>    Echo tokens whose scopes are exactly `voice`. Change it to recognise Echo tokens by what
>    marks them as Echo tokens (their `Echo <name> <date>` name, or better a marker stored at
>    mint time) so tokens with the new scope are still pruned on relink. Existing voice-only
>    Echo tokens keep working; relinking an Echo upgrades it.
> 2. **WAV in the relay.** The relay accepts only `ogg`/`opus`. Also accept `codec=wav`. Check
>    the file starts with a RIFF/WAVE header and is PCM16 mono 16 kHz; reject others with 400.
>    Athena already decodes WAV, so forward it unchanged. 30 s of WAV is ~960 KB, under the
>    relay's 5 MiB cap.
>
> Tests: token scopes after linking, pruning of old voice-only and new tokens, WAV accepted
> and forwarded with `codec=wav`, a bad WAV header rejected, ogg unchanged. Update
> `docs/fork/voice-assistant.md` ("Linking an Echo") and `docs/fork/modules/athena.md`.
>
> Done: tests pass; PR into `barvis` with Server verification (relink a test Echo token, then
> `curl -F` a 1 s WAV chunk with `kind=note` and the session end; expected `{"ok": true}`, and
> the voice note in Athena).

### E4 (echo): one shared microphone, with raw audio

> Repo `SDarioosh/odysseus-echo`, branch from `odysseus`. Read `CLAUDE.md` and `ODYSSEUS.md`
> and follow them (fork code under `odysseus/`, small hooks marked `// Odysseus fork:` in
> upstream files, an ODYSSEUS.md row, a doc, bump `N` in `odysseusVersionName`).
>
> A recorder coming in a later phase needs the microphone even when Home Assistant isn't
> connected, and needs the audio **before** processing. Today `audio/MicrophoneAudioStream` is
> created per `Satellite` (`satellite/Satellite.kt:127`), so it exists only while HA is
> connected. Its `audio` SharedFlow carries frames after `MicGate` (quiet frames become
> silence), `MicSuppressor` and the gain.
>
> 1. Make one process-wide microphone: `odysseus/audio/SharedMicrophone.kt` owns a single
>    `MicrophoneAudioStream`, created on first use, with the app's process scope. `Satellite`
>    uses it instead of making its own (one hook line). Behaviour for the wake word must not
>    change: same flow, same buffering, mic still opens on first subscriber and closes after
>    the last.
> 2. Add a `raw: SharedFlow<ShortArray>` beside `audio`: the same frames before `MicGate`,
>    WebRTC, the suppressor and gain (tee in `MicrophoneInput.readShort` before processing, a
>    small hook). Same frame size (1280 shorts, 80 ms, 16 kHz mono).
> 3. The mic must still be released when nothing listens (screen-off battery), and a
>    subscriber that collects only `raw` must keep the mic open.
>
> Tests: plain-Kotlin tests for whatever logic you can pull out (who keeps the mic open), and
> the existing audio tests still pass. Run `./gradlew :app:testDebugUnitTest`.
>
> Done: PR into `odysseus`. Server verification: install on the Echo, check the wake word,
> barge-in and "Check my voice" still work, and `adb logcat` shows one AudioRecord.

---

## Phase 2

### E1 (echo): Today page (needs H1)

> Repo `SDarioosh/odysseus-echo`. Read `CLAUDE.md`, `ODYSSEUS.md` and
> `docs/odysseus/home-screen.md`.
>
> Add a native **Today** page beside the clock: page 1 in `HomePages.all`
> (`odysseus/home/HomePage.kt`), reached by swiping left from the clock. It shows contract C1
> from the hub's `GET /api/chat/voice/v1/today`:
> - the headline;
> - today's events (time, title, place);
> - to-dos due today or overdue;
> - Athena's open action items;
> - "and N more" counts.
>
> Use the house style of `ClockPage`, and keep it readable from across a kitchen on the Echo
> Show 5's 960×480 screen. Read-only in this phase.
>
> Follow the weather feature's pattern exactly (`odysseus/home/weather/`):
> - `TodayClient` (pure, parses C1 with `ignoreUnknownKeys`, tested with a fake HTTP);
> - `TodayRepository` (process-wide, Compose state, refresh when the page comes up and every
>   15 min while the home screen is on, keeps the last result up to 2 h, never throws);
> - data passed to the page through `HomePageContext` (pages don't read the view model).
>
> The call goes through `OdysseusLink` (its base URL and voice token; add a GET helper if
> needed). Settings in Menu → Odysseus → Home screen: "Today page" on/off (default on), stored in `HomeSettings`. With Odysseus not linked
> or the page off, the page isn't in the list.
>
> [paste contract C1]
>
> Done: tests (client parsing, empty sections, stale data) pass; doc section in
> `docs/odysseus/home-screen.md`; ODYSSEUS.md row; version bump; PR into `odysseus` with
> Server verification (swipe left on the Echo, compare with Odysseus's `/today` page).

### E2 (echo): updates from the hub (needs H2)

> Repo `SDarioosh/odysseus-echo`. Read `CLAUDE.md` and `ODYSSEUS.md`.
>
> The fork disabled upstream's GitHub updates (`utils/Updater.kt`, `inAppUpdatesEnabled =
> false`). Add updates from the owner's own Odysseus hub instead, contract C2 below, in new code
> under `odysseus/update/`:
>
> - **Check**: `GET <voice base>/app/latest` with the voice token, at start, then daily (and
>   after `satellite/hello` once E3's hello exists).
>   - Compare `version_code` with `BuildConfig.VERSION_CODE`. Don't use the semver library: it
>     ignores `+odysseus.N`.
>   - Pure decision logic, unit-tested.
> - **Download**: into app-private storage with the Bearer token (not `DownloadManager`). Resume
>   if possible, verify size and sha256, delete on mismatch. Wi-Fi only, at most once per day
>   per version after a failure.
> - **Install**: `PackageInstaller` session; Android shows its prompt. The device is the home
>   app, not device owner, so no silent install.
>   - Show "Update ready: <version> — Install" in Menu → Odysseus, and a small dismissible
>     banner on the clock page.
>   - "Install unknown apps" for this app: detect it's missing and open the system screen for
>     it. Document the adb line for setup:
>
>     ```bash
>     appops set com.msp1974.vacompanion.odysseus REQUEST_INSTALL_PACKAGES allow
>     ```
> - **Signing**: an update installs only over a build signed with the same key. The Echos run
>   debug builds today. Document in `docs/odysseus/build.md` the one-time move to release
>   builds, which needs one uninstall and loses app data; setup by phone QR redoes the link.
>   Also document the release steps:
>   1. `scripts/odysseus/build.sh assembleRelease`
>   2. `check_apk_privacy.py`
>   3. copy to the server
>   4. `scripts/echo_release.py` on the hub
> - Don't touch upstream's `Updater`/`RUN_UPDATE` code (hooks only if needed).
>
> [paste contract C2]
>
> Done: tests pass; doc `docs/odysseus/updates.md`; ODYSSEUS.md row; version bump; PR with
> Server verification (publish N+1 on the hub, the Echo offers it, installs, keeps settings).

### H3 (hub): announcements to a room or device, and the device feed

> Repo `SDarioosh/odysseus-expanded`. Read `AGENTS.md`, `docs/fork/voice-assistant.md`
> ("announcements").
>
> Today `fork/voice/announce.py` speaks a due reminder on every entity in
> `FORK_VOICE_ANNOUNCE_ENTITIES`, through Home Assistant's `assist_satellite.announce`. Add:
>
> 1. **Device registry**: `POST /api/chat/voice/v1/satellite/hello` (contract C3), and also
>    record `device_id`, name and area from `satellite/turn`. Store in the existing voice SQLite
>    (`fork_voice.db`), with a last-seen time.
> 2. **Targets**: `announce(message, target)` where `target` is `all`, `{"area": "kitchen"}` or
>    `{"device_id": ...}`.
>    - For speaking, resolve to HA `assist_satellite.*` entities by asking HA which satellites
>      are in that area. Use a template through `POST /api/template` with `area_entities()`,
>      with the same local-only HA rules as now.
>    - With no area match, fall back to the configured list.
> 3. **Device feed**: every announcement is also stored (24 h) and served by
>    `GET /api/chat/voice/v1/announcements` (contract C3), filtered to the asking device.
> 4. **Reminders**: the `ping_notes` hook passes `kind: "reminder"`, the to-do ref and target
>    `all` (per-reminder rooms can come later).
> 5. **Agent tool**: "announce" for chat and voice ("tell the kitchen dinner is ready"). It
>    speaks aloud, so it's a write effect and goes through the same confirmation rules as the
>    other voice write tools (`fork/voice/confirm.py`, tool policy). Arguments: message, room
>    or device name; matched against the registry.
>
> Tests with a fake HA (no real HA): area resolution, fallback, feed filtering, expiry,
> reminder kind, the tool's confirmation path. Docs and FORK.md updated.
>
> [paste contract C3]
>
> Done: PR into `barvis` with Server verification: announce to one area from chat; only that
> room's Echo speaks; the feed returns it for that device only.

### E5 (echo): recorder: recordings and voice notes to Athena (needs E4 and H4)

> Repo `SDarioosh/odysseus-echo`. Read `CLAUDE.md`, `ODYSSEUS.md`.
>
> Record conversations and voice notes on the device and upload them to Athena through the
> hub (contract C4 below). This phase uses an on-screen button and the quick actions sheet
> as triggers; the hardware button comes in E6. New code in `odysseus/record/`.
>
> **Gestures**, the same as the T-Watch Ultra, as a pure, unit-tested state machine
> (`RecorderStateMachine`):
> - Press: start a recording, or stop the recording, or stop a voice note if one is running.
> - Hold ~1 s: start a voice note (release does nothing), or stop the running note.
> - Starting a note while recording: end the recording session (last chunk, `/end`), record
>   the note as its own session, then quietly start a new recording session.
> - Voice note max 5 min; recording max 4 h; a second press within 1 s is ignored.
> - Refuse to start while the mic is muted (`VoiceActivity.micMuted` or `config.isMuted`), with
>   an on-screen reason.
>
> **Audio**: collect `SharedMicrophone.raw` (from E4) undispatched into a channel, as
> `audio/DetectionBuffer` does. Encode 30 s WAV chunks (`Wav.encodePcm16Mono`, 16 kHz mono)
> on a background dispatcher.
> - Each chunk's timestamp = session start + 80 ms × frames before it.
> - The wake word keeps working during recordings and voice notes (owner's choice).
>
> **Durable queue**: write each chunk to app-private storage before uploading (`session_id`,
> `sequence`, `kind`, timestamp in a small sidecar file). Upload in order per session.
> - Delete only after `ok`, per the result table in C4. A duplicate is ok.
> - After the last chunk, `/end`.
> - Survives app restarts and works offline. Cap the queue at 500 MB, dropping the oldest
>   *finished* sessions first, with a warning on screen.
> - Uploads go through the hub with the Echo's token, which needs `athena:ingest` (H4; an
>   Echo linked before H4 must be relinked — show that on a 403).
>
> **Feedback** (no haptics on these devices):
> - a red "REC 12:34" or "NOTE 0:42" pill over every screen, and the screen kept on while
>   recording;
> - a short start sound and a two-tone stop sound (generate them like
>   `scripts/odysseus/make_thinking_sound.py`), not played while DND is on.
>
> The `device` field is `echo-<config.uuid>`.
>
> **UI**: a record button in the quick actions sheet (tap = press, long-press = hold) and a
> small record button on the clock page (setting "Record button on the clock", default on).
> Settings in Menu → Odysseus → Recording:
> - recording on/off;
> - queue status ("3 chunks waiting");
> - "Upload now".
>
> **Privacy**: audio never goes anywhere but the owner's hub. The indicator is always visible
> while recording. Nothing is recorded unless a person pressed something.
>
> Tests: the state machine (every gesture in every state), chunk timing, queue ordering and
> result classes (use the table in C4 as test cases), WAV header. `./gradlew :app:testDebugUnitTest`.
>
> [paste contract C4]
>
> Done: doc `docs/odysseus/recording.md`; ODYSSEUS.md row; version bump; PR with Server
> verification:
> 1. Record 1 min, and check it appears in Athena as a conversation from `echo-…`.
> 2. Hold for a voice note "remind me to water the plants at 6", and check it becomes a reminder.
> 3. Unplug Wi-Fi during a recording, then plug it back: everything still arrives.

---

## Phase 3

### E3 (echo): announcement and reminder card (needs H3)

> Repo `SDarioosh/odysseus-echo`. Read `CLAUDE.md`, `ODYSSEUS.md`.
>
> The hub (H3) now speaks announcements through Home Assistant on the right room's Echo and
> also serves them as a feed (contract C3 below). Add, under `odysseus/announce/`:
> - **Hello**: `POST <voice base>/satellite/hello` (device id `config.uuid`, name from
>   `OdysseusLink.deviceName`, area `config.area`, `BuildConfig.VERSION_CODE`), at start and
>   hourly.
> - **Feed**: poll `GET <voice base>/announcements` every 30 s while HA or the hub is
>   reachable, and at once after the screen comes on. Pure "what's new" logic, tested: each id
>   once, skip expired, remember the last `since` across restarts.
> - **Card**: an overlay like `TurnCard` (`MainActivity` hook beside it):
>   - title, text, time;
>   - for `kind: reminder`, a Dismiss button (Done comes later);
>   - wake the screen the way the Sendspin pairing prompt does (`applyScreenMode(ON)` through
>     an event, not a direct reference);
>   - stays until dismissed or 10 min, and doesn't cover an active voice turn.
> - **Speaking** stays with HA's announce (already works). Check whether an HA announce plays
>   while the Echo is in DND. If it does, it should still play (the owner chose to announce),
>   but write down what happens in the doc.
>
> [paste contract C3]
>
> Done: tests; doc `docs/odysseus/announcements.md`; ODYSSEUS.md row; version bump; PR with
> Server verification: announce to the kitchen from Odysseus chat; the kitchen Echo speaks and
> shows the card; another room's Echo shows nothing.

### E6 (echo): the mute switch starts recordings (needs Phase 0 result and E5)

> Repo `SDarioosh/odysseus-echo`. Read `CLAUDE.md`, `ODYSSEUS.md`,
> `docs/odysseus/echo-show-5.md` ("Privacy button") and the Phase 0 issue with the owner's
> `getevent` output.
>
> Today `scripts/odysseus/gpio-privacy-button.kl` maps no keys: the button's `KEY_POWER`
> (scancode 116) is dropped, and only the hardware mute (`SW_MUTE_DEVICE`) acts. The app
> receives no key events at all.
>
> If Phase 0 showed the button reports down and up and the microphone keeps working:
> 1. Map scancode 116 in the `.kl` to a key the app can catch (for example `MEDIA_RECORD`)
>    with the `WAKE` flag, so it also arrives with the screen off (Android only passes non-wake
>    keys while the screen is on). Install it from `scripts/odysseus/setup_device.sh` (root,
>    `/data/system/devices/keylayout/`), with a check that it loaded.
> 2. Stop the hardware mute from acting on the press, if Phase 0 showed a way. If it can't be
>    stopped, this phase can't work: stop and report.
> 3. Handle the key in `MainActivity.dispatchKeyEvent` (one hook calling
>    `odysseus/record/RecordButton`). Turn down/up timing into the E5 state machine's press and
>    hold (hold = down for ≥ 1 s, fired while still held; the up after a hold does nothing).
>    Only the focused activity gets keys, so make sure the app is in front (it's the home app).
>    If it isn't, the press goes nowhere; document that.
> 4. The privacy button no longer mutes. Muting moves to the quick actions sheet's mic toggle;
>    say so on screen once and in the docs.
>
> If Phase 0 showed the switch cuts the mic in hardware: use the volume buttons instead (hold
> volume-down 1 s = press, hold 3 s = voice note, or what the owner picks), or stop and report.
>
> Done: docs (`echo-show-5.md`, `recording.md`); ODYSSEUS.md row; version bump; PR with Server
> verification on the Echo:
> 1. Press: recording starts with the screen off.
> 2. Hold: a voice note starts.
> 3. Press again: it stops.
> 4. The red privacy light doesn't come on.

---

## After all phases

- The hub's `docs/fork/roadmap.md` and this repo's `ODYSSEUS.md` list the four features.
- Not in this plan, possible next:
  - Done buttons on Today and reminder cards (needs write routes);
  - per-reminder rooms;
  - Opus uploads for smaller files;
  - "Enroll my voice" from the Echo.
