"""Build the in-game WMATA traction profile from the tuned reference-matching synthesis.

Writes:
  - six looping samples (44.1 kHz mono Vorbis, like the MP 89 set) into sounds/electric;
  - WmataTractionData.java: the knot tables and voice scales the client plays from;
  - WmataTractionTest.java: golden values from SciPy's PchipInterpolator, pinning the Java port.

Inputs are wmata-agent-settings.json (the brief) and wmata_tuning2.json (the tuned result).
The reference recording is not needed here, only by the tuning scripts.

Level scale: every tonal sample is baked so that a unit-amplitude tone in the offline mix is
volume 1.0 in game, at the 0.30 amplitude the MP 89 samples use. The whole bank is then scaled
so no voice ever asks the engine for more than volume 1.0, and so its mean mix power over a
standstill-to-cruise ramp matches the MP 89 profile, keeping the two variants equally loud.

Noise: the tuned noise matched the whole recording, which was made on a platform, so it carried
station background and came out far louder than the traction tones. Here the background is
subtracted from its envelope and the bed trimmed to a level chosen by ear from in-game renders
(simulate_ingame.py). Also writes wmata_ingame_tables.json for that simulator.
"""
import json, os, subprocess, tempfile
import numpy as np
import soundfile as sf
from scipy.interpolate import PchipInterpolator
from scipy.signal import butter, sosfreqz, iirpeak, freqz
import synth_wmata as S

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
# events that faded to silence, each went "woop" and ended. So they are one rising voice: B's and
# D's knots where they were measured, the line between and after, holding at cruise (chosen by ear
# over fading out).
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


