"""Build the in-game WMATA traction profile from the tuned reference-matching synthesis.

Writes:
  - five looping samples (44.1 kHz mono Vorbis, like the MP 89 set) into sounds/electric;
  - WmataTractionData.java: per-voice tables over train speed that the client plays from;
  - WmataTractionTest.java: golden values from traction_model.py, pinning the Java to the model;
  - wmata_ingame_tables.json: the same tables, for simulate_ingame.py.

Inputs are wmata-agent-settings.json (the brief) and wmata_tuning2.json (the tuned result).
The reference recording is not needed here, only by the tuning scripts.

Level scale: every tonal sample is baked so that a unit-amplitude tone in the offline mix is
volume 1.0 in game, at the 0.30 amplitude the MP 89 samples use. The bank's scales were set so no
voice asked the engine for more than volume 1.0 and its departure matched MP 89's loudness. Those
scales are still derived from the original timeline curves (legacy_curves) and deliberately left
alone by later changes: reducing a layer lowers the mix rather than being normalised back up.

Noise: the tuned noise matched the whole recording, which was made on a platform, so it carried
station background and came out far louder than the traction tones. Here the background is
subtracted from its envelope and the bed trimmed to a level chosen by ear from in-game renders.

Playback (revision for comfortable repeated play):
  - The upper whine (its line with the baked-in satellites, and the narrowband noise on the same
    track) sits 10 dB under its tuned level. Nothing is renormalised.
  - Tonal layers follow traction demand (TractionDemand): full under power, COAST_GAIN_DB while
    coasting or holding speed, so there is no sustained whine at cruise. The noise bed follows
    speed alone.
  - Departure stages are laid out over speed ranges, not playback time. The upper whine has three
    stages on two voices, crossfaded at equal power where they overlap; the lower tone keeps its
    own straight-line rise with speed.
  - Braking has its own curve on its own voice, crossfaded in by braking demand: a single upper
    tone falling with speed and the lower tone at a steady lower level. It is not the departure
    reversed, and nothing is modulated to fake inverter changes.
"""
import json, os, subprocess, tempfile
import numpy as np
import soundfile as sf
from scipy.interpolate import PchipInterpolator
from scipy.signal import butter, sosfreqz, iirpeak, freqz
import synth_wmata as S
import traction_model as TM

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
SOUNDS = os.path.join(REPO, "common/src/main/resources/assets/pantographsandwires/sounds/electric")
JAVA_DATA = os.path.join(REPO, "common/src/main/java/de/mrjulsen/paw/traction/WmataTractionData.java")
JAVA_TEST = os.path.join(REPO, "common/src/test/java/de/mrjulsen/paw/traction/WmataTractionTest.java")

SR = 44100
AMP = 0.30
REF = {"UPPER": 2440.0, "RIDGE": 850.0, "BRIEF": 2890.0}
rng = np.random.default_rng(6001)

spec = S.load_spec()
tun = json.load(open(os.path.join(HERE, "wmata_tuning2.json")))
st2 = tun["stage2"]
L = {x["name"]: x for x in spec["layers"]}
A, B, C, D = (L[k] for k in ("A_upper_cluster", "B_rising_low_tone", "C_brief_upper_cluster_optional", "D_later_mid_tone_optional"))
assert st2["a_diffuse"] and st2["a_diffuse"]["width_hz"] == 160

# Untonal floor of the platform before the train moves (source 0-4 s and 6-8 s, 80 Hz-6 kHz),
# relative to the floor at the end of the crop, where the tuned noise envelope is 0 dB.
PLATFORM_BACKGROUND_DB = -8.74
# The noise bed relative to its tuned level. The tuned level put it 14 dB over the tones at
# cruise in game; this puts it about 4 dB under, close to the MP 89 balance.
NOISE_TRIM_DB = -18.0

