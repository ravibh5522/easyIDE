#!/usr/bin/env python3
"""Synthesize the tour film's soundtrack: python3 tools/make-tour-audio.py

An original 120 BPM track in A minor built from plain oscillators and noise (no samples, no
third-party music), cut to the same bar grid as tools/tour/scenes.py. Output:
docs/media/source/score.m4a, which tools/make-tour-video.py muxes in when it exists.
"""
import math
import random
import struct
import subprocess
import sys
import wave
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from tour import scenes as S  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "docs/media/source/score.m4a"
SR = 44100
TAU = 2 * math.pi
BEAT = S.BEAT
rnd = random.Random(11)

PROGRESSION = [  # (bass root Hz, chord midi notes) one bar each: Am, F, C, G
    (55.00, (57, 60, 64)), (43.65, (53, 57, 60)), (65.41, (60, 64, 67)), (49.00, (55, 59, 62)),
]
BREAK = (36.0, 42.0)   # terminal scene: kick drops out, tension builds
OUTRO = 48.0


def hz(midi):
    return 440.0 * 2 ** ((midi - 69) / 12)


def mix(buf, start, samples, gain=1.0):
    i0 = int(start * SR)
    if i0 < 0 or i0 >= len(buf):
        return
    end = min(len(buf), i0 + len(samples))
    for i in range(i0, end):
        buf[i] += samples[i - i0] * gain


def env(n, attack, decay_tau):
    a = max(1, int(attack * SR))
    return [(i / a if i < a else 1.0) * math.exp(-(i - a) / (decay_tau * SR) if i >= a else 0) for i in range(n)]


def kick(strength=1.0):
    n = int(0.42 * SR)
    out, phase = [], 0.0
    for i in range(n):
        t = i / SR
        phase += TAU * (46 + 90 * math.exp(-t * 28)) / SR
        out.append(math.sin(phase) * math.exp(-t * 9) * strength + (rnd.random() - .5) * 0.25 * math.exp(-t * 180))
    return out


def clap():
    n = int(0.22 * SR)
    prev, out = 0.0, []
    for i in range(n):
        t = i / SR
        x = rnd.random() - .5
        out.append((x - prev) * (math.exp(-t * 26) + 0.8 * math.exp(-((t - 0.012) ** 2) * 9000)))
        prev = x * .6
    return out


def hat(open_=False):
    n = int((0.22 if open_ else 0.05) * SR)
    tau = 0.09 if open_ else 0.012
    prev, out = 0.0, []
    for i in range(n):
        x = rnd.random() - .5
        out.append((x - prev) * math.exp(-i / SR / tau) * 0.5)
        prev = x
    return out


def tone(freq, length, shape=(1.0, 0.0, 0.0), attack=0.004, tau=0.2, vibrato=0.0):
    n = int(length * SR)
    out, ph = [], 0.0
    a = max(1, int(attack * SR))
    for i in range(n):
        t = i / SR
        ph += TAU * freq * (1 + vibrato * math.sin(TAU * 5.2 * t)) / SR
        v = shape[0] * math.sin(ph) + shape[1] * math.sin(2 * ph) + shape[2] * math.sin(3 * ph)
        out.append(v * (i / a if i < a else 1.0) * math.exp(-t / tau))
    return out


def pad(notes, length):
    n = int(length * SR)
    out = [0.0] * n
    for nt in notes:
        for det in (-0.004, 0.0, 0.004):
            f, ph = hz(nt) * (1 + det), rnd.random() * TAU
            for i in range(n):
                t = i / SR
                swell = min(1.0, t / 0.9) * min(1.0, (length - t) / 0.9)
                out[i] += math.sin(TAU * f * t + ph) * swell * 0.05
    return out


def noise_sweep(length, rising=True):
    n = int(length * SR)
    out, prev = [], 0.0
    for i in range(n):
        k = i / n
        x = rnd.random() - .5
        amp = (k ** 2.2) if rising else (1 - k) ** 2
        out.append((x - prev * (0.9 - 0.6 * k)) * amp * 0.5)
        prev = x
    return out


def tick(freq):
    n = int(0.018 * SR)
    return [(rnd.random() - .5) * 0.5 * math.exp(-i / SR * 260) + math.sin(TAU * freq * i / SR) * 0.25 * math.exp(-i / SR * 330) for i in range(n)]


