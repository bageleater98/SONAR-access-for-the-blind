"""Renders Wren's lines (assets/sonar/voice/script.json) into mono Ogg Vorbis files for the mod.

Run from the project folder:  tools/voice/.venv/Scripts/python -I tools/voice/render.py [--force]

- Only lines whose text, voice or speed changed are rendered again (content hash, kept in tools/voice/cache.json).
- Each line: Kokoro -> trim silence -> about -16 LUFS, peak -1 dB -> mono Vorbis -> sounds/voice/<id>.ogg (and <id>.short.ogg for the short variant).
- Files of lines no longer in the script are removed.
- sounds.json: every "voice.*" entry is regenerated (the other entries are left exactly as they are).
- voice/index.json: each line's length in seconds (the narrator times its pauses and the ducking with it).
- Fails (exit 1) when all voice files together are over the 12 MB budget.
"""
import hashlib
import json
import os
import re
import sys

import numpy as np
import soundfile as sf

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import voicelib  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
ASSETS = os.path.join(ROOT, "src", "client", "resources", "assets", "sonar")
SCRIPT = os.path.join(ASSETS, "voice", "script.json")
INDEX = os.path.join(ASSETS, "voice", "index.json")
OUT = os.path.join(ASSETS, "sounds", "voice")
SOUNDS = os.path.join(ASSETS, "sounds.json")
CACHE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "cache.json")
BUDGET = 12 * 1024 * 1024
TOOL_VERSION = 1  # bump to re-render everything after changing the audio steps
QUALITY = 0.9  # libsndfile Vorbis "compression level": 0 = best, 1 = smallest; 0.9 is about 33 kbit/s at 24 kHz mono, the practical floor and clear for speech


def spoken(text, keys):
    """The text as rendered: {key:NAME} becomes the key's default name."""
    return re.sub(r"\{key:(\w+)\}", lambda m: keys[m.group(1)], text)


PHRASES = {"scan": "your ping key", "echo": "your echo key", "ledge": "your ledge key", "confusing": "your marking key"}


def rebound(text):
    """For a player who changed that key: the line says what the key is for, not its letter (a rebound key can not be pre-recorded)."""
    return re.sub(r"\{key:(\w+)\}", lambda m: PHRASES.get(m.group(1), "your " + m.group(1) + " key"), text)


def variants(script):
    """(file id, text) for every line and every short variant."""
    for line in script["lines"]:
        yield line["id"], line["text"]
        if "{key:" in line["text"]:
            yield line["id"] + ".rb", rebound(line["text"])
        if line.get("short"):
            yield line["id"] + ".short", line["short"]
            if "{key:" in line["short"]:
                yield line["id"] + ".short.rb", rebound(line["short"])


def main():
    force = "--force" in sys.argv
    with open(SCRIPT, encoding="utf-8") as f:
        script = json.load(f)
    voice, speed, keys = script["voice"], float(script.get("speed", 1.0)), script["keys"]
    cache = {}
    if os.path.exists(CACHE) and not force:
        with open(CACHE, encoding="utf-8") as f:
            cache = json.load(f)
    index = {}
    if os.path.exists(INDEX):
        with open(INDEX, encoding="utf-8") as f:
            index = json.load(f)
    os.makedirs(OUT, exist_ok=True)
    ids = set()
    rendered = 0
    for vid, text in variants(script):
        if not re.fullmatch(r"[a-z0-9._-]+", vid):
            sys.exit(f"bad line id (lower case, digits, . _ - only): {vid}")
        if vid in ids:
            sys.exit(f"duplicate line id: {vid}")
        ids.add(vid)
        say = spoken(text, keys)
        h = hashlib.sha1(f"{TOOL_VERSION}|{voice}|{speed}|{QUALITY}|{say}".encode()).hexdigest()
        path = os.path.join(OUT, vid + ".ogg")
        if cache.get(vid) == h and os.path.exists(path) and vid in index:
            continue
        x, sr = voicelib.kokoro(voice, say, speed)
        x = voicelib.normalise(voicelib.trim(x, sr), sr)
        sf.write(path, x.astype(np.float32), sr, format="OGG", subtype="VORBIS", compression_level=QUALITY)
        cache[vid] = h
        index[vid] = round(len(x) / sr, 2)
        rendered += 1
        print(f"rendered {vid} ({index[vid]} s)")
    # lines removed from the script: their files and entries go too
    for name in os.listdir(OUT):
        if name.endswith(".ogg") and name[:-4] not in ids:
            os.remove(os.path.join(OUT, name))
            print(f"removed {name}")
    index = {k: index[k] for k in sorted(ids)}
    cache = {k: cache[k] for k in sorted(ids) if k in cache}
    with open(SOUNDS, encoding="utf-8") as f:
        sounds = json.load(f)
    sounds = {k: v for k, v in sounds.items() if not k.startswith("voice.")}
    for vid in sorted(ids):
        sounds["voice." + vid] = {"sounds": [{"name": "sonar:voice/" + vid, "stream": True}]}
    with open(SOUNDS, "w", encoding="utf-8") as f:
        json.dump(sounds, f)
    with open(INDEX, "w", encoding="utf-8") as f:
        json.dump(index, f, indent=1, sort_keys=True)
    with open(CACHE, "w", encoding="utf-8") as f:
        json.dump(cache, f, indent=1, sort_keys=True)
    total = sum(os.path.getsize(os.path.join(OUT, n)) for n in os.listdir(OUT) if n.endswith(".ogg"))
    seconds = sum(index.values())
    print(f"{len(ids)} voice files, {rendered} rendered now, {seconds / 60:.1f} min of speech, {total / 1024 / 1024:.2f} MB of {BUDGET / 1024 / 1024:.0f} MB")
    if total > BUDGET:
        sys.exit("OVER BUDGET: raise QUALITY (smaller files) or shorten the script")


if __name__ == "__main__":
    main()
