# Announcements and reminders

Odysseus can say something in a room ("Dinner is ready", "Reminder: take the bins out"). Home
Assistant speaks it on the right room's satellites; the Echo also shows a card for the same
message. The hub side is Odysseus's `fork/voice/announce.py` (hub docs: `docs/fork/voice-assistant.md`,
"announcements"). Plan: `docs/odysseus/plans/2026-11-today-updates-announce-record.md`, phases H3
(hub) and E3 (this), contract C3.

## What the device does

- **Hello.** At start, every hour, and whenever the device name, area or Odysseus link changes,
  the device tells Odysseus who it is, so the hub can pick it for an area or by name:

  ```
  POST <base>/satellite/hello    application/x-www-form-urlencoded, Authorization: Bearer <voice token>
    device_id         VACA's device id (APPConfig.uuid)
    device_name       Menu → Odysseus → Device name; blank: "VACA <id>" (as in the satellite post)
    area              the HA area this device is in (APPConfig.area), may be empty
    app_version_code  BuildConfig.VERSION_CODE
  -> {"ok": true}
  ```

- **Feed.** Every 30 s, and at once when the screen comes on (at most every 10 s, and not while
  waiting after a refused token or on an older hub), it asks for announcements meant for it (all
  devices, its area, or its id; the hub filters):

  ```
  GET <base>/announcements?device_id=<id>&since=<epoch ms>
  -> {"announcements": [{"id": "an_102", "at": 1762434000000, "kind": "reminder" | "announcement",
       "title": "…", "text": "…", "target": {…}, "ref": "todo:41:3" | null, "expires_at": <ms> | null}]}
  ```

  - Each `id` is shown once. Seen ids are kept for 26 h (the hub keeps announcements 24 h).
  - An item with `"kind": "settings"` is never a card: it says this device's settings changed in
    Odysseus, and the device fetches them ([managed-settings.md](managed-settings.md)).
  - `since` is the newest `at` seen. It's the hub's own clock, so the device clock doesn't matter.
    It survives a restart (SharedPreferences `odysseus_announce`). The first time, it is 15 min
    before now, so a new or reset Echo doesn't show a day of old messages.
  - Skipped (but marked seen): expired ones (`expires_at` passed on the device clock), and ones
    older than 2 h that have no `expires_at`, so an Echo that was off for a night doesn't show
    them all when it comes back.
  - After a failed request it waits 30 s, 1, 2, 4, then 5 min. On a refused token (401/403) it
    waits 15 min. An older Odysseus without the feed (404) is asked again hourly. A changed link
    in Menu → Odysseus (a new token too) starts again within a minute.
  - Not linked to Odysseus: nothing is sent at all. It only checks the settings again every minute.

