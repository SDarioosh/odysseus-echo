# Lock mode

Locks the Echo outside the work hours, for an Echo in an office: Monday to Friday it is open from
08:00 to 18:00 (changeable in 15-minute steps) and locked the rest of the time, all weekend too. A
PIN unlocks it on the device. Code: `app/src/main/java/com/msp1974/vacompanion/odysseus/lock/`.

**Off by default.** Turn it on in Menu → Odysseus → Lock outside work hours, after setting a PIN
(4 to 8 digits) there. It applies at once: turned on in the evening, the device locks straight away.

## While locked

- **Screen:** `LockOverlay` covers everything (dashboard, clock home screen, settings menu, cards)
  with a dim clock, "Locked" and when it opens. A tap shows a PIN pad, which hides again after 20 s
  without a touch. The settings menu is closed on lock.
- **Microphone:** the wake word engine is muted, which unsubscribes it from the microphone (the same
  path as Home Assistant's mute switch, which keeps its own value). A wake word, Home Assistant's
  `wake` action and a follow-up are ignored. A turn running at the lock time is stopped.
- **Recording:** stopped on lock and refused while locked ("Locked: not recording").
- **Wake word training clips:** the guide stops and the audio held in memory is forgotten; no
  clip is caught, kept or sent until unlocked ([wake-word-training](wake-word-training.md)).
- **Music:** the Sendspin player is stopped (Music Assistant sees it leave) and started again on
  unlock if Sendspin is on. Home Assistant's `play-media`, `play`, `set-volume` actions are ignored.
- **What Home Assistant starts:** an announcement's audio is dropped and answered with `played`
  at its end, so Home Assistant doesn't wait on it; a conversation it starts (`transcribe`) gets an
  `audio-stop` at once, so no audio is sent. `toast-message`, `alarm` and `navigate` are ignored.
  `refresh`, `interrupt`, `stop`, `pause` and `get-logs` still work.

## Unlocking and locking by hand

| How | Effect |
|---|---|
| PIN on the lock screen | Unlocked until the schedule's **next lock time** (e.g. Monday 20:00 → Tuesday 18:00; Saturday → Monday 18:00). |
| Menu → Odysseus → **Lock now** | Locked until the schedule's **next opening** (leaving early on Tuesday → Wednesday 08:00). |
| Home Assistant `odysseus://unlock` | As the PIN. |
| Home Assistant `odysseus://lock` | As Lock now. |

A newer lock or unlock replaces the one before. Both survive a restart (SharedPreferences
`odysseus_lock`) and are dropped once their time has passed. Turning Lock mode off or on forgets them.

Five wrong PINs in a row, then each further wrong one waits 30 s, doubling up to 5 minutes. The
count survives a restart. The PIN is stored as a salted SHA-256 hash.

Once a PIN is set, every change in the Lock section (turning it off, the times, a new PIN) asks for
the PIN first. "Lock now" doesn't.

## From Home Assistant

The stock VACA integration (v0.14.0) has no lock entity and the app can't add one, so the lock goes
through its `vaca.send_event` action with the `navigate` event, which the app intercepts for
`odysseus://` URLs (they never reach the dashboard). Lock mode must be on, or the command is
ignored (logged). Example template switch (the state is HA's own guess, since the device doesn't
report it):

```yaml
switch:
  - platform: template
    switches:
      office_echo_lock:
        friendly_name: Office Echo locked
        turn_on:
          action: vaca.send_event
          target: { entity_id: assist_satellite.vaca_office_echo }
          data: { event_name: navigate, event_value: { url: "odysseus://lock" } }
        turn_off:
          action: vaca.send_event
          target: { entity_id: assist_satellite.vaca_office_echo }
          data: { event_name: navigate, event_value: { url: "odysseus://unlock" } }
```

Use any entity of the Echo's VACA device as the target.

## Time

The device's own time zone and clock (`persist.sys.timezone`). The state is worked out again every
15 s and at once after any change, so a scheduled lock lands within 15 s of the time. Daylight
saving changes keep the wall-clock times.

## Files

- `odysseus/lock/LockMode.kt` publishes `locked`; the rules are in `LockRules.kt`, the settings in
  `LockSettings.kt`, the screen in `LockOverlay.kt` and `LockSettingsSection.kt`.
- `odysseus/lock/SatelliteLock.kt`: the satellite's side, one per `Satellite` (held by
  `odysseus/OdysseusSatellite.kt`): `watch()` (started from `Satellite.start`) re-applies the wake
  word mute and stops a turn and all sound on lock; `serverPacket()` refuses what Home Assistant
  starts; `action()` handles `navigate odysseus://lock|unlock` and the ignored actions;
  `refusesWake()` ignores a wake word. Which packets and actions are refused is the pure
  `SatelliteLockRules.kt` (`IGNORED_ACTIONS`, `serverPacket`; `SatelliteLockRulesTest`).
- The wake word check in `refusesWake()` looks redundant with the muted engine and the ignored
  `wake` action, but isn't: the mute is applied by `watch()`'s collector after `LockMode` flips, so
  a detection already on its way at the lock time would otherwise start a turn.
- Hooks in `satellite/Satellite.kt` (one line each, through `OdysseusSatellite`): `start`,
  `processMessage` (one check for the run gate and Lock mode, custom actions included),
  `handleWakeWordDetection`, `muteMicrophone`; `wakeword/WakeWordEngine.kt` (a fresh engine starts
  muted); `MainActivity.kt` (the overlay, and closing the menu on lock, both in
  `odysseus/OdysseusOverlays.kt`).

## Logs

`Lock mode: locked (tick), until Mon 08:00`, `Lock mode: unlocked (PIN), ...`, `Lock mode: wrong PIN (n in a row)`,
`Lock mode: action <a> ignored, locked`, `Lock mode: announcement from Home Assistant dropped, locked`,
`SendspinManager: stopping the player, locked`.
