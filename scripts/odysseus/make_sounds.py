#!/usr/bin/env python3
"""Generate the app's own sounds from sine waves (Odysseus fork).

Nothing is downloaded and the files are reproducible:

    python3 scripts/odysseus/make_sounds.py app/src/main/assets/odysseus           # all of them
    python3 scripts/odysseus/make_sounds.py app/src/main/assets/odysseus thinking.wav

The sounds (see SOUNDS below):

- thinking.wav: played once per voice turn when the answer takes longer than 5 s
  (odysseus/ThinkingChime.kt; docs/odysseus/turn-card.md#thinking-sound).
- listening.wav: played when the device listens again by itself for the answer to a question
  Odysseus asked, without the wake word (odysseus/followup/FollowUp.kt;
  docs/odysseus/follow-up.md). It is not the wake sound, so the owner can tell "it's waiting for
  my yes" from "it heard Hey Athena".
- record_start.wav, record_stop.wav: the recorder's start and stop sounds
  (odysseus/record/RecorderController.kt, not while do-not-disturb is on;
  docs/odysseus/recording.md#sounds).

Standard library only. 22.05 kHz mono 16-bit WAV.
"""

import math
import os
import struct
import sys
import wave

RATE = 22050

# file: parameters
#   notes:   (Hz, start s, length s)
#   peak_db: peak level in dBFS
#   length:  file length in s; None = the last note's end plus 20 ms
#   attack:  attack in s
#   decay:   True = exponential decay to about -40 dB at the note's end (a soft bell);
#            False = flat, with a 40 ms release
#   octave:  level of the quiet octave partial (bell rather than beep)
#   fade:    fade the file's last seconds to zero, so it never clicks
SOUNDS = {
    # Two notes a fifth apart (E5 then B5), overlapping. Soft next to the answer's speech.
    "thinking.wav": dict(notes=[(659.25, 0.00, 0.45), (987.77, 0.18, 0.52)], peak_db=-20,
                         length=0.72, attack=0.015, decay=True, octave=0.18, fade=0.03),
    # Two quick notes going up (G5 then C6): "your turn". Short, so speech right after it is
    # kept; a little softer than the thinking sound.
    "listening.wav": dict(notes=[(783.99, 0.00, 0.20), (1046.50, 0.11, 0.24)], peak_db=-22,
                          length=0.35, attack=0.012, decay=True, octave=0.15, fade=0.02),
    # One short tone (A5).
    "record_start.wav": dict(notes=[(880.00, 0.00, 0.16)], peak_db=-14,
                             length=None, attack=0.008, decay=False, octave=0.12, fade=0),
    # Two tones going down (A5 then D5).
    "record_stop.wav": dict(notes=[(880.00, 0.00, 0.13), (587.33, 0.15, 0.20)], peak_db=-14,
                            length=None, attack=0.008, decay=False, octave=0.12, fade=0),
}


def envelope(t: float, length: float, attack: float, decay: bool) -> float:
    if t < attack:
        return t / attack
    if decay:
        return math.exp(-4.6 * (t - attack) / (length - attack))
    release = 0.04
    if t > length - release:
        return max(0.0, (length - t) / release)
    return 1.0


def render(path: str, notes, peak_db, length, attack, decay, octave, fade) -> None:
    peak = 10 ** (peak_db / 20)
    if length is None:
        length = max(start + note for _, start, note in notes) + 0.02
    n = int(RATE * length)
    samples = [0.0] * n
    for freq, start, note in notes:
        first = int(start * RATE)
        for i in range(int(note * RATE)):
            if first + i >= n:
                break
            t = i / RATE
            tone = math.sin(2 * math.pi * freq * t) + octave * math.sin(4 * math.pi * freq * t)
            samples[first + i] += tone * envelope(t, note, attack, decay)
    top = max(abs(s) for s in samples) or 1.0
    faded = int(fade * RATE)
    for i in range(faded):
        samples[n - 1 - i] *= i / faded
    frames = b"".join(struct.pack("<h", int(round(s / top * peak * 32767))) for s in samples)
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(frames)


def main(folder: str, names) -> None:
    os.makedirs(folder, exist_ok=True)
    for name in names or SOUNDS:
        render(os.path.join(folder, name), **SOUNDS[name])


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "app/src/main/assets/odysseus", sys.argv[2:])
