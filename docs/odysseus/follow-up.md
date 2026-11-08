# Answer "yes" without the wake word (follow-up listening)

When Odysseus asks to confirm something ("Add Dentist on Tuesday at 3 PM to your calendar?"), the
Echo that answered listens again by itself once its answer has finished playing. You hear a soft
rising tone, the screen shows "Listening…", and you just say "yes" or "no". No hub change: the
device decides from the turn card's `awaiting_confirmation` (`GET /turns/latest`,
[turn-card.md](turn-card.md)).

## When it listens

All of these:

- the turn was started by a wake word on this device (spoken, or a manual wake) and ended normally
  (not interrupted by a barge-in, not timed out, no error);
- Odysseus waits for a spoken "yes" **from this device** on that turn (`awaiting_confirmation` set and
  `VoiceTurnApi.isFromDevice`). The card already fetched when the answer started is used; if there is
  none for this turn yet, the device asks the hub (two tries 1.5 s apart, at most 5 s), whether or not
  the card is shown. A card tapped away counts as no;
- **Home Assistant didn't continue the conversation itself** (see below);
- the answer has finished playing;
- not muted (Home Assistant's mute switch, or the privacy button in Mute mode), not in
  do-not-disturb, no recording or voice note running (`odysseus/record/`), the screen not blanked by
  the owner (the screen saver / black screen), and no other turn started meanwhile (a wake word, a
  question from the server);
- "Answer without the wake word" is on (default).

## How it listens

- The listening sound (`assets/odysseus/listening.wav`, 0.35 s, two notes going up, −22 dBFS; not the
  wake sound), then a pipeline started the manual-wake way: **only this device**, no arbitration report
  (a manual wake doesn't contest either), other devices in its duck group duck as for any answer. The
  microphone audio is kept from the end of the sound (`DetectionBuffer`), so a "yes" said right away
  isn't lost while Home Assistant gets ready.
- The run is Home Assistant's follow-up kind (`PipelineStartMode.CONTINUE_CONVERSATION`: `run-pipeline`
  from speech-to-text, no `detection` event), the same as when HA continues a conversation. HA keeps
  the same conversation for a few minutes, so Odysseus gets the "yes" in the conversation that asked.
  "Never mind" ends it, as in any follow-up. The turn card keeps showing the question until the answer
  to it arrives.
- It listens for **`followup.seconds`** (default 8, 3–15) for speech to start. Nothing heard → it ends
  quietly: no error sound, no toast (the time-out, or HA's `stt-no-text-recognized`). **One try only**:
  a follow-up is never followed by another one.
- A wake word during the follow-up is a barge-in, as during any turn.

## Home Assistant's own "continue conversation"

HA already has a way to keep listening: when the conversation agent's result has
`continue_conversation` (HA sets it when an answer ends with a question mark), the Wyoming `handled`
event says so and VACA starts a `CONTINUE_CONVERSATION` run itself once the answer has played
(`Satellite.startAudioPipeline` → `onFinish`); the upstream setting "Continue conversation" forces it
on for every turn. HA doesn't start that run itself: the satellite does. So the two could double up
only on the device, and they don't:

- when HA continues (`continueConversation` true in `onFinish`), the follow-up is not offered at all;
- a follow-up is offered only after a wake-word turn, never after a `CONTINUE_CONVERSATION` run (HA's or
  ours), so neither starts after the other;
- the follow-up is started only when no pipeline runs and no wake word is being handled.

The follow-up therefore covers what HA's mechanism misses: Odysseus waits for a "yes", but HA
didn't set `continue_conversation` (the agent integration doesn't pass it, or the question doesn't end
with "?").

## Settings

Menu → Odysseus, under "Thinking sound". Stored on the device in their own SharedPreferences file
(`odysseus_followup`), not sent to Home Assistant. The keys are those of the hub-managed settings
(plan contract C6), so Odysseus can set them later:

| Setting | Key | Values | Default |
|---|---|---|---|
| Answer without the wake word | `followup.enabled` | on / off | on |
| Listen for | `followup.seconds` | 3, 5, 8, 10, 15 s (any 3–15 from the hub) | 8 s |

For the hub-managed settings: `FollowUpSettings.enabled` and `FollowUpSettings.seconds` are the
setters (Compose state, persisted; seconds are clamped to 3–15), `FollowUpSettings.KEY_ENABLED` and
`KEY_SECONDS` the keys.

## Logs

`adb logcat | grep Follow-up`:

- `Follow-up: Odysseus waits for a yes; listening up to 8 s without the wake word`
- `Follow-up: not listening (NOT_AWAITING)` (or `MUTED`, `MIC_MUTED`, `DO_NOT_DISTURB`, `RECORDING`,
  `SCREEN_BLANKED`, `BUSY`, `NEW_TURN`, `DISABLED`; debug level)
- `Follow-up: satellite busy, not listening`

## Files

- `odysseus/followup/FollowUpRules.kt`: when it listens, the time limits, the guards (pure, unit-tested:
  `FollowUpRulesTest`).
- `odysseus/followup/FollowUp.kt`: after a turn: waits for the answer to finish, asks the turn card,
  checks the guards, starts the follow-up; the listening sound.
- `odysseus/followup/FollowUpSettings.kt`, `FollowUpSettingsSection.kt`: the settings and their screen.
- `odysseus/TurnCard.kt`: `VoiceTurns.awaitingConfirmation()`.
- `assets/odysseus/listening.wav`, made by `scripts/odysseus/make_listening_sound.py` (sine waves,
  reproducible, nothing downloaded).
- `FollowUp.startListening`: the follow-up's pipeline, started the manual-wake way, through
  `FollowUp.Host` (what it needs of the satellite; `odysseus/OdysseusSatellite.kt` implements it over
  `Satellite`'s private state; `FollowUpStartTest` with a fake host).
- Hooks: `satellite/Satellite.kt` (`startAudioPipeline`: `odysseus.afterTurn` in `onFinish`, the
  `followUpListenMs` parameter), `satellite/SatelliteAudioPipeline.kt`
  (`followUpListenMs`: the listening limit in `hasExceededTimeInStage`, "nothing heard" in
  `handlePipelineError`), `odysseus/OdysseusSettingsLayout.kt` (one line).

## Still to check on the real device

- A question Odysseus asks without HA continuing: the tone right after the answer, "Listening…", "yes"
  is understood and the card shows what it did; "no" clears it.
- A question HA continues itself (answer ending with "?"): one listening run only, no tone.
- Silence for 8 s: it stops quietly, no error sound; no second try.
- Muted, DND on, a voice note recording, the screen blanked: no tone, no listening.
- The tone is audible but soft at the usual volume, isn't in the transcript, and doesn't trigger the
  wake word; the first word after it isn't cut.
- Two Echos in a room: only the one that answered listens; the other doesn't react.
