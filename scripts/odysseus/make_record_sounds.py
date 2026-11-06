#!/usr/bin/env python3
"""Generate the recorder's start and stop sounds (Odysseus fork, docs/odysseus/recording.md).

Start: one short tone. Stop: two tones going down. Played by odysseus/record/RecorderController.kt
(not while do-not-disturb is on). Made here from sine waves, so nothing is downloaded and the
files are reproducible:

    python3 scripts/odysseus/make_record_sounds.py app/src/main/assets/odysseus

Standard library only. 22.05 kHz mono 16-bit WAV, peak about -14 dBFS.
"""

import math
import os
import struct
import sys
import wave

RATE = 22050
PEAK = 10 ** (-14 / 20)

# (Hz, start s, length s)
START = [(880.00, 0.00, 0.16)]                      # A5
STOP = [(880.00, 0.00, 0.13), (587.33, 0.15, 0.20)]  # A5 then D5


def envelope(t: float, length: float) -> float:
    attack = 0.008
    release = 0.04
    if t < attack:
        return t / attack
    if t > length - release:
        return max(0.0, (length - t) / release)
    return 1.0


def render(notes, path: str) -> None:
    total = max(start + length for _, start, length in notes) + 0.02
    n = int(RATE * total)
    samples = [0.0] * n
    for freq, start, length in notes:
        first = int(start * RATE)
        for i in range(int(length * RATE)):
            if first + i >= n:
                break
            t = i / RATE
            tone = math.sin(2 * math.pi * freq * t) + 0.12 * math.sin(4 * math.pi * freq * t)
            samples[first + i] += tone * envelope(t, length)
    top = max(abs(s) for s in samples) or 1.0
    frames = b"".join(struct.pack("<h", int(round(s / top * PEAK * 32767))) for s in samples)
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(frames)


def main(folder: str) -> None:
    os.makedirs(folder, exist_ok=True)
    render(START, os.path.join(folder, "record_start.wav"))
    render(STOP, os.path.join(folder, "record_stop.wav"))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "app/src/main/assets/odysseus")
