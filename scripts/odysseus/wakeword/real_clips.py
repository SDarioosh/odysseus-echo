#!/usr/bin/env python3
"""Use the real "Hey Athena" clips the Echos collected (docs/odysseus/wake-word-training.md).

Input: the zip from the hub (Settings → Integrations → Voice assistant → Wake word clips →
Download for training), or the folder it unpacks to. The clips are private recordings of the
household: keep them and everything made from them out of git and off the internet.

  score    How well the current model hears the clips, per threshold, and the clips it misses.
           Needs `pip install pymicro-wakeword` (no TensorFlow).
             python3 real_clips.py score clips.zip
             python3 real_clips.py score clips.zip --model path/to/hey_athena.json

  prepare  Trims each clip to the spoken wake word and writes training and test folders that
           microWakeWord reads like the generated Piper clips (16 kHz mono 16-bit WAV).
             python3 real_clips.py prepare clips.zip --out real_hey_athena
           Then add real_hey_athena/train as another positive set (see the doc).
"""
from __future__ import annotations

import argparse
import csv
import io
import json
import math
import struct
import sys
import tempfile
import wave
import zipfile
from dataclasses import dataclass
from pathlib import Path

SAMPLE_RATE = 16_000
REPO = Path(__file__).resolve().parents[3]
DEFAULT_MODEL = REPO / "app/src/main/assets/microwakeword/wakeWords/hey_athena.json"
THRESHOLDS = [0.5, 0.6, 0.7, 0.78, 0.85, 0.9, 0.92, 0.94]


@dataclass
class Clip:
    id: str
    kind: str
    path: Path
    meta: dict


def read_wav(path: Path) -> list[int]:
    with wave.open(str(path), "rb") as w:
        if w.getnchannels() != 1 or w.getsampwidth() != 2 or w.getframerate() != SAMPLE_RATE:
            raise ValueError(f"{path.name}: not 16 kHz mono 16-bit")
        data = w.readframes(w.getnframes())
    return list(struct.unpack(f"<{len(data) // 2}h", data))


def write_wav(path: Path, samples: list[int]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SAMPLE_RATE)
        w.writeframes(struct.pack(f"<{len(samples)}h", *samples))


def load_clips(source: Path, workdir: Path) -> list[Clip]:
    """The clips of a hub export (zip or folder): <wake_word>/<kind>/<id>.wav (+ clips.jsonl)."""
    root = source
    if source.is_file():
        with zipfile.ZipFile(source) as z:
            for name in z.namelist():
                if ".." in Path(name).parts or name.startswith("/"):
                    raise ValueError(f"unsafe path in zip: {name}")
            z.extractall(workdir)
        root = workdir
    meta: dict[str, dict] = {}
    for jl in root.rglob("clips.jsonl"):
        for line in jl.read_text(encoding="utf-8").splitlines():
            if line.strip():
                row = json.loads(line)
                meta[str(row.get("id") or row.get("clip_id"))] = row
    clips = []
    for wav in sorted(root.rglob("*.wav")):
        m = meta.get(wav.stem, {})
        clips.append(Clip(wav.stem, m.get("kind") or wav.parent.name, wav, m))
    clips.sort(key=lambda c: (c.meta.get("recorded_at") or 0, c.id))
    return clips


# ---- score -------------------------------------------------------------------------------------

