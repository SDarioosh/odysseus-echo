# Voice actions on the screen

The Echo Show shows what a voice turn **added** or **completed** in Odysseus: a card at the top of the screen for 15 s. You can tap it away, and the next "Hey Athena" clears it.

| Row | Icon | Example |
|---|---|---|
| Added to-do or reminder | checklist / alarm (blue) | **Call the dentist** · To-dos · Tomorrow, 9:00 AM |
| Added list line | checklist (blue) | **Milk** · Shopping list |
| Added event | calendar (blue) | **Lunch with Sam** · Calendar · Friday, 12:00 PM |
| Added note | note (blue) | **Garage code is changing** · Notes |
| Completed (Odysseus or Athena) | check (green), title struck through | ~~Send the revised spreadsheet to Alice~~ · Done · Athena |

The card shows up to four rows, then "and N more". It's sized for the Echo Show 5's 960×480 screen.

## Why the device asks Odysseus itself

Home Assistant gives the satellite only the spoken answer. Extended OpenAI Conversation keeps only the reply's text, so nothing structured ever reaches the device. After each turn, the device therefore asks Odysseus what that turn did, using the same voice token Home Assistant uses.

## The feed (hub side)

```
GET <base>/actions?since=<epoch ms>&limit=<n>
Authorization: Bearer <voice token>

<base> = the OpenAI base URL Home Assistant uses: https://<odysseus>/api/chat/voice/v1
200 {"actions": [
  {"id": "…", "at": "2026-11-03T19:52:10+00:00",
   "action": "created" | "completed",
   "kind": "todo" | "reminder" | "list_item" | "note" | "event" | "action_item",
   "title": "Call the dentist", "detail": "full text or null",
   "due": "2026-11-04T09:00:00-05:00" | "2026-11-07" | null, "all_day": false,
   "where": "To-dos" | "Shopping list" | "Calendar" | "Notes" | "Athena" | …,
   "source": "odysseus" | "athena"}
]}
```

- **Order and window:** newest first, only actions done by voice at or after `since`.
- **Auth:** 401/403 without a `voice` token.
- **Unknown fields:** the device ignores them.

The device asks once when a turn ends. If the answer is empty it asks once more 1.5 s later, from 2 s before that turn's wake word.

## Setup on the device

### Set up with your phone (QR code)

The phone setup links the Echo to Odysseus as one of its steps: see [setup.md](setup.md).
Menu → Odysseus → **Set up with phone (QR code)** runs it again on an Echo that is already set up.

### Or type them

1. Open the menu: a two-finger swipe up from the bottom, then **Menu → Odysseus**.
2. Enter the base URL and the voice token (Odysseus → Settings → Integrations → Voice assistant).
3. Tap **Save and test**. It shows "Linked: Odysseus answered", or the error ("token refused (403)", "Odysseus has no actions feed (404)", …).

The token is stored encrypted with an Android Keystore key, kept in the app's private storage, and never shown again. **Unlink** removes it.

The URL must be reachable from the device: Odysseus's LAN or Tailscale address, as for Home Assistant. When the Echo Show reaches Home Assistant through Tailscale (`tailscale.md`), use Odysseus's Tailscale name.

## Files

- `odysseus/OdysseusLink.kt`: settings, the Keystore-encrypted token, and the feed client.
- `odysseus/VoiceActionsCard.kt`: the card, and fetching after a turn.
- `odysseus/OdysseusSettingsLayout.kt`: Menu → Odysseus.
- `odysseus/EchoSetup.kt` and the files next to it: the phone setup ([setup.md](setup.md)).
- Hooks: `MainActivity.kt` (the overlay), `ui/layouts/SettingsLayout.kt` (the tile and screen).

## Testing without the hub feed

`docs/odysseus/echo-show-5.md` ("Voice actions card test") uses a stand-in for the feed with fictional actions. The two-finger menu gesture was injected with root `sendevent`, because `adb shell input` has one finger only.