def build():
    total = S.TOTAL + 1.0
    n = int(total * SR)
    drums, music, fx = [0.0] * n, [0.0] * n, [0.0] * n
    kicks = []
    bars = int(S.TOTAL / S.BAR)

    # --- cold open: drone, heartbeat, typing ticks, riser, silence, impact -------------
    drone = [math.sin(TAU * 55 * i / SR) * 0.30 + math.sin(TAU * 82.4 * i / SR) * 0.16 for i in range(int(S.COLD_SLAM * SR))]
    for i in range(len(drone)):
        drone[i] *= min(1.0, i / (2.0 * SR)) * (0.8 + 0.2 * math.sin(TAU * 0.25 * i / SR))
    mix(music, 0.0, drone)
    for t in (2.0, 3.0, 4.0, 4.5, 5.0, 5.25, 5.5, 5.75):
        mix(drums, t, kick(0.55), 0.8)
        kicks.append(t)
    for start, text, _ in S.COLD_LINES:
        for i in range(len(text)):
            mix(fx, start + 0.032 * i, tick(1800 + 700 * rnd.random()), 0.22)
    mix(fx, 3.6, noise_sweep(2.35), 0.7)
    boom = [math.sin(TAU * 42 * i / SR * (1 + .5 * math.exp(-i / SR * 6))) * math.exp(-i / SR * 2.2) for i in range(int(3.0 * SR))]
    mix(fx, S.COLD_SLAM, boom, 1.1)
    mix(fx, S.COLD_SLAM, noise_sweep(1.6, rising=False), 0.9)

    # --- the groove, bar by bar --------------------------------------------------------
    for bar in range(int(S.COLD_SLAM / S.BAR), bars + 1):
        t0 = bar * S.BAR
        if t0 >= S.TOTAL:
            break
        root, chord = PROGRESSION[bar % 4]
        in_break = BREAK[0] <= t0 < BREAK[1]
        outro = t0 >= OUTRO
        groove = t0 >= 8.0 and not outro
        if t0 >= S.COLD_SLAM - 0.01:
            mix(music, t0, pad(chord, S.BAR + 0.4), 1.0 if not outro else 1.3)
        if groove:
            for beat in range(4):
                t = t0 + beat * BEAT
                if not in_break:
                    mix(drums, t, kick(0.9 if t0 > 8 else 0.7), 1.0)
                    kicks.append(t)
                    if beat in (1, 3) and t0 >= 12:
                        mix(drums, t, clap(), 0.55)
                if t0 >= 10 and not in_break:
                    mix(drums, t, hat(), 0.5)
                    mix(drums, t + BEAT / 2, hat(open_=(beat % 2 == 1)), 0.45 if beat % 2 else 0.3)
                bass_note = root * (2 if beat in (1, 3) and not in_break else 1)
                mix(music, t, tone(bass_note, BEAT * 0.9, (1.0, 0.35, 0.12), 0.005, 0.28), 0.55)
                mix(music, t + BEAT / 2, tone(root * (1.5 if beat == 3 else 1), BEAT * 0.45, (1.0, 0.4, 0.1), 0.005, 0.2), 0.35)
            if t0 >= 12:
                for step in range(16):
                    note = chord[(0, 1, 2, 1)[step % 4]] + 12
                    mix(music, t0 + step * BEAT / 4, tone(hz(note), 0.35, (1.0, 0.2, 0.05), 0.003, 0.11), 0.17)
            lead_on = (20.0 <= t0 < 36.0) or (42.0 <= t0 < OUTRO)
            if lead_on:
                for k, (beat_at, idx, dur) in enumerate([(0, 2, 1.0), (1.5, 1, 0.5), (2, 0, 1.0), (3, 1, 1.0)]):
                    mix(music, t0 + beat_at * BEAT, tone(hz(chord[idx] + 24), dur * BEAT * 1.1, (1.0, 0.15, 0.0), 0.01, 0.35, 0.004), 0.16)
        if outro:
            for step in range(8):
                note = chord[(0, 1, 2, 1)[step % 4]] + 12
                mix(music, t0 + step * BEAT / 2, tone(hz(note), 0.8, (1.0, 0.2, 0.05), 0.004, 0.3), 0.14)

    # --- whooshes into each cut, riser into the themes drop ----------------------------
    for sc in S.SCENES[1:]:
        mix(fx, sc["t0"] - 0.38, noise_sweep(0.4), 0.55)
    mix(fx, 38.0, noise_sweep(4.0), 0.5)
    for beat in range(8):
        mix(drums, 41.0 + beat * BEAT / 2, clap(), 0.25 + 0.05 * beat)
    mix(fx, 42.0, boom, 0.8)
    mix(fx, OUTRO, boom, 0.9)

    # --- typing ticks for the live-coding scene ---------------------------------------
    t_code = S.SCENES[3]["t0"] + S.SCENES[3]["typing_at"]
    clock = 0.0
    for chars in S.CODE_LINE_CHARS:
        for i in range(chars):
            mix(fx, t_code + clock + i / S.TYPING_CPS, tick(1500 + 900 * rnd.random()), 0.16)
        clock += chars / S.TYPING_CPS + S.TYPING_GAP

    # --- side-chain the music to the kick, add a little echo, sum and master ----------
    duck = [1.0] * n
    for t in kicks:
        i0 = int(t * SR)
        for i in range(i0, min(n, i0 + int(0.22 * SR))):
            duck[i] = min(duck[i], 0.45 + 0.55 * (1 - math.exp(-(i - i0) / SR / 0.07)))
    delay = int(BEAT * 0.75 * SR)
    wet = [music[i] * duck[i] for i in range(n)]
    for i in range(delay, n):
        wet[i] += wet[i - delay] * 0.28
    master = [wet[i] + drums[i] * 0.9 + fx[i] * 0.8 for i in range(n)]
    fade_from = (S.TOTAL - 1.8) * SR
    peak = 0.0
    for i in range(n):
        x = master[i]
        x *= 1.0 if i < fade_from else max(0.0, 1 - (i - fade_from) / (1.9 * SR))
        x = math.tanh(x * 1.1)
        master[i] = x
        peak = max(peak, abs(x))
    return [x / peak * 0.89 for x in master]


def main():
    samples = build()
    wav = Path("/tmp/easyide-score.wav")
    with wave.open(str(wav), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1, min(1, x)) * 32767)) for x in samples))
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", str(wav), "-ac", "2", "-c:a", "aac", "-b:a", "192k", str(OUT)], check=True)
    print(f"{len(samples) / SR:.1f}s -> {OUT}")


if __name__ == "__main__":
    main()