# Revision for comfortable repeated play.
UPPER_WHINE_GAIN_DB = -10.0
COAST_GAIN_DB = -20.0
TABLE_STEPS = 200
# Upper whine stages over speed fraction: crossfades run over [start, end] at equal power.
STAGE_1_TO_2 = (0.07, 0.10)
STAGE_2_TO_3 = (0.575, 0.70)
# Braking: the upper tone's pitch falls from BRAKE_TOP_HZ at top speed to BRAKE_REST_HZ, its level
# from BRAKE_TOP_DB to BRAKE_REST_DB (in the upper cluster's tuned dB frame), and it fades out as
# the train comes to rest; the lower tone holds at BRAKE_RIDGE_DB (in its own tuned frame).
BRAKE_REST_HZ, BRAKE_TOP_HZ = 2400.0, 2580.0
BRAKE_REST_DB, BRAKE_TOP_DB = -9.0, -4.0
BRAKE_FADE = (0.03, 0.10)
BRAKE_RIDGE_DB = -8.0
BRAKE_RIDGE_FADE = (0.23, 0.30)


def without_background(knots, background_db):
    """Remove a constant background power from a dB envelope, keeping its peak at 0 dB."""
    k = np.asarray(knots, dtype=float)
    power = np.maximum(10 ** (k[:, 1] / 10) - 10 ** (background_db / 10), 1e-6)
    db = 10 * np.log10(power)
    db -= db.max()
    return [[float(a), float(b)] for a, b in zip(k[:, 0], db)]


# The low sweep (B) and the later mid ridge (D) are one component. Both lie within about 20 Hz of
# RIDGE_LINE, and along that line the reference holds a tone above chance (checked against parallel
# control lines) from about 2 s to 10.5 s, weaker between the two measured stretches. Played as two
# events that faded to silence, each went "woop" and ended. So they are one rising voice.
RIDGE_LINE = (92.0, 152.6)  # f = a + b * t, Hz, fitted through the B and D knots


def ridge_line(t):
    return RIDGE_LINE[0] + RIDGE_LINE[1] * t


RIDGE_FREQUENCY_KNOTS = ([[2.25, ridge_line(2.25)], [2.5, ridge_line(2.5)]] + B["frequency_knots_s_hz"]
                         + [[t, ridge_line(t)] for t in (5.5, 6.0, 6.5, 7.0, 7.5)] + D["frequency_knots_s_hz"]
                         + [[t, ridge_line(t)] for t in (9.25, 9.75, 10.0)])
assert all(b[0] > a[0] and b[1] > a[1] for a, b in zip(RIDGE_FREQUENCY_KNOTS, RIDGE_FREQUENCY_KNOTS[1:]))
# In B's tuned dB frame (peak +3 dB at 4 s). The B peak and the D peak (D's knots plus its +6 dB tuned
# gain) keep their tuned values; the rest follows the tone power measured along the line.
RIDGE_LEVEL_KNOTS_DB = [[0, -60], [2.0, -60], [2.5, -12], [3.0, -9], [3.5, 0], [4.0, 3], [4.5, 0], [5.0, -2], [5.5, -6],
                        [6.0, -8], [6.5, -8.5], [7.0, -8], [7.5, -7], [8.0, -4], [8.25, -3], [8.5, -3], [8.75, -6],
                        [9.25, -10], [9.75, -11], [10.0, -11]]

KNOTS = {
    "UPPER_FREQUENCY_KNOTS": A["frequency_knots_s_hz"],
    "RIDGE_FREQUENCY_KNOTS": RIDGE_FREQUENCY_KNOTS,
    "BRIEF_FREQUENCY_KNOTS": C["frequency_knots_s_hz"],
    "UPPER_LEVEL_KNOTS_DB": tun["A_upper_cluster_level_knots_s_db"],
    "UPPER_LINE_CUT_KNOTS_DB": st2["a_diffuse"]["line_cut_knots_s_db"],
    "UPPER_DIFFUSE_LEVEL_KNOTS_DB": st2["a_diffuse"]["level_knots_s_db"],
    "RIDGE_LEVEL_KNOTS_DB": RIDGE_LEVEL_KNOTS_DB,
    "BRIEF_LEVEL_KNOTS_DB": C["level_knots_s_db"],
    "NOISE_LEVEL_KNOTS_DB": without_background(tun["noise_env_knots_s_db"], PLATFORM_BACKGROUND_DB),
}
# The single-sine loops are baked this much hotter than AMP and their gains lowered to match, so
# the ridge does not reach volume 1.0 first and hold the whole bank below MP 89's loudness.
SINE_SAMPLE_BOOST_DB = 8.0
GAINS = {
    "RIDGE_GAIN_DB": float(st2["layer_gains_db"]["B_rising_low_tone"]) - SINE_SAMPLE_BOOST_DB,
    "BRIEF_GAIN_DB": float(st2["layer_gains_db"]["C_brief_upper_cluster_optional"]) - SINE_SAMPLE_BOOST_DB,
}


