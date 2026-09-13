"""Render what the client plays, so a change can be judged by ear before building a jar.

Uses the shipped .ogg loops, the tables build_wmata_ingame.py wrote (wmata_ingame_tables.json),
TractionSoundManager's speed, motion and load mapping, and TractionHumSoundInstance's per-tick
pitch, load and fade smoothing, over a 10 s departure at constant acceleration then cruise.
Each render is normalised to the same RMS so only the balance differs between them.

CLI: simulate_ingame.py OUT_DIR [--noise-db X ...]   extra renders with the noise voice offset by X dB
"""
import argparse, json, os
import numpy as np
import soundfile as sf
from scipy.interpolate import PchipInterpolator

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
SOUNDS = os.path.join(REPO, "common/src/main/resources/assets/pantographsandwires/sounds/electric")
SR, TPS = 44100, 20
TICK = SR // TPS
RAMP_TICKS, CRUISE_TICKS, STOP_TICKS = 200, 80, 12

tab = json.load(open(os.path.join(HERE, "wmata_ingame_tables.json")))
K, G, REF = tab["knots"], tab["gains"], tab["reference_hz"]
TONAL, NOISE = tab["tonal_voice_scale"], tab["noise_voice_scale"]


def pchip(knots, t):
    k = np.asarray(knots, dtype=float)
    return float(PchipInterpolator(k[:, 0], k[:, 1])(np.clip(t, k[0, 0], k[-1, 0])))


def lin(knots, t, gain_db=0.0):
    db = pchip(knots, t)
    return 0.0 if db <= -59.999 else 10 ** ((db + gain_db) / 20)


def window(x, a, b, c, d):
    if x <= a or x >= d:
        return 0.0
    if x < b:
        return (x - a) / (b - a)
    return 1.0 if x <= c else (d - x) / (d - c)


def wmata(fraction, noise_db):
    """name -> (sample, reference Hz or None, frequency, volume, follows load)"""
    t = fraction * 10
    upper = pchip(K["UPPER_FREQUENCY_KNOTS"], t)
    level = lin(K["UPPER_LEVEL_KNOTS_DB"], t)
    return {
        "upper_line": ("wmata_upper_line", REF["UPPER"], upper, TONAL * level * lin(K["UPPER_LINE_CUT_KNOTS_DB"], t), True),
        "upper_diffuse": ("wmata_upper_diffuse", REF["UPPER"], upper, TONAL * level * lin(K["UPPER_DIFFUSE_LEVEL_KNOTS_DB"], t), True),
        "ridge": ("wmata_ridge", REF["RIDGE"], pchip(K["RIDGE_FREQUENCY_KNOTS"], t), TONAL * lin(K["RIDGE_LEVEL_KNOTS_DB"], t, G["RIDGE_GAIN_DB"]), True),
        "brief": ("wmata_brief", REF["BRIEF"], pchip(K["BRIEF_FREQUENCY_KNOTS"], t), TONAL * lin(K["BRIEF_LEVEL_KNOTS_DB"], t, G["BRIEF_GAIN_DB"]), True),
        "noise": ("wmata_noise", None, None, NOISE * lin(K["NOISE_LEVEL_KNOTS_DB"], t) * 10 ** (noise_db / 20), False),
    }


def mp89(fraction, noise_db):
    return {
        "tone_a": ("mp89_tone_a", 686, 686, window(fraction, -1, .02, .15, .35), True),
        "tone_b": ("mp89_tone_b", 1186, 1186, window(fraction, .18, .30, .45, .62), True),
        "ridge": ("mp89_ridge", 256, 191 + 132 * fraction, 0.9 * window(fraction, .05, .30, 1.0, 1.01), True),
        "noise": ("mp89_texture", None, None, 0.5 * 10 ** (noise_db / 20), False),
    }


