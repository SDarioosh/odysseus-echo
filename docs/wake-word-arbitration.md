# Wake word arbitration

How several VACA devices decide which one answers when more than one hears the same wake word, how
they find each other, and how interruptions and music ducking fit in.

Code: `app/src/main/java/com/msp1974/vacompanion/broadcasts/` (`VacaUdpBroadcast`, `WakeWordArbiter`,
`PeerPresence`), `satellite/Satellite.kt` (`handleWakeWordDetection`, `interruptResponse`), `audio/`
(`DetectionBuffer`, `MicLevelMonitor`, `WakeLevelCalibration`), `wakeword/DetectionRearmGate.kt`.
Odysseus fork: the choice and the level are in `odysseus/arbitration/` (`WakeWordPickRules`,
`WakeLevelMeter`, `WakeLevelReading`, `WakeDecisionLog`); see [Same-room devices](#same-room-devices).
Keep-alive, failover and presence timing (Odysseus fork): `odysseus/power/MasterKeepAlivePolicy.kt`;
when Wi-Fi leaves power save: `odysseus/power/WifiLockPolicy.kt` and `WifiPerformance.kt` (see
[Wi-Fi power save](#wi-fi-power-save)).

## In short

- Devices on the same network talk over a small UDP broadcast protocol and elect one **master**.
- Arbitration is **by wake word**: every device that hears a given wake word, in any room, reports it
  to the master, and the master picks one. **Only devices that reported it take part in the decision.**
  A device that didn't hear it is never consulted, told to stop, or counted as busy.
- The winner is normally the device with the **loudest calibrated wake word level**, i.e. the one the
  speaker is nearest to or facing. Only when the device that answered last is within 1.5 dB of the
  loudest does it keep answering; equal levels fall back to detection score, then device id.
- A reporting device that is **already responding** always wins: the wake word interrupts its
  response, and the other devices that heard it do nothing.
- Every device that hears the wake word starts **buffering audio** straight away, so nothing said
  while the decision is made is lost. The winner streams that buffer and then live audio to Home
  Assistant; the others drop theirs.
- **Ducking** (lowering music on nearby devices) is separate. It follows each device's **area or custom
  zone**, not the wake word.

## Switching it off

Under Settings → Experimental → Voice assistant, each part can be turned off on a device (arbitration
is on by default, ducking follows the area). Every setting in that group is stored on the device and
survives a restart, and the app logs their values (`Experimental settings: ...`) when wake word detection
starts:

- **Wake word arbitration** off: the device doesn't report to the master or abide by its decisions. It
  answers every wake word it hears, and a response it is giving is still interrupted by its own wake
  word, but it doesn't tell the others.
- **Ducking scope** set to *Disabled* (the other choices are *Area* and *Custom zone*): the device
  neither sends `duck` when it answers nor lowers its music when it receives one. Its duck group is
  blank, so it matches no other device. Its own music is still lowered while it speaks.
  (`pipeline_completed` is always sent and handled, since unducking something that never ducked does
  nothing.)

The custom ducking zone setting lives there too and only applies in *Custom zone* scope.

## Two kinds of group

| | Arbitration | Ducking |
|---|---|---|
| Grouped by | the **wake word** (case-insensitive) | the device's **duck group**: `area:<ha area>` (default) or `zone:<zoneN>`, set by the duck scope setting |
| Who is involved | every device on the network listening for that wake word **that heard it** | devices with the same, non-blank duck group |
| Blank | a device with no wake word (`none`) doesn't take part | no area (area scope) means no ducking |
| Used for | who answers or interrupts | who lowers music when another device starts answering |

A consequence of grouping by wake word: if two devices in different rooms both hear the **same wake
word at the same moment**, only one of them answers. Devices with different wake words never affect
each other, and a device that did not hear it has no part in the decision.

## Messages

JSON datagrams on UDP port 10950: `{"magic":"VACA","v":1,"type":"...","sender":"<device uuid>","data":{...}}`.
Every message also carries the sender's duck group (`duck_group`) in `data`. Anything that doesn't
parse, lacks the magic, or comes from the device itself is dropped. Most are broadcast; a message only
one device needs goes to that device alone (unicast, same format, same port), because a device in
Wi-Fi power save gets a unicast frame when it next wakes but may sleep through a broadcast (see
[Wi-Fi power save](#wi-fi-power-save)). The address is where the device was last heard from.

| Type | Sent by | To | Meaning / extra fields |
|---|---|---|---|
| `device_hello` | a device whose listener has just come up | everyone | "I'm here"; the master answers with a heartbeat and every device answers with `presence`, both to it alone |
| `master_claim` | a device taking over as master | everyone | election; `wake_word`. Every device answers with `presence` to the new master; a master with a lower id answers with a heartbeat to the claimer |
| `master_heartbeat` | the master | everyone, every 5 s; the asker alone in reply to a hello, query or claim | keep-alive; `wake_word` (the master's presence) |
| `master_query` | a device that has heard nothing from the master for 12.5 s | everyone | "is there a master?"; only the master answers, with a heartbeat to it alone |
| `master_resign` | the master, when the app stops | everyone | the others elect at once |
| `presence` | every device that isn't master: every 30 s and when its wake word changes; in reply to a hello or claim | the master (everyone if none is known); the asker | `wake_word`: the wake word it listens for |
| `wake_detection` | a device that heard the wake word and needs a decision | the master, and a broadcast copy | `id`, `score`, `level` (calibrated dB, optional), `floor` (calibrated noise floor in dB, optional, only logged), `wake_word`, `responding` (true if the device was responding when it heard it). The master counts each `id` once |
| `wake_decision` | the master | each reporting device, and a broadcast copy | `winner` (the winning report's id), `ids` (all reports it covers) |
| `duck` | the device that is going to answer | everyone | others in its duck group lower their music |
| `pipeline_completed` | the device whose response has ended | everyone | others in its duck group unduck |

The wire format changed in the Odysseus fork (unicast, `master_query`, `master_resign`, `wake_word` on
heartbeats and claims, presence to the master only, `floor` on `wake_detection`); keep all devices on
the same build. `floor` is additive: a build without it ignores it, and a report without it is decided
the same. What changed meaning is `level` (the wake word's speech-band level, see
[Level and calibration](#level-and-calibration)): a mixed set of builds compares two different
measures.

## The master

There is **one master for the whole network**; it makes a separate decision per wake word.

**Election**

1. A starting device sends `device_hello` and listens for 2.5 s. An existing master replies at once,
   with a heartbeat to it alone.
2. If it hears no master it waits a random 0–0.5 s, checks again, and if there is still none it sends
   `master_claim` and becomes master.
3. The master heartbeats every 5 s. Anything heard from the master counts as a heartbeat. A device
   that has heard nothing from it for 12.5 s (two heartbeats missed) **asks** (`master_query`); a live
   master answers it directly. Only if no answer comes within 2.5 s does it go to step 2. Asking
   first matters because devices other than the master may be in Wi-Fi power save and can miss a
   broadcast now and then; a unicast answer isn't lost.
4. If two devices both claim, the lowest device id wins and the other stands down. A master that hears
   a claim from a higher id tells the claimer at once (a heartbeat to it), so two masters don't last.
5. A master whose app stops sends `master_resign`, and the others elect at once.
6. A device whose report gets no decision within 1.5 s (see below) assumes the master is gone and asks
   at once.

**What the master alone holds**

- The open decision window for each wake word (the reports gathered so far).
- The last decision for each wake word (kept 2 s), so a late report can be told it lost.
- Which device last answered each wake word (kept 5 minutes). A device that only interrupted its
  response isn't counted as having answered.

**What every device holds** (built from what it hears, so any device can become master)

- *Presence* (`PeerPresence`): which other devices are around and which wake word each listens for. A
  device not heard from for 95 s is dropped. A device that has not said its wake word is assumed to
  compete. This is only used to skip arbitration when nobody else could have heard, and to decide
  early; it never decides who answers. Only the master's list is used (presence goes to the master):
  a new master learns who is around from the `presence` every device sends it in reply to its claim.

## What happens when the wake word is heard

On each device that hears it (`Satellite.onWakeWordDetected` / `handleWakeWordDetection`):

**This device is already responding** (it has a pipeline running that isn't stuck): it's an
*interruption*.

1. The device interrupts at once: it stops its pipeline and doesn't wait for a decision. (Upstream
   then does nothing else. Odysseus fork, barge-in: it then handles the wake word as a new turn,
   arbitrated like any other, which it wins because its own interruption won; see "A device whose own
   report won" below and [turn-card.md](odysseus/turn-card.md#barge-in).)
2. If any other device listens for this wake word, it also reports to the master with
   `responding = true` and doesn't wait for an answer. This is so the decision for this wake word goes
   to this device and the others that heard it stand down.

**Otherwise** this device is a candidate:

- measure its calibrated level (see below);
- play the wake sound and start the audio buffer (`DetectionBuffer`) at once;
- **arbitrate** (`VacaUdpBroadcast.arbitrateWakeWord`).

**Arbitration is skipped, and the device simply answers, when:**

- it is a manual wake (button, or sent from HA);
- it is a follow-up: Odysseus waits for a "yes" and the device that answered listens again by itself,
  with no wake word heard (Odysseus fork, `docs/odysseus/follow-up.md`);
- the device has no wake word;
- no master is known yet;
- **this device is the master and no other device listens for this wake word** (from presence), so
  nobody else could have heard it. A device that isn't master always reports: the master, which has
  the reliable list, then decides at once that nobody else could have heard it (step 3 below), one
  unicast round trip of a few milliseconds.

**Otherwise:**

1. The device takes Wi-Fi out of power save (so the decision reaches it at once), then sends
   `wake_detection` to the master, directly and as a broadcast copy (or adds it itself if it is the
   master).
2. The master opens a **150 ms window** for that wake word on the first report; later reports join it.
3. It decides **early** if a responding device has reported (it wins whatever the others say), or if
   every device that could have heard has reported: all the devices listening for that wake word, plus
   itself if it listens for it. Otherwise it decides when the window ends.
4. It picks one (`WakeWordArbiter`; Odysseus fork: the rules are `odysseus/arbitration/WakeWordPickRules.kt`),
   in this order:
   0. a device that is **responding** (it interrupts); if several are, the rules below choose among them;
   1. the **loudest calibrated level**, however small its lead, except that
   2. the device that **last answered** this wake word (within 5 minutes) wins if its level is within
      **1.5 dB** of the loudest: too close to call, so it keeps answering;
   3. with equal levels, the **highest wake word score**;
   4. then the **lowest device id**.

   If any contender reported no level (an older version), levels are skipped: last answered, score,
   device id. Upstream let level decide only beyond a 3 dB lead; why that changed is under
   [Same-room devices](#same-room-devices).
5. It sends `wake_decision` to each device that reported, and broadcasts a copy. A report arriving
   within 2 s after a decision is told at once that it lost, so a slow device can't also answer.

**Then:**

- **Winner (a candidate)**: sends `duck`, wakes the screen, and starts the audio pipeline. The pipeline
  streams the buffered audio first, then live audio, in order, with nothing missed or repeated at the
  join.
- **Winner (a responding device)**: has already interrupted its response; nothing more happens.
- **Loser**: cancels its buffer and does nothing more (its wake sound has already played). Its
  "Listening…" goes back to idle at once (Odysseus fork, `odysseus/WakeIndicatorPolicy.kt`: whenever
  the wake word ends without a turn, whatever the reason).
- **A device whose own report won**: answers. This happens on a barge-in (Odysseus fork): the device
  reports its interruption (responding, so it wins at once), then handles the wake word as a new turn
  and reports again; that report arrives after the decision, whose winner is its own earlier report
  (`WakeWordPickRules.isOwnReport`: report ids are `<device id>-<n>`). Before, it was told it had lost
  to itself, and the new turn never started.
- **No decision within 1.5 s**: the device answers anyway and asks for the master at once (a new
  election if nobody answers), so a dead master can never silence a device.
- When a pipeline ends its device sends `pipeline_completed`, and the others in its duck group unduck.

## Timeline

```
 t=0     device A hears the wake word, device B (another room) hears it fainter
         A, B: take Wi-Fi out of power save, measure level, play wake sound, start buffering,
               send wake_detection to the master (and a broadcast copy)
 ~t+5ms  master (Wi-Fi awake) receives both; window opens on the first report
 t+150   (or sooner if every device listening for this wake word has reported)
         master: picks A (louder), sends wake_decision to A and B (and a broadcast copy)
 ~t+155  A: sends duck, starts the pipeline, streams buffered audio then live audio to HA
         B: cancels its buffer, does nothing
         A's duck group lowers its music (the duck broadcast arrives at the next beacon)
```

## Wi-Fi power save

(Odysseus fork.) Upstream held Wi-Fi out of power save for the app's whole life. Now a device does so
only while something needs it (`odysseus/power/WifiPerformance.kt`, rules in `WifiLockPolicy.kt`): during
a voice interaction, while it is master with other devices around, while Sendspin plays and while the
camera is watched. The rest of the time the radio power-saves, which changes how fast frames reach it.

**What power save does to delivery.** A device in power save sleeps between beacons (every ~100 ms).
The access point keeps unicast frames for it until it wakes and asks for them: delayed, not lost.
Broadcasts are sent right after a DTIM beacon (every 1–3 beacons, so up to ~300 ms later), and only
then; a device that sleeps through that beacon misses them (with the screen off Android lets the chip
skip beacons). Note that an access point holds every broadcast until the DTIM beacon as soon as any one
device on it is in power save, and phones always are, so broadcasts were already delayed this way
before; only unicast to an awake device arrives at once.

**The timing, case by case** (the decision window is 150 ms):

| Device | Wi-Fi | What it must receive in time | How |
|---|---|---|---|
| The master, whether or not it heard the wake word | out of power save while any other device is around (one device per home) | every report, within the window | reports come to it by unicast, so they arrive in milliseconds |
| A device that heard the wake word | taken out of power save at the wake word, before it reports | the decision | sent to it by unicast; the broadcast copy is a fallback |
| A device that heard nothing | power save | nothing time-critical | it isn't part of the decision; `duck` arrives at the next DTIM beacon, as before |
| A device that is responding | out of power save (a voice interaction) | nothing | it interrupts without waiting |

So reports and decisions now go over unicast between awake devices, which is faster than before:
upstream's broadcasts waited for the DTIM beacon whenever any device on the access point was in power
save, which could push a report past the 150 ms window. The master keeping Wi-Fi awake is what makes
this hold whether or not it heard the wake word; a master alone on the network (no other device heard
from for 95 s) has nobody to decide for and lets the radio power-save.

**A voice interaction** keeps Wi-Fi awake from the wake word (or the start of an announcement,
follow-up or continued conversation) while the device arbitrates or has a pipeline that hasn't ended,
checked every second, plus 5 s; never longer than 10 minutes.

**Lock modes.** Android 8–9: a high-performance lock. Android 10 and up (both Echo Show 5 on
LineageOS 18.1 and Fire HD 10 on Fire OS 8 run Android 11): a high-performance and a low-latency lock;
the platform applies low latency while the screen is on and the app is visible, and high performance
otherwise. Both turn power save off.

## Keep-alive and failover

Only the master's heartbeat is broadcast regularly (every 5 s, was 2 s); presence goes to the master
by unicast every 30 s (was a broadcast from every device every 10 s), and the 1 s election loop became a
timer that sleeps until the next step is due. With four devices that is 0.2 broadcast a second plus
0.1 unicast, against 0.9 broadcasts before; the devices other than the master no longer receive each
other's presence at all.

| | Before | Now |
|---|---|---|
| Master gone without a word (unplugged, Wi-Fi lost) noticed and replaced | ≤ 7.5 s (6 s + 1 s loop + 0.5 s jitter) | ≤ 15.5 s (12.5 s + 2.5 s query + 0.5 s jitter) |
| Master's app stopping | same as above | at once (`master_resign`) + 0.5 s jitter |
| A wake word while the master is dead and not yet replaced | report, no decision after 1.5 s, answer anyway, new election | the same; the election starts at once |
| A device missing a heartbeat or two (power save) | n/a (no power save) | tolerated; after 12.5 s it asks, and the master answers by unicast, so no needless election |

The longer failover only matters for a wake word in the ~15.5 s after a master vanishes: that one is
answered 1.5 s late, possibly by two devices, exactly as within the 7.5 s before, and it starts the
election at once.

## Interruptions

A wake word said while a device is responding is, for that device, an interruption.

- **The responding device heard it:** it interrupts at once and reports `responding = true`. The
  master awards it the decision immediately (it doesn't wait for the window), so the other devices
  that heard the same wake word lose and do nothing. A report from another device that arrives within
  2 s afterwards is told it lost, because that decision is still the latest.
- **The responding device did not hear it:** nothing tells it to stop. Only devices that reported take
  part, so any other device that heard the wake word is decided among the reporters and answers as
  usual. This is deliberate: it lets someone use a device in one room while another room's device is
  still talking.
- If the interrupting device is the only one listening for that wake word, it simply interrupts; there
  is nobody to tell.
- A device that interrupts isn't remembered as the last to have answered.

## Level and calibration

The level compared is the **raw microphone level before any processing** (WebRTC's gain control would
level everything up to speech loudness), plus this device's **calibration offset**. Odysseus fork: it is
the **wake word's** level (`odysseus/arbitration/WakeLevelMeter.kt`, fed every raw frame by
`MicLevelMonitor`):

- after a **300 Hz high-pass** (2nd-order Butterworth), so hum, rumble, music's bass and a
  microphone's DC offset don't count; they depend on where a device stands, not on how near the
  speaker is. The higher frequencies left are also the most directional part of a voice, so the
  device spoken towards reads relatively louder;
- over the last **1.5 s** (the phrase plus the engine's delay in reporting it), measured when the
  handler starts, before this device's wake sound plays;
- as the power average of the **loudest 240 ms** (three 80 ms frames) in that window, so one click,
  tap or plosive doesn't decide.

Upstream used the loudest single 80 ms frame, broadband, over the last 2 s. Each report also carries
the **noise floor** (20th percentile of the frames in the last 5 s, calibrated the same way). It is
only logged.

### Microphone calibration

Microphones differ in sensitivity by more than a change in distance does, so each device has an
offset (`wake_level_offset_db`, SharedPreferences, never sent to HA) set by the **Microphone
calibration** wizard (Settings → Experimental → Voice assistant). The wizard names the wake word in use
("Hey Jarvis") and takes 5 detections in a normal voice at about an arm's length. The **median** is
offset to -30 dB. While it runs, detections only record a reading; the device doesn't wake. If the
readings span more than 6 dB the wizard says so and suggests trying again.

Calibrate every device in one sitting, at the same distance and volume; the result is good to about
±3 dB. The calibration wizard reads the same measure as arbitration, so **recalibrate every device
after updating to this build** (Odysseus fork: the measure changed; an offset from before is roughly
right, but all devices should be measured the same way).

## Same-room devices

(Odysseus fork.) What went wrong, read from the code, when two or three devices in one room hear the
same "Hey Athena", and what changed:

| Failure | Cause | Now |
|---|---|---|
| The device that answered last keeps answering after the speaker walks up to another | under a 3 dB lead, "last answered" decided, and each win renewed it for 5 minutes. Devices a metre or two apart in a room often differ by less than 3 dB (reflections even it out) | the last to answer keeps it only within 1.5 dB of the loudest; a bigger lead is evidence of who is nearer |
| A random device answers when nobody answered lately | under 3 dB the score decided; it saturates near 1.0 for anyone near enough to hear clearly | the louder one, however small the gap; score only for equal levels |
| A device by the fridge, the TV or a speaker playing bass reads louder | broadband level: low-frequency noise counted | 300 Hz high-pass |
| A cheap microphone's DC offset raises its level | the same | the high-pass removes DC |
| A click, tap or plosive decides | the loudest single 80 ms frame | power average of the loudest 240 ms |
| Sound from before the wake word counts | a 2 s window | 1.5 s |
| A barge-in heard by other devices never starts its new turn | its second report was told it lost to its own interruption | a device's own winning report counts as its win |

**Not solved, to watch for in the logs:**

- **Another device's wake sound.** If one device detects the wake word well before another (a
  farther device can cross the threshold a few hundred ms later), the first one's wake sound can
  reach the second's microphone inside its window and raise its level. The second is usually the
  farther one, so this pushes towards a wrong answer. The decision log shows each report's arrival
  time: reports much more than ~150 ms apart are the sign. Measuring at the detection frame itself
  (not when the handler runs) would narrow it further.
- **A device's own playback.** Music or an answer playing on a device is in its raw microphone, so it
  reads louder while it plays. That is right when the speaker is next to it (they want to stop it) and
  wrong when they are next to another device. The noise floor in the log shows it (a high floor).
- **Detection score with level.** Not combined: the score is close to 1.0 for every device near
  enough to matter, so it carries little information about distance.
- **Different microphones** are what calibration is for (above); an uncalibrated device can be off by
  more than any margin.

**Tuning from logs.** The master logs every decision with the rule that decided and every reporter's
inputs ([Debugging](#debugging)). Collect a few days of them (`adb logcat -s VacaUdpBroadcast`) with
notes of where the speaker stood: the leads of wrong decisions tell whether 1.5 dB is right.

## One detection per utterance

The engines report a score every frame, so the frames after the one that crossed the threshold can cross
it too. `DetectionRearmGate` ignores them: after a detection fires, further ones for that wake word are
ignored until the score drops under half the threshold or the engine pauses for 300 ms (a 10 s
failsafe re-opens it). It is a self-contained piece (tagged `REARM-FILTER`) that can be removed on its
own. Ignored frames are logged.

## Timings and limits

| What | Value |
|---|---|
| Decision window | 150 ms (shorter if everyone has reported, or a responding device has) |
| Wait for a decision before answering anyway | 1.5 s |
| Level gap within which the last to answer keeps it (Odysseus fork; upstream: 3 dB, for every rule) | 1.5 dB |
| "Last answered" remembered | 5 minutes |
| Late report treated as having lost | 2 s after a decision |
| Heartbeat / master asked about / no answer, claim | every 5 s / after 12.5 s silence / 2.5 s |
| Election wait at startup / random delay before claiming | 2.5 s / 0–0.5 s |
| Presence to the master / peer forgotten by the master | every 30 s / after 95 s |
| Wi-Fi kept awake after a voice interaction / at most | 5 s / 10 min |
| Wake word re-arms after | score < 50% of threshold, or a 300 ms gap (failsafe 10 s) |
| Level window / loudest part averaged / high-pass (Odysseus fork; upstream: 2 s, one frame, none) | 1.5 s / 240 ms / 300 Hz |
| Calibration readings / reference level | 5 / -30 dB |

## Things to be aware of

- **Only reporters decide.** A device that doesn't hear the wake word is never asked to stop and
  never blocks another. Two devices can therefore both be responding, to different people.
- **Same wake word heard in two rooms at the same moment: one answer.** That is what arbitrating by
  wake word means. Use a different wake word per room if that is a problem.
- **A responding device that can't hear the wake word over its own reply is not interrupted by the
  others.** It stops by voice only if it hears the wake word itself (or the stop word).
- **Keep all devices on the same build.** A device that doesn't send `presence` is forgotten after
  95 s and may answer when it shouldn't; an older build also doesn't understand `master_query`.
- **The master keeps Wi-Fi out of power save** while any other device is around, so it uses a little
  more power than the others.
- **The broadcast must reach every device**: same subnet, no client isolation between them.
- **Every candidate plays the wake sound**, including the ones that lose. The winner's microphone can
  hear the others' sounds; for a *spoken* wake sound, check the transcripts.
- **Calibration depends on how you spoke.** Redo it if devices move or if one consistently wins when it
  shouldn't.

## Debugging

Useful log lines (`adb logcat -s VacaUdpBroadcast Satellite SatelliteWakeWorkHandler`):

- `Wake word detected: ... (mic level -31.4 dB)`: the calibrated level that device measured.
- `VacaUdpBroadcast: decided winner 1a2b3c4d by loudest (+2.4 dB) from 2 report(s): 1a2b3c4d -29.6 dB (floor -61.0), score 0.99, +0 ms | 5e6f7a8b -32.0 dB (floor -58.3), score 0.97, +23 ms`:
  the master's decision (Odysseus fork, `odysseus/arbitration/WakeDecisionLog.kt`): the rule that
  decided (`only one heard it`, `responding: interrupting its response`, `loudest` with its lead,
  `answered last, within 1.5 dB of the loudest` with how far behind it was, `equal level, highest
  score`, `equal level and score, lowest device id`, or the no-level variants) and, per report, the
  device, its calibrated level and noise floor, its score, when the master got it after the first
  report, and `, responding`.
- `no other device listens for <wake word>, answering without arbitration`: the skip path.
- `reported after the decision, telling it it lost: <its inputs>`: a report that missed the window
  (`... it won (its own earlier report did)` after a barge-in).
- `no decision from master ..., starting anyway`: the master didn't answer in 1.5 s.
- `Wake word heard while responding, interrupting`: a device interrupting its own response.
- `nothing from master ... for a while, asking` / `no master heard, claiming` / `master ... has stopped,
  electing`: failover.
- `Wi-Fi: full performance for VOICE, ARBITRATION_MASTER` / `Wi-Fi: power save allowed`
  (`adb logcat | grep Wi-Fi:`): when the lock is taken and released, and why.
- `Ignoring the rest of the ... detection already acted on`: residual frames dropped by the re-arm gate.

Pure logic with unit tests: `WakeWordArbiterTest`, `PeerPresenceTest`, `DetectionRearmGateTest`,
`DetectionBufferTest`, `WakeLevelCalibrationTest`, `MicLevelMonitorTest`, `MasterKeepAlivePolicyTest`,
`WifiLockPolicyTest`, and (Odysseus fork) `WakeWordPickRulesTest`, `WakeLevelMeterTest`,
`WakeDecisionLogTest`, `WakeIndicatorPolicyTest`. The message flow between
devices and the `Satellite` wiring need real devices to test.