def pchip(knots, t):
    k = np.asarray(knots, dtype=float)
    return PchipInterpolator(k[:, 0], k[:, 1])(np.clip(t, k[0, 0], k[-1, 0]))


def lin(knots, t, gain_db=0.0):
    db = pchip(knots, t)
    out = 10 ** ((db + gain_db) / 20)
    return np.where(db <= -59.999, 0.0, out)


def rms(x):
    return float(np.sqrt(np.mean(np.asarray(x) ** 2)))


def legacy_curves(t, tonal_scale, noise_scale):
    """The original timeline curves (t = 10 s x speed fraction). Only used to derive the level scales."""
    K, G = KNOTS, GAINS
    return {
        "upperLineVolume": tonal_scale * lin(K["UPPER_LEVEL_KNOTS_DB"], t) * lin(K["UPPER_LINE_CUT_KNOTS_DB"], t),
        "upperDiffuseVolume": tonal_scale * lin(K["UPPER_LEVEL_KNOTS_DB"], t) * lin(K["UPPER_DIFFUSE_LEVEL_KNOTS_DB"], t),
        "ridgeVolume": tonal_scale * lin(K["RIDGE_LEVEL_KNOTS_DB"], t, G["RIDGE_GAIN_DB"]),
        "briefVolume": tonal_scale * lin(K["BRIEF_LEVEL_KNOTS_DB"], t, G["BRIEF_GAIN_DB"]),
        "noiseVolume": noise_scale * lin(K["NOISE_LEVEL_KNOTS_DB"], t),
    }


# ---------------------------------------------------------------- samples
def encode(name, x):
    peak = float(np.max(np.abs(x)))
    assert peak < 10 ** (-1 / 20), f"{name}: peak {20*np.log10(peak):.2f} dBFS breaches -1 dBFS"
    path = os.path.join(SOUNDS, f"{name}.ogg")
    with tempfile.TemporaryDirectory() as d:
        wav = os.path.join(d, "x.wav")
        sf.write(wav, x, SR, subtype="PCM_16")
        subprocess.run(["oggenc", "-Q", "-q", "6", "-o", path, wav], check=True)
    y, sr = sf.read(path)
    assert sr == SR
    return y


def sine_loop(freq, seconds=1.0):
    assert float(freq * seconds).is_integer(), "loop must hold whole cycles to be seamless"
    t = np.arange(int(SR * seconds)) / SR
    return AMP * 10 ** (SINE_SAMPLE_BOOST_DB / 20) * np.sin(2 * np.pi * freq * t)


def upper_line_loop():
    """Centre plus the two satellites with their smoothed drift, periodic over 1 s."""
    t = np.arange(SR) / SR
    out = np.sin(2 * np.pi * REF["UPPER"] * t)
    for off, rel in zip(A["satellite_offsets_hz"], A["satellite_levels_db_relative_to_center"]):
        # Drift from 1 Hz and 2 Hz components: smooth like the 150 ms original, and its
        # integral over the loop is zero, so the phase closes and the loop has no seam.
        a = rng.normal(size=2); p = rng.uniform(0, 2 * np.pi, size=2)
        grid = np.linspace(0, 1, 4001)
        d_grid = a[0] * np.sin(2 * np.pi * grid + p[0]) + a[1] * np.sin(4 * np.pi * grid + p[1])
        s = A["satellite_drift_hz"] / np.max(np.abs(d_grid)); a = a * s
        drift_phase = sum(a[k] / (2 * np.pi * (k + 1)) * (np.cos(p[k]) - np.cos(2 * np.pi * (k + 1) * t + p[k])) for k in range(2))
        f0 = REF["UPPER"] + off
        out = out + 10 ** (rel / 20) * np.sin(2 * np.pi * (f0 * t + drift_phase))
    return AMP * out


