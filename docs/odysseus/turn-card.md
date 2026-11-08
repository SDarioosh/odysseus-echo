# Turn card, voice check and thinking sound

The Echo Show shows what a voice turn did in Odysseus, tells Odysseus where each turn was asked and who asked it, and plays a soft tone when an answer takes a while. It follows the shared voice contract v1 (hub, Echo, Athena), §6 for the device.

## The turn card

A card at the top of the screen, from the moment the answer starts playing. It stays for 15 s, or 60 s while Odysseus waits for a "yes". You can tap it away, and the next "Hey Athena" clears it.

| Part | Shows | Example |
|---|---|---|
| Added to-do or reminder | checklist / alarm (blue) | **Call the dentist** · To-dos · Tomorrow, 9:00 AM |
| Added list line | checklist (blue) | **Milk** · Shopping list |
| Added event | calendar (blue) | **Lunch with Sam** · Calendar · Friday, 12:00 PM |
| Added note | note (blue) | **Garage code is changing** · Notes |
| Completed (Odysseus or Athena) | check (green), title struck through | ~~Send the revised spreadsheet to Alice~~ · Done · Athena |
| Waiting for a "yes" | question mark (amber) | **Add Dentist on Tuesday at 3 PM to your calendar?** · Say "yes" to confirm or "no" to cancel |
| Sources | the answer (2 lines), then up to 3 chips | 🔗 Budget review · 🔗 example.org |

- **Rows:** up to four action rows, fewer when the card also has a question or sources, then "and N more". It's sized for the Echo Show 5's 960×480 screen.
- **Sources:** a chip shows the hub's label, else the link's host. Only `http`/`https` links are shown. A tap opens the page full screen inside the app, with a close button (✕) at the top right. Links open in that view only, never in another app. The page loads from wherever the link points: Athena's transcript links are on the owner's network; a web source is fetched from the web only when you tap it.
- **Confirmations:** HA keeps the microphone open after an answer that ends with "?". When it doesn't, but Odysseus waits for a "yes" from this device, the device listens again by itself after a soft tone ([follow-up.md](follow-up.md), "Answer without the wake word"). The card for that question stays while you answer. When the follow-up turn ends, the card shows what "yes" did, or goes away after "no".
- **Nothing to show:** a turn that added nothing, has no sources and asks nothing shows no card.

## The satellite post and "Check my voice"

When you stop speaking (pipeline stage `VOICE_STOPPED`, or the transcript if HA skips that), the device posts the turn's context to Odysseus in the background, before HA calls the agent. It never holds up or fails the turn.

```
POST <base>/satellite/turn   multipart/form-data, Authorization: Bearer <voice token>
  device_id    VACA's device id (APPConfig.uuid)
  device_name  Menu → Odysseus → Device name (HA never sends the satellite its own name); blank: "VACA <id>"
  area         the HA area this device is in (the one used for ducking), may be empty
  wake_word    the wake word id, e.g. hey_athena
  started_at   epoch ms of the wake word (the follow-up's start for a follow-up)
  audio        command.wav: 16 kHz mono 16-bit, at most 15 s; only with "Check my voice" on
-> {"ok": true, "context_id": "…", "speaker": {"verified", "is_owner", "name", "score"} | null}
```

- **Audio:** exactly the frames the pipeline streamed to HA for this turn: the audio kept since the wake word, then live audio until HA's VAD stopped listening. The first 15 s are kept. It's held in memory for that turn only, never written to storage or logged.
- **Hub side:** Odysseus forwards it to Athena's speaker check and never stores it. Athena compares it with the enrolled voices, so personal answers can be kept to the owner's voice (`FORK_VOICE_PRIVATE` on the hub).
- **Off:** with "Check my voice" off, the post carries no audio; the hub still learns the device and area.
- **Logs:** the device logs the context id, the audio size and the speaker's `verified`/`is_owner`, never the audio.

## Thinking sound

When HA has the question (`AWAITING_RESPONSE`) and no answer has started 5 s later, the device plays one short soft tone (0.7 s, two notes, −20 dBFS) through VACA's sound-effect player.