def curves(t, tonal_scale, noise_scale):
    K, G = KNOTS, GAINS
    return {
        "upperFrequency": pchip(K["UPPER_FREQUENCY_KNOTS"], t),
        "ridgeFrequency": pchip(K["RIDGE_FREQUENCY_KNOTS"], t),
        "briefFrequency": pchip(K["BRIEF_FREQUENCY_KNOTS"], t),
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

# ---------------------------------------------------------------- scales
# The approved offline mix, the reference the noise level is measured against.
res = S.render(level_overrides={"A_upper_cluster": KNOTS["UPPER_LEVEL_KNOTS_DB"], "B_rising_low_tone": tun["B_rising_low_tone_level_knots_s_db"]},
               noise_env_knots=tun["noise_env_knots_s_db"], noise_db=tun["noise_db_rel_tones"],
               broadband_db=tun["broadband_db_rel_body"], layer_gains_db=st2["layer_gains_db"], a_diffuse=st2["a_diffuse"])
t48 = np.arange(len(res["noise"])) / 48000
noise_k = rms(res["noise"] / res["gain"] / lin(tun["noise_env_knots_s_db"], t48))
# Volume that reproduces the tuned noise level at the envelope's 0 dB, then trimmed.
noise_unit = noise_k * AMP / rms(samples["wmata_noise"]) * 10 ** (NOISE_TRIM_DB / 20)

grid = np.linspace(0, 10, 20001)
raw = curves(grid, 1.0, noise_unit)
vols = {k: v for k, v in raw.items() if k.endswith("Volume")}
peak = max(float(np.max(v)) for v in vols.values())
scale = 0.999 / peak  # a hair of margin so no stepping between grid points crosses 1.0

voice_rms = {"upperLineVolume": rms(samples["wmata_upper_line"]), "upperDiffuseVolume": rms(samples["wmata_upper_diffuse"]),
             "ridgeVolume": rms(samples["wmata_ridge"]), "briefVolume": rms(samples["wmata_brief"]),
             "noiseVolume": rms(samples["wmata_noise"])}
wmata_power = np.mean(sum((voice_rms[k] * v * scale) ** 2 for k, v in vols.items()))


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
    parity = "matched"
else:
    parity = f"limited: WMATA sits {20*np.log10(match):.2f} dB below MP 89, since raising it would exceed volume 1.0"
TONAL_SCALE, NOISE_SCALE = float(scale), float(noise_unit * scale)
final = curves(grid, TONAL_SCALE, NOISE_SCALE)
top = max(float(np.max(v)) for k, v in final.items() if k.endswith("Volume"))
print(f"\nnoise unit volume {noise_unit:.4f}; peak-normalised scale {0.999/peak:.4f}; MP 89 parity {parity}")
print(f"TONAL_VOICE_SCALE {TONAL_SCALE:.6f}  NOISE_VOICE_SCALE {NOISE_SCALE:.6f}  loudest voice volume {top:.4f}")
for k, v in final.items():
    if k.endswith("Volume"):
        print(f"  {k:20} max {np.max(v):.4f} at t={grid[np.argmax(v)]:.2f}s")

# ---------------------------------------------------------------- Java
def jarr(knots):
    return "{" + ", ".join("{" + f"{float(a)!r}, {float(b)!r}" + "}" for a, b in knots) + "}"


lines = [
    "package de.mrjulsen.paw.traction;",
    "",
    "/**",
    " * Generated by tools/sound/wmata/build_wmata_ingame.py. Do not edit by hand; change the",
    " * tuning there and regenerate. Frequencies are the brief's measured features; level knots,",
    " * gains and scales were tuned offline against the reference recording, except the noise bed,",
    " * which has the platform background removed and a level chosen by ear.",
    " */",
    "public final class WmataTractionData {",
    "    private WmataTractionData() {}",
    "",
    "    /** Length of the reference departure the curves describe, in seconds. */",
    "    public static final double TIMELINE_SECONDS = 10.0;",
    "",
]
for k, v in REF.items():
    lines.append(f"    public static final double {k}_REFERENCE_HZ = {v!r};")
lines.append("")
for k, v in KNOTS.items():
    lines.append(f"    static final double[][] {k} = {jarr(v)};")
lines.append("")
for k, v in GAINS.items():
    lines.append(f"    static final double {k} = {v!r};")
lines += ["", f"    static final double TONAL_VOICE_SCALE = {TONAL_SCALE!r};", f"    static final double NOISE_VOICE_SCALE = {NOISE_SCALE!r};", "}", ""]
open(JAVA_DATA, "w").write("\n".join(lines))
json.dump({"knots": KNOTS, "gains": GAINS, "reference_hz": REF, "tonal_voice_scale": TONAL_SCALE, "noise_voice_scale": NOISE_SCALE},
          open(os.path.join(HERE, "wmata_ingame_tables.json"), "w"), indent=1)

times = [-1.0, 0.0, 0.13, 0.5, 0.7, 0.85, 1.0, 1.7, 2.5, 2.8, 2.9, 3.0, 3.1, 3.33, 3.8, 4.1, 4.5, 4.9, 5.25,
         5.4, 5.6, 6.1, 6.33, 6.9, 7.3, 7.6, 7.8, 8.1, 8.6, 8.9, 9.1, 9.5, 10.0, 12.0]
g = curves(np.array(times), TONAL_SCALE, NOISE_SCALE)
fmt = lambda arr: "{" + ", ".join(f"{float(x)!r}" for x in arr) + "}"
names = list(g.keys())
test = [
    "package de.mrjulsen.paw.traction;",
    "",
    "import static org.junit.jupiter.api.Assertions.assertEquals;",
    "import static org.junit.jupiter.api.Assertions.assertTrue;",
    "",
    "import org.junit.jupiter.api.Test;",
    "",
    "/**",
    " * Generated by tools/sound/wmata/build_wmata_ingame.py. The expected values come from",
    " * SciPy's PchipInterpolator on the same knots, so this pins the Java curves to the offline",
    " * synthesis the profile was tuned with.",
    " */",
    "class WmataTractionTest {",
    f"    private static final double[] TIMES = {fmt(times)};",
]
for nm in names:
    test.append(f"    private static final double[] {nm.upper()} = {fmt(g[nm])};")
test += ["", "    @Test", "    void curvesMatchOfflineSynthesis() {", "        for (int i = 0; i < TIMES.length; i++) {", "            double t = TIMES[i];"]
for nm in names:
    test.append(f'            assertEquals({nm.upper()}[i], WmataTraction.{nm}(t), 1e-9, "{nm} at t=" + t);')
test += ["        }", "    }", "",
         "    @Test",
         "    void volumesStayWithinWhatTheEngineCanPlay() {",
         "        for (double t = 0; t <= WmataTractionData.TIMELINE_SECONDS; t += 0.005) {"]
for nm in names:
    if nm.endswith("Volume"):
        test.append(f'            double {nm} = WmataTraction.{nm}(t);')
        test.append(f'            assertTrue({nm} >= 0 && {nm} <= 1.0 + 1e-12, "{nm} at t=" + t + " was " + {nm});')
test += ["        }", "    }", "",
         "    @Test",
         "    void pitchRatiosStayInsideTheEngineClamp() {",
         "        for (double t = 0; t <= WmataTractionData.TIMELINE_SECONDS; t += 0.005) {"]
for nm, ref in (("upperFrequency", "UPPER"), ("ridgeFrequency", "RIDGE"), ("briefFrequency", "BRIEF")):
    test.append(f"            double {nm}Ratio = WmataTraction.{nm}(t) / WmataTractionData.{ref}_REFERENCE_HZ;")
    test.append(f'            assertTrue({nm}Ratio >= 0.5 && {nm}Ratio <= 2.0, "{nm} ratio at t=" + t);')
test += ["        }", "    }", "}", ""]
open(JAVA_TEST, "w").write("\n".join(test))
print(f"\nwrote {os.path.relpath(JAVA_DATA, REPO)} and {os.path.relpath(JAVA_TEST, REPO)}")