def periodic_noise(seconds, magnitude_fn):
    n = int(round(SR * seconds))
    f = np.fft.rfftfreq(n, 1 / SR)
    mag = magnitude_fn(f)
    spec_ = mag * np.exp(1j * rng.uniform(0, 2 * np.pi, size=len(f)))
    spec_[0] = 0
    return np.fft.irfft(spec_, n)


def upper_diffuse_loop():
    """Band of noise on the upper centre, shaped like the offline 4th-order 80 Hz complex lowpass."""
    half = st2["a_diffuse"]["width_hz"] / 2
    x = periodic_noise(4.0, lambda f: 1 / np.sqrt(1 + ((f - REF["UPPER"]) / half) ** 8))
    return x / rms(x) * AMP / np.sqrt(2)  # same RMS as a unit tone


def noise_loop():
    """The tuned noise bed as one exactly periodic 9.5 s buffer: body bands plus broadband, then AM."""
    nz = spec["noise"]; seconds = 9.5
    n = int(round(SR * seconds)); f = np.fft.rfftfreq(n, 1 / SR)
    hp = butter(4, nz["highpass_hz"], btype="highpass", fs=SR, output="sos")
    lp = butter(4, nz["lowpass_hz"], btype="lowpass", fs=SR, output="sos")
    _, h_hp = sosfreqz(hp, worN=f, fs=SR); _, h_lp = sosfreqz(lp, worN=f, fs=SR)
    broad = h_hp * h_lp
    body = np.zeros_like(broad)
    for (fc, q), g in zip(nz["body_parallel_bandpass_hz_q"], nz["body_band_relative_gains_db"]):
        b, a_ = iirpeak(fc, q, fs=SR)
        _, h = freqz(b, a_, worN=f, fs=SR)
        band = h * broad
        body += 10 ** (g / 20) * band / np.sqrt(np.sum(np.abs(band) ** 2))
    total = body + broad / np.sqrt(np.sum(np.abs(broad) ** 2)) * np.sqrt(np.sum(np.abs(body) ** 2)) * 10 ** (tun["broadband_db_rel_body"] / 20)
    x = np.fft.irfft(total * np.exp(1j * rng.uniform(0, 2 * np.pi, size=len(f))), n)
    # Irregular 4% amplitude movement, built from whole-period components so it loops too.
    t = np.arange(n) / SR
    am = sum(np.exp(-(k / seconds / 0.8) ** 2) * rng.normal() * np.sin(2 * np.pi * k / seconds * t + rng.uniform(0, 2 * np.pi))
             for k in range(1, 15))
    x = x * (1 + nz["irregular_am_depth_fraction"] * am / np.max(np.abs(am)))
    return x / rms(x) * 10 ** (-16.74 / 20)  # the MP 89 texture's level


samples = {
    "wmata_upper_line": encode("wmata_upper_line", upper_line_loop()),
    "wmata_upper_diffuse": encode("wmata_upper_diffuse", upper_diffuse_loop()),
    "wmata_ridge": encode("wmata_ridge", sine_loop(REF["RIDGE"])),
    "wmata_brief": encode("wmata_brief", sine_loop(REF["BRIEF"])),
    "wmata_noise": encode("wmata_noise", noise_loop()),
}
print("samples (decoded back from Vorbis):")
for name, y in samples.items():
    sp = np.abs(np.fft.rfft(y * np.hanning(len(y)))); fr = np.fft.rfftfreq(len(y), 1 / SR)
    seam = abs(y[0] - y[-1]) / np.percentile(np.abs(np.diff(y)), 99)  # below 1: no click at the loop point
    print(f"  {name:22} {len(y):6d} samples RMS {20*np.log10(rms(y)):6.2f} dBFS peak {20*np.log10(np.max(np.abs(y))):6.2f} dBFS "
          f"strongest {fr[np.argmax(sp)]:7.1f} Hz  seam/p99-step {seam:4.2f}")

# ---------------------------------------------------------------- scales (unchanged by the revision)
# The approved offline mix, the reference the noise level is measured against.
res = S.render(level_overrides={"A_upper_cluster": KNOTS["UPPER_LEVEL_KNOTS_DB"], "B_rising_low_tone": tun["B_rising_low_tone_level_knots_s_db"]},
               noise_env_knots=tun["noise_env_knots_s_db"], noise_db=tun["noise_db_rel_tones"],
               broadband_db=tun["broadband_db_rel_body"], layer_gains_db=st2["layer_gains_db"], a_diffuse=st2["a_diffuse"])