- **At most once per turn.** None once the answer's TTS is on its way.
- **Never during a barge-in** (a wake word ends the old turn and its timer), **muted** (HA's mute switch or the privacy button), or **do-not-disturb**.
- **The sound:** generated locally from sine waves by `scripts/odysseus/make_thinking_sound.py` into `app/src/main/assets/odysseus/thinking.wav`. The script is reproducible (same bytes every run); nothing is downloaded.

## Timings

Each voice turn notes when it reached each pipeline stage, in milliseconds after the wake word (`odysseus/TurnTimings.kt`): run started, listening, speech started and stopped, transcript, speech output asked for, audio starting, first audio, playback done, ended. When the turn has ended, the device posts them to Odysseus in the background (`POST <base>/satellite/timing`, JSON), with three flags: the thinking sound played, a wake word interrupted the turn (`barge_in`), "Check my voice" was on. Odysseus joins them to its own times for the same turn (getting ready, the model's first token, each tool call, first sentence sent), so every step of a turn can be measured: Home Assistant's speech to text and text to speech on the device's clock, Odysseus's steps on its own.

Only numbers, the device id and these flags are sent: never the question, the answer or audio. Nothing is sent without the Odysseus link; a failed post is only logged.

Read them on the server: `python scripts/voice_timings.py` in the Odysseus checkout (hub docs: `docs/fork/voice-assistant.md#timings`).

## Settings (Menu → Odysseus)

| Setting | Default | What it does |
|---|---|---|
| Show what a voice turn did | on | the turn card |
| Check my voice | on | sends the spoken command with the satellite post |
| Thinking sound | on | the tone after 5 s of waiting |
| Answer without the wake word | on | listens for the "yes" by itself, 8 s by default ([follow-up.md](follow-up.md); own file `odysseus_followup`) |
| Device name told to Odysseus | blank ("VACA <id>") | the `device_name` Odysseus puts in the turn's context ("Asked from: Kitchen Echo, Kitchen") |

Nothing is fetched or posted until the device is linked (base URL and token below). They're stored on the device (SharedPreferences, `odysseus_link`), not sent to HA.

## Why the device asks Odysseus itself

Home Assistant gives the satellite only the spoken answer. Extended OpenAI Conversation keeps only the reply's text, so nothing structured ever reaches the device. The device therefore asks Odysseus what each turn did, using the same voice token Home Assistant uses. HA also tells the agent nothing about the satellite (which device, which room, whose voice), so the device posts that itself.

## The feeds (hub side)

```
<base> = the OpenAI base URL Home Assistant uses: https://<odysseus>/api/chat/voice/v1
Authorization: Bearer <voice token>        401/403 without a `voice` token

GET <base>/turns/latest?since=<epoch ms>
200 {"turn": {"id", "started_at", "ended_at", "question", "answer",
              "links": [{"url", "label" | null}], "actions": [<action>, …],
              "awaiting_confirmation": "…?" | null, "device": {…} | null, "speaker": {…} | null,
              "session_id"}} | {"turn": null}

<action>:
  {"id": "…", "at": "2026-11-03T19:52:10+00:00",
   "action": "created" | "completed",
   "kind": "todo" | "reminder" | "list_item" | "note" | "event" | "action_item",
   "title": "Call the dentist", "detail": "full text or null",
   "due": "2026-11-04T09:00:00-05:00" | "2026-11-07" | null, "all_day": false,
   "where": "To-dos" | "Shopping list" | "Calendar" | "Notes" | "Athena" | …,
   "source": "odysseus" | "athena", "turn_id": "…"}
```

- **When:** when the answer starts playing and again when the turn ends, from 2 s before that turn's wake word. If there's no turn yet, it asks once more 1.5 s later.
- **Errors:** any answer but a 200 (a 404 too) is a failure: logged, no card. The device and the hub are deployed together, so the feed is always there.
- **Unknown fields:** the device ignores them.

## Setup on the device

### Set up with your phone (QR code)

The phone setup links the Echo to Odysseus as one of its steps: see [setup.md](setup.md).
Menu → Odysseus → **Set up with phone (QR code)** runs it again on an Echo that is already set up.

### Or type them

1. Open the menu: a two-finger swipe up from the bottom, then **Menu → Odysseus**.
2. Enter the base URL and the voice token (Odysseus → Settings → Integrations → Voice assistant).
3. Tap **Save and test**. It shows "Linked: Odysseus answered", or the error ("Token refused (403)", "HTTP 404", …).

The token is stored encrypted with an Android Keystore key, kept in the app's private storage, and never shown again. **Unlink** removes it.

The URL must be reachable from the device: Odysseus's LAN or Tailscale address, as for Home Assistant. When the Echo Show reaches Home Assistant through Tailscale (`tailscale.md`), use Odysseus's Tailscale name.

## Files

- `odysseus/OdysseusLink.kt`: settings, the Keystore-encrypted token, and the hub client (`fetchLatestTurn`, `fetchActions`, `postSatelliteTurn`, `postTimings`, `testLink`).
- `odysseus/VoiceTurnApi.kt`: the turn and satellite-post JSON, link filtering, the card's content, the multipart body (pure, unit-tested).
- `odysseus/TurnCard.kt`: the card, the source overlay, and fetching after a turn (`VoiceTurns`).
- `odysseus/OdysseusTurn.kt`: one per voice turn: the satellite post, the thinking sound and the timings. Later per-turn features plug in here.
- `odysseus/TurnTimings.kt`: the turn's stage times and their JSON (unit-tested).
- `odysseus/TurnAudioRecorder.kt`: the copy of the turn's streamed audio, and the WAV writer (unit-tested).
- `odysseus/ThinkingChime.kt`: when the thinking sound plays (unit-tested).
- `odysseus/followup/`: listening for the "yes" without the wake word ([follow-up.md](follow-up.md)); `VoiceTurns.awaitingConfirmation()` in `TurnCard.kt`.
- `odysseus/OdysseusSettingsLayout.kt`: Menu → Odysseus. `odysseus/EchoSetup.kt` and the files next to it: the phone setup ([setup.md](setup.md)).
- Hooks: `MainActivity.kt` (the card overlay), `ui/layouts/SettingsLayout.kt` (the tile and screen), `satellite/Satellite.kt` (`startAudioPipeline`: `OdysseusTurn.begin` and `onStage`, next to the listening indicator, through `odysseus/OdysseusSatellite.kt` (`beginTurn`, `onStage`); `tee` on the pipeline's microphone flow). The card overlay is composed in `odysseus/OdysseusOverlays.kt`.
- Tests: `app/src/test/java/com/msp1974/vacompanion/odysseus/` (`./gradlew :app:testDebugUnitTest`).

## Testing without the hub feed

`docs/odysseus/echo-show-5.md` ("Voice actions card test") uses a stand-in for the feed with fictional actions. The two-finger menu gesture was injected with root `sendevent`, because `adb shell input` has one finger only. The same stand-in can serve `/turns/latest` and `/satellite/turn` (fictional data only).

## Still to check on the real device

- The card while the answer plays: it should appear when TTS starts and not flicker when the turn ends and it's fetched again.
- A source chip opens the overlay; the page loads (Athena `/view` link over LAN or Tailscale); ✕ closes it; the next wake word closes it too.
- A confirmation: the question stays on the card during HA's follow-up; "yes" shows the done action, "no" clears it.
- `POST /satellite/turn` arrives before HA calls the agent (hub log: the context is claimed, not missed), with the audio of the whole command and not the wake sound; speaker check result for the owner's voice at 1 m and 3 m.
- The thinking sound after 5 s on a slow turn: audible but soft at the usual volume, once, not after a barge-in, not with mute or DND on; it doesn't trigger the wake word.
- Timings: after a few questions, `scripts/voice_timings.py` on the server lists them with the device's steps (speech to text, text to speech) next to Odysseus's.
- Memory: 15 s of audio is 480 KB per turn; check nothing builds up over many turns on the 1 GB device.