def render(bank, noise_db=0.0):
    speeds = [0.7 * k / RAMP_TICKS for k in range(1, RAMP_TICKS + 1)] + [0.7] * CRUISE_TICKS
    loops, state, series = {}, {}, {}
    previous, voices = None, None
    for i in range(len(speeds) + STOP_TICKS):
        stopping = i >= len(speeds)
        if not stopping:
            speed = speeds[i]
            acceleration = 0.0 if previous is None else speed - previous
            previous = speed
            motion = min(1.0, speed / 0.08)
            tonal = motion * (0.75 + 0.25 * min(1.0, max(0.0, acceleration / 0.004)))
            voices = bank(min(1.0, speed / 0.7), noise_db)
        for name, (sample, ref, freq, volume, loaded) in voices.items():
            s = state.setdefault(name, {"pitch": None, "load": None, "fade": 0.0, "target_pitch": 1.0, "target_load": 1.0})
            if name not in loops:
                loops[name] = sf.read(os.path.join(SOUNDS, sample + ".ogg"))[0]
            if not stopping:
                s["target_pitch"] = 1.0 if ref is None else min(2.0, max(0.5, freq / ref))
                if s["pitch"] is None:
                    s["pitch"] = s["target_pitch"]
                s["target_load"] = (tonal if loaded else motion) * volume
                if s["load"] is None:
                    s["load"] = s["target_load"]  # first setLoadScale snaps, as in TractionHumSoundInstance
            s["pitch"] += (s["target_pitch"] - s["pitch"]) * 0.22
            s["load"] += (s["target_load"] - s["load"]) * (0.25 if s["target_load"] < s["load"] else 0.05)
            s["fade"] = max(0.0, s["fade"] - 0.1) if stopping else min(1.0, s["fade"] + 0.1)
            series.setdefault(name, []).append((s["pitch"], 0.5 * s["fade"] * s["load"]))

    n = (len(speeds) + STOP_TICKS) * TICK
    ticks = np.arange(n) / TICK
    parts = {}
    for name, values in series.items():
        v = np.array(values)
        pitch = np.interp(ticks, np.arange(len(v)), v[:, 0])
        volume = np.interp(ticks, np.arange(len(v)), v[:, 1])
        x = loops[name]
        pos = np.cumsum(pitch) % len(x)
        i0 = np.floor(pos).astype(int)
        frac = pos - i0
        parts[name] = (x[i0] * (1 - frac) + x[(i0 + 1) % len(x)] * frac) * volume
    return sum(parts.values()), parts


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out_dir")
    ap.add_argument("--noise-db", type=float, action="append", default=[])
    args = ap.parse_args()
    os.makedirs(args.out_dir, exist_ok=True)
    runs = [("wmata", wmata, 0.0), ("mp89", mp89, 0.0)] + [(f"wmata_noise{d:+g}dB", wmata, d) for d in args.noise_db]
    cruise = slice((RAMP_TICKS + 10) * TICK, (RAMP_TICKS + CRUISE_TICKS) * TICK)
    ramp_mid = slice(100 * TICK, 110 * TICK)
    for tag, bank, noise_db in runs:
        mix, parts = render(bank, noise_db)
        rest = mix - parts["noise"]
        balance = lambda sl: 10 * np.log10(np.mean(parts["noise"][sl] ** 2) / np.mean(rest[sl] ** 2))
        raw_rms = np.sqrt(np.mean(mix[SR // 2:-SR] ** 2))
        out = mix * 10 ** (-18 / 20) / raw_rms
        sf.write(os.path.join(args.out_dir, tag + ".wav"), out.astype(np.float32), SR, subtype="PCM_24")
        print(f"{tag:22} noise vs the rest: mid-departure {balance(ramp_mid):+5.1f} dB, cruise {balance(cruise):+5.1f} dB | "
              f"in-game RMS at volume 0.5 {20*np.log10(raw_rms):6.2f} dBFS | normalised peak {20*np.log10(np.max(np.abs(out))):6.2f} dBFS")


if __name__ == "__main__":
    main()
