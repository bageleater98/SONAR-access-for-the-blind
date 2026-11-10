"""Phase 1: render the same 3 narrator lines in several candidate voices, for the user to pick one.

Run: tools/voice/.venv/Scripts/python tools/voice/samples.py <out folder>
Writes sample1.wav .. sampleN.wav (the 3 lines, 0.7 s apart, loudness-normalised to -16 LUFS, peak <= -1 dB) and voices.txt.
"""
import os
import sys
import wave

import numpy as np
import pyloudnorm as pyln
import soundfile as sf

HERE = os.path.dirname(os.path.abspath(__file__))
MODELS = os.path.join(HERE, "models")

LINES = [
    "Hello. I'm Wren. I'll be your ears' tour guide. You bring the ears.",
    "Four beats: front, right, left, behind. The quiet one is the door. Doors are shy like that.",
    "Hold on. Hear that buzz? That's an edge. Sneak, and you can walk right up to it without falling.",
]

# (label, engine, voice)
VOICES = [
    ("Kokoro af_heart (US female)", "kokoro", "af_heart"),
    ("Kokoro bm_george (UK male)", "kokoro", "bm_george"),
    ("Kokoro bf_emma (UK female)", "kokoro", "bf_emma"),
    ("Kokoro am_michael (US male)", "kokoro", "am_michael"),
    ("Kokoro af_bella (US female)", "kokoro", "af_bella"),
    ("Piper en_GB-cori-high (UK female)", "piper", "en_GB-cori-high"),
]

TARGET_LUFS = -16.0
PEAK = 10 ** (-1.0 / 20)


def kokoro_render(voice, text):
    from kokoro_onnx import Kokoro
    global _kokoro
    if "_kokoro" not in globals():
        _kokoro = Kokoro(os.path.join(MODELS, "kokoro-v1.0.onnx"), os.path.join(MODELS, "voices-v1.0.bin"))
    samples, sr = _kokoro.create(text, voice=voice, speed=1.0, lang="en-us" if voice.startswith("a") else "en-gb")
    return np.asarray(samples, dtype=np.float32), sr


def piper_render(voice, text):
    from piper import PiperVoice
    v = PiperVoice.load(os.path.join(MODELS, voice + ".onnx"))
    chunks = [np.frombuffer(c.audio_int16_bytes, dtype=np.int16).astype(np.float32) / 32768 for c in v.synthesize(text)]
    return np.concatenate(chunks), v.config.sample_rate


def trim(x, sr, thresh=0.01):
    idx = np.where(np.abs(x) > thresh)[0]
    if len(idx) == 0:
        return x
    pad = int(0.05 * sr)
    return x[max(0, idx[0] - pad): idx[-1] + pad]


def limit(x, sr):
    """Look-ahead peak limiter: gain follows the loudest sample within 5 ms either side, with a 50 ms release, so short peaks are held
    under PEAK without lowering the whole line (plain scaling made some voices 4 dB quieter than others)."""
    w = max(1, int(0.005 * sr))
    env = np.abs(x)
    pad = np.pad(env, (w, w))
    win = np.lib.stride_tricks.sliding_window_view(pad, 2 * w + 1).max(axis=1)
    g = np.minimum(1.0, PEAK / np.maximum(win, 1e-9))
    rel = np.exp(-1.0 / (0.05 * sr))
    for i in range(1, len(g)):  # instant attack (the window already looks ahead), smooth release
        g[i] = min(g[i], g[i - 1] * rel + (1 - rel))
    return x * g


def normalise(x, sr):
    meter = pyln.Meter(sr)
    for _ in range(3):  # gain to the target, limit, measure again
        x = x * 10 ** ((TARGET_LUFS - meter.integrated_loudness(x)) / 20)
        x = limit(x, sr)
    return np.clip(x, -PEAK, PEAK)


def main():
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    notes = ["Sonar voice samples: the same 3 lines in each voice. Pick the one you like best.", ""]
    for n, (label, engine, voice) in enumerate(VOICES, 1):
        parts, sr = [], None
        for line in LINES:
            x, sr = (kokoro_render if engine == "kokoro" else piper_render)(voice, line)
            parts.append(trim(x, sr))
            parts.append(np.zeros(int(0.7 * sr), dtype=np.float32))
        audio = normalise(np.concatenate(parts[:-1]), sr)
        sf.write(os.path.join(out, f"sample{n}.wav"), audio, sr, subtype="PCM_16")
        notes.append(f"sample{n}: {label}")
        print(f"sample{n}: {label}, {len(audio) / sr:.1f} s")
    notes += ["", "Lines:"] + [f"  {i + 1}. {l}" for i, l in enumerate(LINES)]
    notes += ["", "Licences: Kokoro-82M model and voices Apache-2.0 (hexgrad/Kokoro-82M).",
              "Piper cori: trained from LibriVox recordings (public domain dataset), engine MIT/GPL."]
    with open(os.path.join(out, "voices.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(notes) + "\n")


if __name__ == "__main__":
    main()