t48 = np.arange(len(res["noise"])) / 48000
noise_k = rms(res["noise"] / res["gain"] / lin(tun["noise_env_knots_s_db"], t48))
# Volume that reproduces the tuned noise level at the envelope's 0 dB, then trimmed.
noise_unit = noise_k * AMP / rms(samples["wmata_noise"]) * 10 ** (NOISE_TRIM_DB / 20)

grid = np.linspace(0, 10, 20001)
raw = legacy_curves(grid, 1.0, noise_unit)
peak = max(float(np.max(v)) for v in raw.values())
scale = 0.999 / peak  # a hair of margin so no stepping between grid points crosses 1.0

voice_rms = {"upperLineVolume": rms(samples["wmata_upper_line"]), "upperDiffuseVolume": rms(samples["wmata_upper_diffuse"]),
             "ridgeVolume": rms(samples["wmata_ridge"]), "briefVolume": rms(samples["wmata_brief"]),
             "noiseVolume": rms(samples["wmata_noise"])}
wmata_power = np.mean(sum((voice_rms[k] * v * scale) ** 2 for k, v in raw.items()))


def window(x, a, b, c, d):
    return np.clip(np.minimum((x - a) / (b - a), (d - x) / (d - c)), 0, 1)


mp = {n: rms(sf.read(os.path.join(SOUNDS, f"mp89_{n}.ogg"))[0]) for n in ("tone_a", "tone_b", "ridge", "texture")}
frac = grid / 10
mp89_power = np.mean((mp["tone_a"] * 1.0 * window(frac, -1, 0.02, 0.15, 0.35)) ** 2
                     + (mp["tone_b"] * 1.0 * window(frac, 0.18, 0.30, 0.45, 0.62)) ** 2
                     + (mp["ridge"] * 0.9 * window(frac, 0.05, 0.30, 1.0, 1.01)) ** 2
                     + (mp["texture"] * 0.5) ** 2)
match = np.sqrt(mp89_power / wmata_power)
if match <= 1:
    scale *= match
TONAL_SCALE, NOISE_SCALE = float(scale), float(noise_unit * scale)
print(f"\nlevel scales (as approved, not renormalised): TONAL {TONAL_SCALE:.6f}  NOISE {NOISE_SCALE:.6f}")

# ---------------------------------------------------------------- playback tables over speed
s = np.linspace(0, 1, TABLE_STEPS + 1)
t = 10 * s  # where each speed fraction sits on the reference departure's timeline
zeros = np.zeros_like(s)
upper_gain = 10 ** (UPPER_WHINE_GAIN_DB / 20)


def fade_in(x, a, b):
    return np.sin(np.pi / 2 * np.clip((x - a) / (b - a), 0, 1))


def fade_out(x, a, b):
    return np.cos(np.pi / 2 * np.clip((x - a) / (b - a), 0, 1))


# Upper whine stages. 1: the starting tone at 2495 Hz. 2: the plateau at 2380 Hz and the rise to
# 2590 Hz. 3: the high section, entering at 2490 Hz, rising to 2575 Hz and settling at 2520 Hz.
# Stages 1 and 3 share voice A, stage 2 has voice B, so overlapping stages never share a voice.
w1 = fade_out(s, *STAGE_1_TO_2)
w2 = fade_in(s, *STAGE_1_TO_2) * fade_out(s, *STAGE_2_TO_3)
w3 = fade_in(s, *STAGE_2_TO_3)
f1 = np.full_like(s, 2495.0)
f2 = pchip([[1, 2380], [3, 2380], [4, 2420], [5, 2575], [5.75, 2590], [7, 2590]], t)
f3 = pchip([[5.75, 2490], [7, 2490], [8, 2575], [10, 2520]], t)
diffuse_weight = w2 ** 2 + w3 ** 2
f_diffuse = np.where(diffuse_weight > 1e-9, (w2 ** 2 * f2 + w3 ** 2 * f3) / np.maximum(diffuse_weight, 1e-9), f2)
upper_level = lin(KNOTS["UPPER_LEVEL_KNOTS_DB"], t)
line_level = upper_level * lin(KNOTS["UPPER_LINE_CUT_KNOTS_DB"], t)

