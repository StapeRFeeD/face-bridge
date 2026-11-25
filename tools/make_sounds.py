"""Synthesizes Face Bridge calibration cues (hold start/end, done) following the PICO sound guidelines:
light & simple, warm, natural; operation sounds 0.5-1 s (mostly soft tail); no hover sounds.
Output: 48 kHz mono 16-bit WAV in app/src/main/res/raw. Run: python3 tools/make_sounds.py"""
import os, wave
import numpy as np

SR = 48000
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "raw")


def tone(freq, dur, decay, amp=1.0, attack=0.002, glide=0.0, partials=((1, 1.0),)):
    t = np.arange(int(SR * dur)) / SR
    f = freq * (1 + glide * np.exp(-t / 0.012))          # tiny downward pitch settle = "natural" pluck
    phase = 2 * np.pi * np.cumsum(f) / SR
    sig = sum(a * np.sin(m * phase) for m, a in partials)
    env = np.minimum(t / attack, 1.0) * np.exp(-t / decay)
    return amp * sig * env


def place(buf, sig, at):
    i = int(SR * at)
    buf[i:i + len(sig)] += sig[: max(0, len(buf) - i)]


def warm(sig, cutoff=5200.0):
    # one-pole low-pass: removes harsh highs ("warm", not mechanical)
    a = np.exp(-2 * np.pi * cutoff / SR)
    out = np.empty_like(sig)
    acc = 0.0
    for i, x in enumerate(sig):
        acc = (1 - a) * x + a * acc
        out[i] = acc
    return out


def room(sig, taps=((0.031, 0.18), (0.053, 0.11), (0.079, 0.06))):
    out = sig.copy()
    for delay, gain in taps:
        d = int(SR * delay)
        out[d:] += gain * sig[:-d]
    return out


def write(name, sig, peak_db):
    sig = room(warm(sig))
    fade = int(SR * 0.04)
    sig[-fade:] *= np.linspace(1, 0, fade)
    sig = sig / (np.max(np.abs(sig)) + 1e-9) * (10 ** (peak_db / 20))
    pcm = (sig * 32767).astype("<i2")
    with wave.open(os.path.join(OUT, name + ".wav"), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR); w.writeframes(pcm.tobytes())
    print(f"{name}.wav {len(sig)/SR:.2f}s peak {peak_db} dBFS")


GLASS = ((1, 1.0), (2.76, 0.12), (5.4, 0.03))   # soft inharmonic shimmer, "glass" material
BODY = ((1, 1.0), (2, 0.18))

def buf(d): return np.zeros(int(SR * d))

# Success: warm major arpeggio (calibration saved, streaming started).
b = buf(1.0)
for i, f in enumerate((659.3, 830.6, 987.8)):
    place(b, tone(f, 0.7, 0.28, 0.9, partials=GLASS), i * 0.085)
write("ui_success", b, -13)

# Calibration hold start: one soft bell. Hold end: short rising pair.
b = buf(0.6); place(b, tone(880, 0.5, 0.20, partials=GLASS), 0)
write("ui_step_start", b, -14)
b = buf(0.6); place(b, tone(988, 0.35, 0.10, partials=GLASS), 0); place(b, tone(1318.5, 0.4, 0.14, partials=GLASS), 0.09)
write("ui_step_end", b, -14)
