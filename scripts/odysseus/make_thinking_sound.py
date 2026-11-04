#!/usr/bin/env python3
"""Generate the Odysseus "thinking" sound: one short, soft two-note tone (Odysseus fork).

Played once per voice turn when the answer takes longer than 5 s (odysseus/ThinkingChime.kt).
Made here from sine waves, so nothing is downloaded and the file is reproducible:

    python3 scripts/odysseus/make_thinking_sound.py app/src/main/assets/odysseus/thinking.wav

Standard library only. 22.05 kHz mono 16-bit WAV, about 0.7 s, peak about -20 dBFS.
"""

import math
import struct
import sys
import wave

RATE = 22050
PEAK = 10 ** (-20 / 20)  # -20 dBFS: soft next to the answer's speech
# Two notes a fifth apart (E5 then B5), each with a soft attack and a long decay, overlapping.
NOTES = [(659.25, 0.00, 0.45), (987.77, 0.18, 0.52)]  # (Hz, start s, length s)
LENGTH = 0.72


def envelope(t: float, length: float) -> float:
    attack = 0.015
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
            tone = math.sin(2 * math.pi * freq * t) + 0.18 * math.sin(4 * math.pi * freq * t)
            samples[first + i] += tone * envelope(t, length)
    top = max(abs(s) for s in samples) or 1.0
    # Fade the last 30 ms to zero so it never clicks.
    fade = int(0.03 * RATE)
    for i in range(fade):
        samples[n - 1 - i] *= i / fade
    frames = b"".join(struct.pack("<h", int(round(s / top * PEAK * 32767))) for s in samples)
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(frames)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "app/src/main/assets/odysseus/thinking.wav")
