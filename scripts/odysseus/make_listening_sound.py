#!/usr/bin/env python3
"""Generate the Odysseus follow-up "listening" sound: a short, soft rising tone (Odysseus fork).

Played when the device listens again by itself for the answer to a question Odysseus asked
("Add Dentist on Tuesday?"), without the wake word (odysseus/followup/FollowUp.kt). It is not the
wake sound, so the owner can tell "it's waiting for my yes" from "it heard Hey Athena".
Made here from sine waves, so nothing is downloaded and the file is reproducible:

    python3 scripts/odysseus/make_listening_sound.py app/src/main/assets/odysseus/listening.wav

Standard library only. 22.05 kHz mono 16-bit WAV, about 0.35 s, peak about -22 dBFS.
"""

import math
import struct
import sys
import wave

RATE = 22050
PEAK = 10 ** (-22 / 20)  # -22 dBFS: a little softer than the thinking sound
# Two quick notes going up (G5 then C6): "your turn". Short, so speech right after it is kept.
NOTES = [(783.99, 0.00, 0.20), (1046.50, 0.11, 0.24)]  # (Hz, start s, length s)
LENGTH = 0.35


def envelope(t: float, length: float) -> float:
    attack = 0.012
    if t < attack:
        return t / attack
    # Exponential decay that reaches ~-40 dB at the end of the note.
    return math.exp(-4.6 * (t - attack) / (length - attack))


def main(path: str) -> None:
    n = int(RATE * LENGTH)
    samples = [0.0] * n
    for freq, start, length in NOTES:
        first = int(start * RATE)
        for i in range(int(length * RATE)):
            if first + i >= n:
                break
            t = i / RATE
            # A quiet octave partial makes it sound like a soft bell rather than a beep.
            tone = math.sin(2 * math.pi * freq * t) + 0.15 * math.sin(4 * math.pi * freq * t)
            samples[first + i] += tone * envelope(t, length)
    top = max(abs(s) for s in samples) or 1.0
    # Fade the last 20 ms to zero so it never clicks.
    fade = int(0.02 * RATE)
    for i in range(fade):
        samples[n - 1 - i] *= i / fade
    frames = b"".join(struct.pack("<h", int(round(s / top * PEAK * 32767))) for s in samples)
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(frames)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "app/src/main/assets/odysseus/listening.wav")
