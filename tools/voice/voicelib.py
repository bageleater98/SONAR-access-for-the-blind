"""Shared audio steps for the voice tools: render with Kokoro or Piper, trim, loudness-normalise with a peak limiter."""
import os

import numpy as np
import pyloudnorm as pyln

HERE = os.path.dirname(os.path.abspath(__file__))
MODELS = os.path.join(HERE, "models")
TARGET_LUFS = -16.0
PEAK = 10 ** (-1.0 / 20)

_kokoro = None


def kokoro(voice, text, speed=1.0):
    global _kokoro
    from kokoro_onnx import Kokoro
    if _kokoro is None:
        _kokoro = Kokoro(os.path.join(MODELS, "kokoro-v1.0.onnx"), os.path.join(MODELS, "voices-v1.0.bin"))
    samples, sr = _kokoro.create(text, voice=voice, speed=speed, lang="en-us" if voice.startswith("a") else "en-gb")
    return np.asarray(samples, dtype=np.float32), sr


def piper(voice, text):
    from piper import PiperVoice
    v = PiperVoice.load(os.path.join(MODELS, voice + ".onnx"))
    chunks = [np.frombuffer(c.audio_int16_bytes, dtype=np.int16).astype(np.float32) / 32768 for c in v.synthesize(text)]
    return np.concatenate(chunks), v.config.sample_rate


def trim(x, sr, thresh=0.01, pad_s=0.04):
    """Cuts the silence before and after (keeping a short pad so the first and last sounds are not clipped)."""
    idx = np.where(np.abs(x) > thresh)[0]
    if len(idx) == 0:
        return x
    pad = int(pad_s * sr)
    return x[max(0, idx[0] - pad): idx[-1] + pad]


def limit(x, sr):
    """Look-ahead peak limiter: gain follows the loudest sample within 5 ms either side, with a 50 ms release, so short peaks stay under PEAK
    without lowering the whole line (plain scaling made some voices 4 dB quieter than others)."""
    w = max(1, int(0.005 * sr))
    pad = np.pad(np.abs(x), (w, w))
    win = np.lib.stride_tricks.sliding_window_view(pad, 2 * w + 1).max(axis=1)
    g = np.minimum(1.0, PEAK / np.maximum(win, 1e-9))
    rel = np.exp(-1.0 / (0.05 * sr))
    for i in range(1, len(g)):  # instant attack (the window already looks ahead), smooth release
        g[i] = min(g[i], g[i - 1] * rel + (1 - rel))
    return x * g


def normalise(x, sr):
    """About -16 LUFS integrated, peaks at most -1 dB. Very short clips (under 0.4 s) are too short to gate, so they are matched by RMS instead."""
    meter = pyln.Meter(sr, block_size=min(0.4, len(x) / sr * 0.99))
    for _ in range(3):  # gain to the target, limit, measure again
        loud = meter.integrated_loudness(x)
        if not np.isfinite(loud):
            break
        x = x * 10 ** ((TARGET_LUFS - loud) / 20)
        x = limit(x, sr)
    return np.clip(x, -PEAK, PEAK)
