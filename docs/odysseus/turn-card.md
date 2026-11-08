# Voice turns: indicator, turn card, voice check, thinking sound, barge-in

The Echo Show shows that it is listening and what a voice turn did in Odysseus, tells Odysseus where each turn was asked and who asked it, plays a soft tone when an answer takes a while, and lets a new "Hey Athena" interrupt a turn. It follows the shared voice contract v1 (hub, Echo, Athena), §6 for the device.

## Listening indicator

The app draws its own overlay on top of the dashboard, the clock or the blank screen. It needs no HA configuration.

- A blue bar along the bottom pulses with "Listening…", and pulses faster once you start speaking.
- It turns violet with "Thinking…" while HA and Odysseus work, then green with "Answering…".
- The wake sound is VACA's own, set from HA ([echo-show-5.md](echo-show-5.md#wake-word-wake-sound-feedback)).

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
- **The context id:** the answer's `context_id` names this turn at the hub. The device keeps it for the turn (`OdysseusTurn.contextId`) and sends it with the turn's later requests, so the hub joins them exactly instead of by time: the turn card's feed (`context_id=` beside `since=`; a turn is taken as ours when it carries our id, else only when it names this device), the timings and a barge-in's interrupt. Until the hub has answered, or when the post failed, the turn has no id and those requests go without one, matched by time or device as before.
- **Logs:** the device logs the context id, the audio size and the speaker's `verified`/`is_owner`, never the audio.

## Thinking sound

When HA has the question (`AWAITING_RESPONSE`) and no answer has started 5 s later, the device plays one short soft tone (0.7 s, two notes, −20 dBFS) through VACA's sound-effect player.

- **At most once per turn.** None once the answer's TTS is on its way.
- **Never during a barge-in** (a wake word ends the old turn and its timer), **muted** (HA's mute switch or the privacy button), or **do-not-disturb**.
- **The sound:** generated locally from sine waves by `scripts/odysseus/make_sounds.py` into `app/src/main/assets/odysseus/thinking.wav`. The script is reproducible (same bytes every run); nothing is downloaded.

## Timings

Each voice turn notes when it reached each pipeline stage, in milliseconds after the wake word (`odysseus/TurnTimings.kt`): run started, listening, speech started and stopped, transcript, speech output asked for, audio starting, first audio, playback done, ended. When the turn has ended, the device posts them to Odysseus in the background (`POST <base>/satellite/timing`, JSON), with three flags: the thinking sound played, a wake word interrupted the turn (`barge_in`), "Check my voice" was on, and the turn's `context_id` when the satellite post gave one (the post waits for that answer if the turn ended first). Odysseus joins them to its own times for the same turn (getting ready, the model's first token, each tool call, first sentence sent), exactly by `context_id`, else by device and time, so every step of a turn can be measured: Home Assistant's speech to text and text to speech on the device's clock, Odysseus's steps on its own.

Only numbers, the device id, the context id and these flags are sent: never the question, the answer or audio. Nothing is sent without the Odysseus link; a failed post is only logged.

Read them on the server: `python scripts/voice_timings.py` in the Odysseus checkout (hub docs: `docs/fork/voice-assistant.md#timings`).

## Barge-in

Saying "Hey Athena" while the device is still thinking or answering stops that turn and starts listening again. Upstream only stops the turn, so the question said right after the wake word was lost.

