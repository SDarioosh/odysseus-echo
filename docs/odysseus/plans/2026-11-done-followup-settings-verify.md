# Plan: Done buttons, answer without the wake word, settings from the hub, verification kit

Written 2026-11-06, after the first round (`2026-11-today-updates-announce-record.md`) was merged
(odysseus-echo#11 and #12, odysseus-expanded#178). Same way of working: one phase is one
session, in one repo. Contracts below are shared by the hub and the Echo.

## Phases

| Phase | Repo | What |
|---|---|---|
| V1 | hub (+ Athena, run only) | Verification kit: an end-to-end run of round 1 in the sandbox, and scripts for the owner |
| H5 | hub | Done routes for to-dos, reminders and Athena action items (C5) |
| H6 | hub | Per-device settings: store, admin page, device route (C6) |
| E7 | echo | Done buttons on the Today page and reminder cards (C5) |
| E8 | echo | Answer "yes" without the wake word (follow-up listening; no hub change) |
| E9 | echo | Apply settings managed by the hub (C6) |

All can run at once; E7 and E9 build against the contracts with fakes.

## C5. Done for devices

Scope `voice`. A tap on the device's own screen is the confirmation, so these need no spoken
"yes". Each is recorded in the voice ledger's actions as `completed`, with the device as the
source. All are idempotent: already done → 200 with `"already": true`.

```
POST /api/chat/voice/v1/todos/done        {"ref": "todo:41:2", "device_id": "..."}   -> {"ok": true, "already": false, "title": "Call the plumber"}
POST /api/chat/voice/v1/reminders/done    {"ref": "todo:41", "device_id": "..."}     -> {"ok": true, "already": false}
POST /api/chat/voice/v1/action-items/done {"id": "ai_17", "device_id": "..."}        -> {"ok": true, "already": false}
```

- `todos/done`: ticks item 2 of note 41 (the same change as the to-do page's tick).
- `reminders/done`: a reminder is a whole note with a due date. Done = the reminder won't fire
  again: tick the note's single item if it has one, else clear its due date. Pick the behaviour
  that matches how the hub's own UI completes a reminder, and document it.
- `action-items/done`: through the hub's Athena link, `update_action_item` with status done.
  503 with `{"error": "athena unavailable"}` when Athena can't be reached.
- 404 `{"error": "not found"}` for an unknown ref or id; 400 for a malformed one.
- **Undo** (same scope): `POST /api/chat/voice/v1/todos/undo {"ref"}` and
  `/action-items/undo {"id"}` reopen within 10 minutes of a done by a device; 409 after that.

## C6. Device settings managed by the hub

The hub stores settings per device, keyed by the device id the Echo sends in
`satellite/hello`. A key that is absent or `null` means "the device decides". The device
fetches them, so the hub never needs to reach the device.

```
GET /api/chat/voice/v1/devices/{device_id}/settings     (scope voice)
-> {"version": 7, "settings": {"kiosk.return_seconds": 600, "today.enabled": true, ...}}
```

Keys and values (only these; unknown keys are ignored by the device):

| Key | Type | Device setting |
|---|---|---|
| `home.enabled` | bool | clock home screen |
| `home.idle_seconds` | int (0 = never) | back to the clock after |
| `home.weather_enabled` | bool | weather on the clock |
| `today.enabled` | bool | Today page |
| `kiosk.return_seconds` | int (0 = never) | back from other apps after |
| `kiosk.auto_reload` | bool | reload a failed dashboard |
| `record.enabled` | bool | recording |
| `record.clock_button` | bool | record button on the clock |
| `record.privacy_button` | `"record"` / `"mute"` | privacy button |
| `followup.enabled` | bool | answer without the wake word (E8) |
| `followup.seconds` | int 3..15 | how long it listens |

- `version` goes up on every change. The device fetches at start, after each hello (hourly),
  and when the announcements feed delivers an item with `kind: "settings"` (H6 sends one to the
  device whenever its settings change, so a change arrives within 30 s).
- The admin page lists the devices from the registry (name, room, app version, last seen) and
  lets the owner set each key or leave it to the device. Admin only.
- Changes are logged (owner, device, key) without values that could be private (none of these
  are).

## E8. Answer "yes" without the wake word

- When a voice turn ends and the turn card shows `awaiting_confirmation` for **this** device
  (`VoiceTurnApi.isFromDevice`), the device starts listening again by itself once its answer
  has finished playing: a soft "listening" chime (not the wake sound), then a pipeline as if the
  wake word was heard (the existing manual-wake path), for `followup.seconds` (default 8).
- Only the device that answered listens; no arbitration report.
- Nothing heard (VAD/STT returns empty) → the pipeline ends quietly; no second retry.
- Setting "Answer without the wake word" (default on) in Menu → Odysseus, and C6's
  `followup.*` keys.
- Not while muted, in DND, recording a voice note, or when the screen is blanked by the owner.

## V1. Verification kit

1. **Sandbox end-to-end run** (hub on 127.0.0.1:7010 with fictional data, Athena's ingest and
   MCP servers from `/home/user/Athena`, no real HA; see the hub's `docs/fork/sandbox.md`):
   - call every round-1 route over real HTTP with an Echo token made by `/echo/link`'s code
     path: `/today`, `/app/latest`, `/app/apk` (with a published fake release),
     `/satellite/hello`, `/announcements`;
   - upload a 3 s WAV voice note through `/api/codex/athena/chunk` with `kind=note`, `/end`,
     and check Athena stored a note session;
   - fix any bug found in the hub (in its own commits); report Athena bugs without fixing.
2. **Owner scripts**:
   - hub `scripts/echo_verify.py --url ... --token ...` runs the same checks against the real
     server, read-only except a clearly labelled test announcement and test voice note it then
     deletes;
   - echo `scripts/odysseus/verify_device.sh` (adb) prints the app version, the settings, the
     privacy-button `getevent` capture (guided: "press once now… hold now…"), the key layout
     status, recent `Recorder|Kiosk|Today|Update|Announce` log lines, and a PASS/CHECK list.