frequency = {
    "STAGE_A": np.where(s < 0.3, f1, f3),
    "STAGE_B": f2,
    "UPPER_DIFFUSE": f_diffuse,
    "BRAKE": BRAKE_REST_HZ + (BRAKE_TOP_HZ - BRAKE_REST_HZ) * s,
    # The lower tone rises on its own straight line with speed, independent of the upper stages.
    "RIDGE": np.clip(ridge_line(t), ridge_line(2.25), ridge_line(10)),
    "BRIEF": pchip(KNOTS["BRIEF_FREQUENCY_KNOTS"], t),
}
powered = {
    "STAGE_A": TONAL_SCALE * upper_gain * line_level * (w1 + w3),
    "STAGE_B": TONAL_SCALE * upper_gain * line_level * w2,
    "UPPER_DIFFUSE": TONAL_SCALE * upper_gain * upper_level * lin(KNOTS["UPPER_DIFFUSE_LEVEL_KNOTS_DB"], t),
    "BRAKE": zeros,
    "RIDGE": TONAL_SCALE * lin(KNOTS["RIDGE_LEVEL_KNOTS_DB"], t, GAINS["RIDGE_GAIN_DB"]),
    "BRIEF": TONAL_SCALE * lin(KNOTS["BRIEF_LEVEL_KNOTS_DB"], t, GAINS["BRIEF_GAIN_DB"]),
}
braking = {
    "STAGE_A": zeros,
    "STAGE_B": zeros,
    "UPPER_DIFFUSE": zeros,
    "BRAKE": TONAL_SCALE * upper_gain * 10 ** ((BRAKE_REST_DB + (BRAKE_TOP_DB - BRAKE_REST_DB) * s) / 20) * fade_in(s, *BRAKE_FADE),
    "RIDGE": TONAL_SCALE * 10 ** ((BRAKE_RIDGE_DB + GAINS["RIDGE_GAIN_DB"]) / 20) * fade_in(s, *BRAKE_RIDGE_FADE),
    "BRIEF": zeros,
}
noise_level = NOISE_SCALE * lin(KNOTS["NOISE_LEVEL_KNOTS_DB"], t)
reference = {"STAGE_A": REF["UPPER"], "STAGE_B": REF["UPPER"], "UPPER_DIFFUSE": REF["UPPER"], "BRAKE": REF["UPPER"],
             "RIDGE": REF["RIDGE"], "BRIEF": REF["BRIEF"]}

for voice in TM.VOICES:
    for label, table in (("frequency", frequency), ("powered", powered), ("braking", braking)):
        assert np.all(np.isfinite(table[voice])), f"{voice} {label} not finite"
    assert powered[voice].max() <= 1 and braking[voice].max() <= 1, f"{voice} asks for more than volume 1.0"
    audible = (powered[voice] > 1e-4) | (braking[voice] > 1e-4)
    ratio = frequency[voice] / reference[voice]
    assert np.all((ratio[audible] >= 0.5) & (ratio[audible] <= 2.0)), f"{voice} pitch leaves the engine's range while audible"

tables = {
    "steps": TABLE_STEPS,
    "voices": TM.VOICES,
    "samples": TM.SAMPLES,
    "reference_hz": [reference[v] for v in TM.VOICES],
    "frequency": [frequency[v].tolist() for v in TM.VOICES],
    "powered_level": [powered[v].tolist() for v in TM.VOICES],
    "braking_level": [braking[v].tolist() for v in TM.VOICES],
    "noise_level": noise_level.tolist(),
    "coast_gain": 10 ** (COAST_GAIN_DB / 20),
}
json.dump(tables, open(os.path.join(HERE, "wmata_ingame_tables.json"), "w"))
print("peak volumes, powered / braking:")
for v in TM.VOICES:
    print(f"  {v:14} {powered[v].max():.4f} / {braking[v].max():.4f}")
print(f"  NOISE          {noise_level.max():.4f}")

# ---------------------------------------------------------------- Java
row = lambda values: "{" + ", ".join(repr(float(x)) for x in values) + "}"
table_block = lambda name, rows: [f"    static final double[][] {name} = {{"] + [f"        {row(r)}," for r in rows] + ["    };"]