- **Card.** A new announcement wakes the screen and shows a card at the top, over any screen
  (dashboard, home screen, blank screen):
  - It shows the kind and time ("Reminder · 3:05 PM"), the title and the text (left out when it
    only repeats the title).
  - A reminder has a **Dismiss** button. A tap anywhere on a card also dismisses it.
  - A reminder of a to-do (one with a `ref`) also has **Done**, beside Dismiss: it calls
    `POST <voice base>/reminders/done {"ref", "device_id"}` (contract C5 in
    [plans/2026-11-done-followup-settings-verify.md](plans/2026-11-done-followup-settings-verify.md);
    `device_id` is the hub id from the hello). The tap is the confirmation: no spoken "yes".
    Done (or already done): the card goes and the Today page is refreshed. Failed: the card
    stays with a short reason under the buttons ("Can't reach Odysseus", "Not found in
    Odysseus", "Odysseus can't do this yet" for a hub without the route), and Done can be tapped
    again. There is no undo for a reminder (the hub has no undo route for it). The buttons are
    52 dp tall, to hit from arm's length.
  - It stays 10 min, or until its `expires_at` if that's sooner. With several at once, the oldest
    shows first, with "and N more".
  - It doesn't cover a voice turn: it's hidden from a wake word until the turn has ended and its
    turn card has gone, then it comes back. HA speaking the announcement itself doesn't hide it:
    no wake word started that run.

Logs: ids and kinds only (`Announcements: new an_102 (reminder)`), never the text.

## Speaking stays with Home Assistant

The hub asks HA to run `assist_satellite.announce` on the room's satellites. The device plays it
like any answer (a TTS-only pipeline). The card and the spoken message come separately: the card
comes from the next poll, up to 30 s after the speech or before it.

### Do not disturb

The owner chose that an announcement should still play in DND. The code suggests that today it
doesn't:

- VACA's DND switch (from HA or the device) sets Android's interruption filter to
  `INTERRUPTION_FILTER_NONE` ("total silence", `device/Volume.kt`).
- VACA plays every voice answer, announcements included, with `USAGE_NOTIFICATION`
  (`VoicePlayerService`). Total silence mutes that.

So in DND an announcement is probably silent. The card still shows and the screen still wakes:
the card isn't held back in DND.

Making it audible would change how upstream plays all voice answers in DND. That isn't in this
phase. Check it on the device first (below), then decide.

## Files

- `odysseus/announce/AnnouncementApi.kt`: the feed and hello JSON and form (pure, tested).
- `odysseus/announce/AnnouncementFeed.kt`: what's new: each id once, expiry, `since` (pure, tested).
- `odysseus/announce/AnnouncementRules.kt`: the card queue and its times, keeping out of a voice
  turn's way, poll delays, when to say hello (pure, tested).
- `odysseus/announce/Announcements.kt`: the process-wide runner: hello, polling, the cards' state,
  the wake-the-screen event `odysseusAnnouncement`.
- `odysseus/announce/AnnouncementCard.kt`: the card.
- `odysseus/announce/ReminderDone.kt`: the reminder card's Done button (its state per card).
- `odysseus/done/`: contract C5, shared with the Today page: `DoneApi.kt` (paths, bodies, what an
  answer means; pure, tested), `DoneClient.kt` (the requests through `OdysseusLink.hubRequest`).
- `odysseus/OdysseusLink.kt`: `hubRequest`, a plain request with the voice token for features
  in their own packages (`errorBody = true` also returns the start of an error answer).
- Hooks:
  - `service/BackgroundTask.kt`: `Announcements.start`/`stop`, beside the other background
    services, so it runs whether or not the activity is in front.
  - `MainActivity.kt`: the card composable, beside the turn card, and the
    `odysseusAnnouncement` event, which turns the screen on as the Sendspin pairing prompt does.
- Tests: `app/src/test/java/com/msp1974/vacompanion/odysseus/announce/`,
  `.../odysseus/done/DoneApiTest.kt`.

## Still to check on the real device

1. **Kitchen only.** Announce to the kitchen from Odysseus chat ("tell the kitchen dinner is
   ready"). The kitchen Echo speaks it and, within 30 s, shows the card with the screen turned
   on. The other room's Echo shows nothing (`adb logcat -s Announcements` there: no `new`).
2. **Reminder.** A due reminder reaches every Echo. The card has Dismiss and goes after 10 min
   (or at its `expires_at`).
3. **Once only.** Restart the app (`adb shell am force-stop …` and open it): the same card
   doesn't come back.
4. **A voice turn.** Say "Hey Athena" while a card is up: the card hides, the turn card shows,
   and the announcement card comes back once the turn card has gone.
5. **Screen off.** With the screen off, an announcement turns it on. The screen goes off again
   as usual (screen timeout).
6. **Done.** On a reminder card of a to-do, tap Done from arm's length: the card goes, the to-do
   is ticked (or its due date cleared) on the hub's to-do page, and the voice ledger lists it as
   completed by the device. `adb logcat -s Announcements` shows `done an_… (todo:…) -> ok`. With
   the hub stopped, Done shows "Can't reach Odysseus" and the card stays.
6. **DND.** Turn DND on (from HA or the device) and announce to that room. Note whether HA's
   announce is heard and whether the card shows. Expected from the code: silent, card shown.
   Write down the result here.
7. **Hello.** In the hub's device registry, the Echo has its name, area and app version
   within a minute of starting. Change the area in HA: a new hello within about a minute.
8. **Unlinked.** Unlink in Menu → Odysseus: `adb logcat` shows `feed NOT_LINKED` and no more
   requests.
