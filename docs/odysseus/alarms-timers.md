# Alarms and timers

Each Echo has its own alarms and timers: they show and ring only on the device they were set on.
Nothing is synced to the other Echos for now.

| | Set by | Kept by | Rings |
|---|---|---|---|
| **Timers** ("set a 10 minute pasta timer") | Home Assistant's own voice timers (built-in intents, handled locally before Odysseus is asked) | Home Assistant, and a copy on the device | on the device at its own end time |
| **Alarms** ("wake me up at 7", "set an alarm for 6:30 on weekdays") | Odysseus's `alarm` voice tool (Home Assistant has no alarm intent) | Odysseus, per device, and a copy on the device | on the device, also when Odysseus or Home Assistant is down |

Code: `app/src/main/java/com/msp1974/vacompanion/odysseus/alarms/`. The hub side (the `alarm` tool,
`/alarms` routes, the `"alarms"` feed item) is in odysseus-expanded, `docs/fork/voice-assistant.md`.

## Timers

Home Assistant's wyoming integration (which the VACA integration builds on) sends the satellite that
set a timer these events; upstream dropped them, the fork handles them in
`OdysseusSatellite.onServerPacket`:

| Event | Data | The device |
|---|---|---|
| `timer-started` | `id`, `total_seconds`, `name`?, `start_hours/minutes/seconds`? | adds it, ending `total_seconds` from now |
| `timer-updated` | `id`, `is_active`, `total_seconds` | paused (`is_active` false) or running again, with `total_seconds` left from now (pause, resume, time added or taken off) |
| `timer-cancelled` | `id` | removes it |
| `timer-finished` | `id` | rings it now, unless it already rang at its own end time |

- `total_seconds` is what is left at that moment (HA resets its clock on every change), so the
  device keeps its own countdown and rings at its own end time. HA drops `timer-finished` for a
  satellite that is disconnected at that moment; the device rings anyway.
- Cancel, pause, resume, add time and "how much time is left" are HA's own intents; they find a
  timer from any device (the asking device's area first).
- Kept across an app restart (SharedPreferences `odysseus_alarm_clock`). HA keeps its timers in
  memory only: after an HA restart the device still rings its copy, but HA no longer knows it.
- On screen: a pill at the top centre over every screen with the three soonest timers
  ("Pasta 4:59"; a pause sign while paused, "+N" for more). Menu → Odysseus → Alarms
  lists them all.

## Alarms

Set by voice through Odysseus: Home Assistant passes "set an alarm for 7" on to its conversation
agent, Odysseus, whose `alarm` tool sets it on the device that asked (or on a named device: "set an
alarm on the bedroom Echo"). It runs without a spoken "yes": it only changes an alarm clock on the
Echo. "What alarms do I have" and "cancel my 7 o'clock alarm" use the same tool.

```
GET    <base>/alarms?device_id=<id>
       -> {"alarms": [{"id": "al_…", "time": "07:30", "days": [1,2,3,4,5], "date": null,
                       "label": "Gym" | null}], "updated_at": "<iso>"}
DELETE <base>/alarms/<alarm id>?device_id=<id>   -> {"ok": true}   (idempotent)
```

- `time` is a wall-clock time, rung in the device's own time zone. `days` are ISO weekdays
  (Monday = 1 … Sunday = 7) for a repeating alarm; a one-off has `days: []` and the `date` it rings.
- The device asks for its list at start, every hour, when the device feed (announcements) has an
  item of `"kind": "alarms"` (never a card), and when its own voice turn shows an `alarm` action
  (turn card fetch; the card shows it with an alarm icon, "Cancelled" for a cancelled one).
- The last list is kept on the device (SharedPreferences `odysseus_alarm_clock`), so alarms ring
  when the hub is down or the device isn't linked any more.
- Menu → Odysseus → **Alarms** lists the alarms (time, label, "Weekdays" or the day)
  with a **Delete** button each (`DELETE /alarms/<id>`).

## Ringing

`AlarmClock` rings whatever is due, one at a time:

- With upstream's `AlarmService`: the alarm sound and alarm volume of Settings → Audio, looping.
- A full-screen card: the title ("Gym", "Alarm", "Pasta", "10 min timer") and time or length, with
  **Stop**, and **Snooze** (9 min) for an alarm. The screen turns on (`odysseusRinging` event).
- The wake word (or the stop word) stops it, as upstream's own alarm: `Satellite` stops a sounding
  `AlarmService`; the clock sees it gone and ends the ring. The wake word only listens while Home
  Assistant is connected; without it, use the screen.
- It stops by itself after 10 minutes.
- A ring missed by more than 10 minutes (the device was off) is skipped: an Echo that comes back at
  noon doesn't ring the 7:00 alarm.
- A loop looks again at the next ring (at least every minute), and Android's alarm clock
  (`AlarmManager.setAlarmClock`, `USE_EXACT_ALARM`) is set for the same time as a backstop, so a
  ring comes on time through doze, and restarts the app if it had been stopped. Without exact
  alarms it falls back to an inexact one (`setAndAllowWhileIdle`).
- **Lock mode:** nothing rings while locked (the ring is skipped and logged), and locking stops a
  ring. Timers and alarms are still kept.

## Files

All in `app/src/main/java/com/msp1974/vacompanion/odysseus/alarms/`:

| File | What |
|---|---|
| `AlarmRules.kt` | the hub's alarm list, next ring times, labels (plain Kotlin, `AlarmRulesTest`) |
| `TimerRules.kt` | timers from HA's timer events, countdowns (plain Kotlin, `TimerRulesTest`) |
| `RingRules.kt` | what rings when: missed rings, snooze, ring once (plain Kotlin, `RingRulesTest`) |
| `AlarmClock.kt` | keeps the timers and alarms, rings them, Android's alarm clock backstop |
| `AlarmClockReceiver.kt` | Android's alarm clock fired: starts the clock and makes it look |
| `Alarms.kt` | fetches this device's alarms from the hub, deletes one |
| `AlarmOverlays.kt` | the timers pill and the ringing card |
| `AlarmsSettingsSection.kt` | Menu → Odysseus → Alarms |

Hooks: `OdysseusSatellite.onServerPacket` (timer events), `announce/Announcements.kt` (starts the
clock and the alarm list, the `"alarms"` feed item), `announce/AnnouncementApi.kt` and
`AnnouncementFeed.kt` (the `"alarms"` kind), `TurnCard.kt` (an `alarm` action asks for the list; its
icon and "Cancelled"), `OdysseusOverlays.kt` (the pill, the card, the screen-on event),
`OdysseusSettingsLayout.kt` (the menu section), `HubHttp.kt`/`OdysseusLink.kt` (a `method` for
DELETE), `AndroidManifest.xml` (`USE_EXACT_ALARM`, `SCHEDULE_EXACT_ALARM` up to Android 12L, the
receiver; marked `Odysseus fork`).

## Not yet

- Timers and alarms on every Echo (synced); for now each device has its own.
- Setting or changing an alarm on the screen (only by voice; Delete in the menu).
- The next alarm on the clock page.