- **Odysseus ends the old turn.** The app calls `POST <base>/satellite/interrupt` (`device_id`, and the interrupted turn's `context_id` when known; `odysseus/BargeIn.kt`). Odysseus ends that turn at once with an empty answer, and HA ends its run within about a second. This also works after a long "Thinking…".
- **The new turn waits for HA.** It starts only once HA has ended the interrupted run (`odysseus/HaRunGate.kt`). In the meantime the app keeps what you say, for up to 15 s. The interrupted answer is dropped and never played. Without the Odysseus link the app waits for HA to end the old run by itself, which happens only after the agent has answered.
- **Why the wait:** HA's Wyoming satellite (`homeassistant/components/wyoming/assist_satellite.py`) clears its "run in progress" flag on any run's `RUN_END`. Starting the new run first left the satellite deaf until it reconnected. This is worth reporting to HA.
- **Checked** in the hub's sandbox (a real Home Assistant and an emulator of this code): `docs/fork/sandbox.md#voice-barge-in` in `SDarioosh/odysseus-expanded`. History: [history.md](history.md).

### "Hey Athena, never mind"

When a wake word turn's transcript is only a cancel phrase ("never mind", "nevermind", "forget it", "cancel", "stop talking", "be quiet", "shut up", "nothing", "that's all", "no thanks"; "Athena", "oh", "please" and punctuation around it are ignored), the device ends the turn there and drops its answer the same way as a barge-in, so it stays quiet (`odysseus/Nevermind.kt`, one hook in `satellite/SatelliteAudioPipeline.kt`). Upstream checked for "never mind" only in a continued conversation, so after a barge-in Odysseus answered it out loud. In a continued conversation (a follow-up) only "never mind" ends it: "cancel" and "no" there answer Odysseus's question.

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

Every request to Odysseus goes through `OdysseusLink.send` (Bearer voice token, redirects not followed, timeouts and body cap as parameters; only the streamed update APK uses `openGet`). Features ask through one seam, `HubHttp` (`odysseus/HubHttp.kt`: `OdysseusLink` on the device, fakes in tests), with one `HubJson` and one status text (`hubStatusText`). The device and the hub are deployed together, so there are no fallbacks for older hubs.

## The feeds (hub side)

```
<base> = the OpenAI base URL Home Assistant uses: https://<odysseus>/api/chat/voice/v1
Authorization: Bearer <voice token>        401/403 without a `voice` token

GET <base>/turns/latest?since=<ms>&context_id=<id>   the turn of that context (null until it's finished); the hub answers by the id, an older one by time
GET <base>/turns/latest?since=<epoch ms>     a turn without a context id: the newest finished one since then
200 {"turn": {"id", "context_id", "started_at", "ended_at", "question", "answer",
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

- **When:** when the answer starts playing and again when the turn ends. If there's no turn yet, it asks once more 1.5 s later.
- **Which turn:** by the turn's `context_id` once the satellite post has answered; that turn is this device's. Without one, from 2 s before that turn's wake word, and a turn the hub names for another satellite is not shown.
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

- `odysseus/OdysseusLink.kt`: settings, the Keystore-encrypted token, and the hub client (`send`, `openGet`, `fetchLatestTurn`, `postSatelliteTurn`, `postTimings`, `interruptTurn`, `testLink`).
- `odysseus/HubHttp.kt`: `HubHttp`, `HubReply`, `HubJson`, `hubStatusText`: the one way features ask the hub (also used by the Today page, Done, announcements, updates and the recorder relay).
- `odysseus/VoiceTurnApi.kt`: the turn and satellite-post JSON, link filtering, the card's content, the multipart body (pure, unit-tested).
- `odysseus/TurnCard.kt`: the card, the source overlay, and fetching after a turn (`VoiceTurns`).
- `odysseus/Nevermind.kt`: the cancel phrases that end a turn unanswered (pure, `NevermindTest`); hook in `satellite/SatelliteAudioPipeline.kt` (`handleTranscript`).
- `odysseus/OdysseusTurn.kt`: one per voice turn: the satellite post, the thinking sound and the timings. Later per-turn features plug in here.
- `odysseus/TurnTimings.kt`: the turn's stage times and their JSON (unit-tested).
- `odysseus/TurnAudioRecorder.kt`: the copy of the turn's streamed audio, and the WAV writer (unit-tested).
- `odysseus/ThinkingChime.kt`: when the thinking sound plays (unit-tested); `assets/odysseus/thinking.wav`.
- `odysseus/ListeningIndicator.kt`: the listening indicator (also the "Microphone off" pill, [recording.md](recording.md#the-privacy-button)).
- `odysseus/BargeIn.kt`: the interrupt call to Odysseus; `odysseus/HaRunGate.kt`: waiting for HA to end the interrupted run (`HaRunGateTest`).
- `odysseus/OdysseusSatellite.kt`: the one fork object `Satellite` holds: `onWakeWord` (barge-in), `onServerPacket`, `awaitHaIdle`, `beginTurn`, `onStage`; reset on a new satellite and on every `clientId` change.
- `odysseus/OdysseusOverlays.kt`: what `MainActivity` composes over the screen (the indicator, the card, …).
- `odysseus/followup/`: listening for the "yes" without the wake word ([follow-up.md](follow-up.md)); `VoiceTurns.awaitingConfirmation()` in `TurnCard.kt`.
- `odysseus/OdysseusSettingsLayout.kt`: Menu → Odysseus. `odysseus/EchoSetup.kt` and the files next to it: the phone setup ([setup.md](setup.md)).
- Hooks: `MainActivity.kt` (the overlays, through `OdysseusOverlays`), `ui/layouts/SettingsLayout.kt` (the tile and screen), `satellite/Satellite.kt`, one line each through `OdysseusSatellite`: `clientId` (resets the run gate), `processMessage` (`onServerPacket`), `handleWakeWordDetection` (`onWakeWord`), the gate wait (`awaitHaIdle`), `startAudioPipeline` (`beginTurn`, `onStage`; `tee` on the pipeline's microphone flow).
- Tests: `app/src/test/java/com/msp1974/vacompanion/odysseus/` (`VoiceTurnApiTest`, `TurnTimingsTest`, `TurnAudioRecorderTest`, `ThinkingChimeTest`, `HaRunGateTest`, `OdysseusLinkHttpTest`, `OdysseusLinkFailureTextTest`; `./gradlew :app:testDebugUnitTest`).

## Testing without the hub feed

The card was first tested against a stand-in for the feed with fictional actions ([history.md](history.md#0140odysseus6-voice-actions-card)). The same stand-in can serve `/turns/latest` and `/satellite/turn` (fictional data only).

## Still to check on the real device

- The card while the answer plays: it should appear when TTS starts and not flicker when the turn ends and it's fetched again.
- A source chip opens the overlay; the page loads (Athena `/view` link over LAN or Tailscale); ✕ closes it; the next wake word closes it too.
- A confirmation: the question stays on the card during HA's follow-up; "yes" shows the done action, "no" clears it.
- `POST /satellite/turn` arrives before HA calls the agent (hub log: the context is claimed, not missed), with the audio of the whole command and not the wake sound; speaker check result for the owner's voice at 1 m and 3 m.
- The thinking sound after 5 s on a slow turn: audible but soft at the usual volume, once, not after a barge-in, not with mute or DND on; it doesn't trigger the wake word.
- Timings: after a few questions, `scripts/voice_timings.py` on the server lists them with the device's steps (speech to text, text to speech) next to Odysseus's.
- Memory: 15 s of audio is 480 KB per turn; check nothing builds up over many turns on the 1 GB device.