def peak_score(model_json: Path, samples: list[int]) -> float:
    """The highest running-mean score the device would compare with its threshold."""
    from pymicro_wakeword import MicroWakeWord, MicroWakeWordFeatures

    model = MicroWakeWord.from_config(model_json)
    features = MicroWakeWordFeatures()
    pad_before, pad_after = [0] * SAMPLE_RATE, [0] * (SAMPLE_RATE // 2)
    audio = struct.pack(f"<{len(samples) + len(pad_before) + len(pad_after)}h", *pad_before, *samples, *pad_after)
    best = 0.0
    for i in range(0, len(audio), 2560):  # 80 ms, as the Echo feeds it
        for f in features.process_streaming(audio[i:i + 2560]):
            p = model.process_streaming_prob(f)
            if p is not None and p > best:
                best = p
    model.close()
    return best


def cmd_score(args: argparse.Namespace) -> int:
    with tempfile.TemporaryDirectory() as tmp:
        clips = [c for c in load_clips(Path(args.clips), Path(tmp)) if c.kind in ("guided", "missed", "heard")]
        if not clips:
            print("No clips found.")
            return 1
        scores: dict[str, list[tuple[float, Clip]]] = {}
        for c in clips:
            try:
                s = peak_score(Path(args.model), read_wav(c.path))
            except ValueError as e:
                print(f"skipped: {e}")
                continue
            scores.setdefault(c.kind, []).append((s, c))
    print(f"Model: {args.model}")
    header = "threshold " + " ".join(f"{k:>14}" for k in sorted(scores))
    print("Share of clips that would wake the device:")
    print(header)
    for t in THRESHOLDS:
        cells = []
        for k in sorted(scores):
            hit = sum(1 for s, _ in scores[k] if s > t)
            cells.append(f"{hit:>4}/{len(scores[k]):<4} {100 * hit / len(scores[k]):3.0f}%")
        print(f"{t:>9.2f} " + " ".join(f"{c:>14}" for c in cells))
    cutoff = args.threshold
    missed = sorted(((s, c) for k in scores for s, c in scores[k] if s <= cutoff), key=lambda x: x[0])
    if missed:
        print(f"\nMissed at {cutoff:.2f} (lowest first; listen to them in the hub):")
        for s, c in missed[: args.show]:
            print(f"  {s:.2f}  {c.kind:<7} {c.meta.get('prompt') or '':<28} {c.meta.get('device_name') or ''}  {c.id}")
    print("\nFalse wakes per hour can't be measured from these clips: lower the threshold only as far "
          "as the table in docs/odysseus/echo-show-5.md allows, or retrain.")
    return 0


# ---- prepare -----------------------------------------------------------------------------------

def trim_to_speech(samples: list[int], before_ms: int = 200, after_ms: int = 150, max_ms: int = 2000) -> list[int] | None:
    """The last spoken stretch of the clip (the wake word ends it), or None when it is silent."""
    hop = SAMPLE_RATE // 100  # 10 ms
    db = []
    for i in range(0, len(samples) - hop + 1, hop):
        frame = samples[i:i + hop]
        rms = math.sqrt(sum(s * s for s in frame) / hop) or 1e-9
        db.append(20 * math.log10(rms / 32768))
    if not db:
        return None
    floor = sorted(db)[len(db) // 10]
    top = max(db)
    if top < -50 or top - floor < 12:
        return None
    level = max(floor + 10, top - 35)
    speech = [d >= level for d in db]
    end = max(i for i, s in enumerate(speech) if s)
    start = end
    gap = 0
    while start > 0 and (end - start) * 10 < max_ms:
        if speech[start - 1]:
            gap = 0
        else:
            gap += 1
            if gap * 10 > 300:  # a pause before the wake word: stop
                break
        start -= 1
    start += gap
    a = max(0, start * hop - before_ms * SAMPLE_RATE // 1000)
    b = min(len(samples), (end + 1) * hop + after_ms * SAMPLE_RATE // 1000)
    return samples[a:b]


def cmd_prepare(args: argparse.Namespace) -> int:
    out = Path(args.out)
    if (REPO / ".git").exists() and out.resolve().is_relative_to(REPO):
        print("Refusing to write private recordings inside the repository; choose a folder outside it.")
        return 2
    with tempfile.TemporaryDirectory() as tmp:
        clips = [c for c in load_clips(Path(args.clips), Path(tmp)) if c.kind in args.kinds.split(",")]
        rows, n = [], {"train": 0, "test": 0, "skipped": 0}
        for i, c in enumerate(clips):
            try:
                trimmed = trim_to_speech(read_wav(c.path))
            except ValueError:
                trimmed = None
            if not trimmed:
                n["skipped"] += 1
                continue
            split = "test" if args.test_every and i % args.test_every == args.test_every - 1 else "train"
            write_wav(out / split / f"{c.kind}_{c.id}.wav", trimmed)
            n[split] += 1
            rows.append([split, c.kind, c.id, f"{len(trimmed) / SAMPLE_RATE:.2f}",
                         c.meta.get("score", ""), c.meta.get("prompt", ""), c.meta.get("device_name", "")])
    with open(out / "manifest.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["split", "kind", "id", "seconds", "device_score", "prompt", "device"])
        w.writerows(rows)
    print(f"{n['train']} training and {n['test']} test clips in {out} ({n['skipped']} silent or unreadable skipped).")
    return 0


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("score", help="how well the current model hears the clips")
    s.add_argument("clips", help="the hub's zip, or the folder it unpacks to")
    s.add_argument("--model", default=str(DEFAULT_MODEL), help="the model's JSON (default: the bundled Hey Athena)")
    s.add_argument("--threshold", type=float, default=0.92, help="the devices' threshold (default 0.92)")
    s.add_argument("--show", type=int, default=30, help="how many missed clips to list")
    s.set_defaults(func=cmd_score)
    r = sub.add_parser("prepare", help="trim the clips into training and test folders")
    r.add_argument("clips")
    r.add_argument("--out", required=True, help="output folder, outside the repository")
    r.add_argument("--kinds", default="guided,missed,heard", help="which kinds to use (comma-separated)")
    r.add_argument("--test-every", type=int, default=5, help="every Nth clip goes to test (0: none)")
    r.set_defaults(func=cmd_prepare)
    args = p.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