lines = [
    "package de.mrjulsen.paw.traction;",
    "",
    "/**",
    " * Generated by tools/sound/wmata/build_wmata_ingame.py. Do not edit by hand; change the model",
    " * there and regenerate. Every table has TABLE_STEPS + 1 entries spaced evenly over speed, from",
    " * standing (0) to the drive's top speed (1). Rows follow WmataTraction.Voice order. Levels already",
    " * include the bank's level scales and the upper whine's reduction.",
    " */",
    "final class WmataTractionData {",
    "    private WmataTractionData() {}",
    "",
    f"    static final int TABLE_STEPS = {TABLE_STEPS};",
    f"    /** Tonal level while coasting or holding speed, relative to full traction demand ({COAST_GAIN_DB:g} dB). */",
    f"    static final double COAST_GAIN = {tables['coast_gain']!r};",
    f"    /** The upper whine's reduction from its tuned level, already applied to the tables. */",
    f"    static final double UPPER_WHINE_GAIN_DB = {UPPER_WHINE_GAIN_DB!r};",
    "",
    f"    static final double[] REFERENCE_HZ = {row(tables['reference_hz'])};",
    "",
]
lines += table_block("FREQUENCY", tables["frequency"]) + [""]
lines += table_block("POWERED_LEVEL", tables["powered_level"]) + [""]
lines += table_block("BRAKING_LEVEL", tables["braking_level"]) + [""]
lines += [f"    static final double[] NOISE_LEVEL = {row(tables['noise_level'])};", "}", ""]
open(JAVA_DATA, "w").write("\n".join(lines))

# Golden values from the Python model
cases = []
for vi, voice in enumerate(TM.VOICES):
    for sv in (-0.1, 0.0, 0.004, 0.05, 0.07, 0.085, 0.1, 0.2, 0.23, 0.26, 0.3, 0.333, 0.45, 0.575, 0.6, 0.64, 0.7, 0.8, 0.95, 1.0, 1.2):
        for p, b in ((0.0, 0.0), (1.0, 0.0), (0.3, 0.6)):
            cases.append((vi, sv, p, b, TM.volume(tables, voice, sv, p, b), TM.frequency(tables, voice, sv)))
speeds, _ = TM.gameplay()
demand = TM.TractionDemand()
power_trace, brake_trace = [], []
for speed in speeds:
    demand.update(speed)
    power_trace.append(demand.power)
    brake_trace.append(demand.brake)

col = lambda i: "{" + ", ".join(repr(float(c[i])) for c in cases) + "}"
test = [
    "package de.mrjulsen.paw.traction;",
    "",
    "import static org.junit.jupiter.api.Assertions.assertEquals;",
    "import static org.junit.jupiter.api.Assertions.assertTrue;",
    "",
    "import org.junit.jupiter.api.Test;",
    "",
    "import de.mrjulsen.paw.traction.WmataTraction.Voice;",
    "",
    "/**",
    " * Generated by tools/sound/wmata/build_wmata_ingame.py. Expected values come from traction_model.py,",
    " * the Python model the renders are made with, so this pins the client to what was auditioned.",
    " */",
    "class WmataTractionTest {",
    "    private static final int[] VOICE = {" + ", ".join(str(c[0]) for c in cases) + "};",
    f"    private static final double[] SPEED = {col(1)};",
    f"    private static final double[] POWER = {col(2)};",
    f"    private static final double[] BRAKE = {col(3)};",
    f"    private static final double[] VOLUME = {col(4)};",
    f"    private static final double[] FREQUENCY = {col(5)};",
    f"    private static final double[] GAMEPLAY_SPEEDS = {row(speeds)};",
    f"    private static final double[] GAMEPLAY_POWER = {row(power_trace)};",
    f"    private static final double[] GAMEPLAY_BRAKE = {row(brake_trace)};",
    "",
    "    @Test",
    "    void volumesAndFrequenciesMatchTheModel() {",
    "        for (int i = 0; i < VOICE.length; i++) {",
    "            Voice voice = Voice.values()[VOICE[i]];",
    "            String at = voice + \" at speed \" + SPEED[i] + \", power \" + POWER[i] + \", brake \" + BRAKE[i];",
    "            assertEquals(VOLUME[i], WmataTraction.volume(voice, SPEED[i], POWER[i], BRAKE[i]), 1e-9, at);",
    "            assertEquals(FREQUENCY[i], WmataTraction.frequency(voice, SPEED[i]), 1e-9, at);",
    "        }",
    "    }",
    "",
    "    @Test",
    "    void demandMatchesTheModelThroughTheGameplayTest() {",
    "        TractionDemand demand = new TractionDemand();",
    "        for (int i = 0; i < GAMEPLAY_SPEEDS.length; i++) {",
    "            demand.update(GAMEPLAY_SPEEDS[i]);",
    "            assertEquals(GAMEPLAY_POWER[i], demand.power(), 1e-12, \"power at tick \" + i);",
    "            assertEquals(GAMEPLAY_BRAKE[i], demand.brake(), 1e-12, \"brake at tick \" + i);",
    "        }",
    "    }",
    "",
    "    @Test",
    "    void volumesStayWithinWhatTheEngineCanPlay() {",
    "        for (Voice voice : Voice.values()) {",
    "            for (int step = 0; step <= 1000; step++) {",
    "                double speed = step / 1000.0;",
    "                for (double power : new double[] {0, 1}) {",
    "                    for (double brake : new double[] {0, 1}) {",
    "                        double volume = WmataTraction.volume(voice, speed, power, brake);",
    "                        assertTrue(volume >= 0 && volume <= 1, voice + \" at speed \" + speed + \" was \" + volume);",
    "                    }",
    "                }",
    "            }",
    "        }",
    "        for (int step = 0; step <= 1000; step++) {",
    "            double noise = WmataTraction.noiseVolume(step / 1000.0);",
    "            assertTrue(noise >= 0 && noise <= 1, \"noise was \" + noise);",
    "        }",
    "    }",
    "",
    "    @Test",
    "    void pitchStaysInsideTheEngineClampWheneverAVoiceIsAudible() {",
    "        for (Voice voice : Voice.values()) {",
    "            for (int step = 0; step <= 1000; step++) {",
    "                double speed = step / 1000.0;",
    "                if (WmataTraction.poweredLevel(voice, speed) > 1e-4 || WmataTraction.brakingLevel(voice, speed) > 1e-4) {",
    "                    double ratio = WmataTraction.frequency(voice, speed) / WmataTraction.referenceFrequency(voice);",
    "                    assertTrue(ratio >= 0.5 && ratio <= 2.0, voice + \" ratio \" + ratio + \" at speed \" + speed);",
    "                }",
    "            }",
    "        }",
    "    }",
    "",
    "    @Test",
    "    void coastingPlaysTheTonesFarUnderFullPower() {",
    "        for (Voice voice : Voice.values()) {",
    "            for (int step = 0; step <= 200; step++) {",
    "                double speed = step / 200.0;",
    "                double full = WmataTraction.volume(voice, speed, 1, 0);",
    "                assertEquals(WmataTractionData.COAST_GAIN * full, WmataTraction.volume(voice, speed, 0, 0), 1e-12, voice + \" at \" + speed);",
    "            }",
    "        }",
    "    }",
    "",
    "    @Test",
    "    void brakingReplacesTheDepartureStagesWithItsOwnCurve() {",
    "        for (Voice stage : new Voice[] {Voice.STAGE_A, Voice.STAGE_B, Voice.UPPER_DIFFUSE, Voice.BRIEF}) {",
    "            assertEquals(0, WmataTraction.volume(stage, 0.8, 1, 1), 1e-12, stage + \" while braking\");",
    "        }",
    "        assertEquals(0, WmataTraction.volume(Voice.BRAKE, 0.8, 1, 0), 1e-12, \"brake voice while powering\");",
    "        assertTrue(WmataTraction.volume(Voice.BRAKE, 0.8, 0, 1) > 0, \"brake voice while braking\");",
    "        assertTrue(WmataTraction.frequency(Voice.BRAKE, 0.2) < WmataTraction.frequency(Voice.BRAKE, 0.8), \"braking pitch falls with speed\");",
    "    }",
    "}",
    "",
]
open(JAVA_TEST, "w").write("\n".join(test))
print(f"\nwrote {os.path.relpath(JAVA_DATA, REPO)} and {os.path.relpath(JAVA_TEST, REPO)} ({len(cases)} golden cases, {len(speeds)} demand ticks)")
