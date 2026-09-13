"""Adjust the WMATA starting settings against the reference, with a few named knobs.

Tuning, not measurement:
  1. Layer A's level knots follow the reference's own 2-4 kHz band trajectory.
  2. Noise gets a dB envelope following the reference's 300 Hz-2 kHz trend, interpolated
     straight across 3-5 s, where tone B dominates the midrange rather than noise.
  3. A grid over three scalars (noise level, broadband share, tone B boost), scored against
     the scene band energies and the 0.5 s RMS envelope after one common offset.
Hard constraint: every high/medium-confidence feature point, measured the brief's way,
must stay within tolerance. A balance that buries a tone is rejected, however well it scores.
Frequencies are untouched.
"""
import itertools, json, os
import numpy as np
import soundfile as sf
from scipy.ndimage import uniform_filter1d
import synth_wmata as S

HERE = os.path.dirname(os.path.abspath(__file__))
SR = 48000
meas = json.load(open(os.path.join(HERE, "wmata-agent-settings.json")))["measured_data"]
spec = S.load_spec()
ref, _ = sf.read(os.path.join(HERE, "wmata_ref.wav"))
CENTERS = np.arange(0.25, 10, 0.5)
TOL = {"rising_low_ridge": 10, "upper_cluster_peak": 60, "brief_upper_cluster": 60, "later_mid_ridge": 30}


def band_db(x, lo, hi):
    out = []
    for c in CENTERS:
        s = int((c - 0.25) * SR); seg = x[s:s + SR // 2] * np.hanning(SR // 2)
        P = np.abs(np.fft.rfft(seg)) ** 2; f = np.fft.rfftfreq(len(seg), 1 / SR)
        out.append(10 * np.log10(P[(f >= lo) & (f < hi)].sum() + 1e-20))
    return np.array(out)


def knots(values_db, smooth):
    v = uniform_filter1d(values_db, size=smooth, mode="nearest")
    v = v - v.max()
    return [[0.0, float(v[0])]] + [[float(c), float(x)] for c, x in zip(CENTERS, v)] + [[10.0, float(v[-1])]]


# 1. Layer A level from the reference 2-4 kHz trajectory.
a_knots = knots(band_db(ref, 2000, 4000), smooth=3)
# 2. Noise envelope from the 300 Hz-2 kHz trend, bridging tone B's 3-5 s span.
mid = 10 * np.log10(10 ** (band_db(ref, 300, 1000) / 10) + 10 ** (band_db(ref, 1000, 2000) / 10))
mask = (CENTERS > 2.9) & (CENTERS < 5.1)
mid[mask] = np.interp(CENTERS[mask], CENTERS[~mask], mid[~mask])
n_knots = knots(mid, smooth=5)

B_KNOTS = next(L["level_knots_s_db"] for L in spec["layers"] if L["name"].startswith("B_"))


def boosted_b(offset_db):
    """Tone B's proposed envelope raised by offset_db; silent knots stay silent."""
    return [[t, v if v <= -60 else v + offset_db] for t, v in B_KNOTS]


WIN, NFFT = int(0.25 * SR), 65536
FREQS = np.fft.rfftfreq(NFFT, 1 / SR)
HANN = np.hanning(WIN)


def peak_at(x, t_center, lo, hi):
    c = int(round(t_center * SR)); s = max(0, c - WIN // 2)
    seg = x[s:s + WIN]
    spec_ = np.abs(np.fft.rfft(seg * HANN, NFFT))
    m = (FREQS >= lo) & (FREQS <= hi)
    return FREQS[m][np.argmax(spec_[m])]


def point_failures(x):
    fails = []
    for p in meas["tones"]:
        if p["confidence"].startswith("low"):
            continue
        d = peak_at(x, p["crop_time_s"], p["search_low_hz"], p["search_high_hz"]) - p["peak_hz"]
        if abs(d) > TOL[p["feature"]]:
            fails.append((p["feature"], p["crop_time_s"], round(float(d), 1)))
    return fails


def band_pct(x):
    P = np.abs(np.fft.rfft(x)) ** 2; f = np.fft.rfftfreq(len(x), 1 / SR)
    e = meas["band_edges_hz"]
    return np.array([P[(f >= e[i]) & (f < e[i + 1])].sum() for i in range(len(e) - 1)]) / P.sum() * 100


def rms_env(x):
    return np.array([20 * np.log10(np.sqrt(np.mean(x[int((c - .25) * SR):int((c + .25) * SR)] ** 2)) + 1e-12) for c in CENTERS])


ref_pct, ref_env = band_pct(ref), rms_env(ref)


def score(res):
    p = band_pct(res["full"])
    band_err = np.sqrt(np.mean((10 * np.log10((p[:5] + 1e-3) / ref_pct[:5])) ** 2))
    e = rms_env(res["full"]); resid = e + np.mean(ref_env - e) - ref_env
    env_err = np.sqrt(np.mean(resid ** 2))
    return band_err + env_err, band_err, env_err, p


def render_with(noise_db, bb_db, b_off):
    return S.render(level_overrides={"A_upper_cluster": a_knots, "B_rising_low_tone": boosted_b(b_off)},
                    noise_env_knots=n_knots, noise_db=noise_db, broadband_db=bb_db)


if __name__ == "__main__":
    print("A level knots (tuned):", [[round(t, 2), round(v, 1)] for t, v in a_knots])
    print("noise env knots (tuned):", [[round(t, 2), round(v, 1)] for t, v in n_knots])
    s0 = score(S.render())
    print(f"\nstarting settings: total {s0[0]:.2f} (bands {s0[1]:.2f} dB, env {s0[2]:.2f} dB), failures {point_failures(S.render()['full'])}")

    rows = []
    for nd, bd, bo in itertools.product([3, 4.5, 6, 7.5, 9], [-18, -15, -12, -9], [0, 3, 6, 9, 12]):
        res = render_with(nd, bd, bo)
        sc = score(res)
        rows.append((sc[0], nd, bd, bo, sc, point_failures(res["full"])))
    passing = sorted([r for r in rows if not r[5]], key=lambda r: r[0])
    failing = sorted([r for r in rows if r[5]], key=lambda r: r[0])
    print(f"\n{len(passing)}/{len(rows)} combinations keep every feature point in tolerance")
    hdr = f"{'total':>6} {'bands':>6} {'env':>6} {'noise':>6} {'bb':>5} {'Bboost':>6} | 0-300 300-1k 1-2k 2-4k 4-8k"
    print("best passing:\n" + hdr)
    for tot, nd, bd, bo, sc, _ in passing[:8]:
        print(f"{tot:6.2f} {sc[1]:6.2f} {sc[2]:6.2f} {nd:6} {bd:5} {bo:6} | " + " ".join(f"{v:5.1f}" for v in sc[3][:5]))
    print(f"{'ref':>40} | " + " ".join(f"{v:5.1f}" for v in ref_pct[:5]))
    if failing:
        tot, nd, bd, bo, sc, f = failing[0]
        print(f"\n(best-scoring rejected combo: total {tot:.2f} noise {nd} bb {bd} Bboost {bo}, fails {f})")

    if not passing:
        raise SystemExit("no combination keeps all tones readable; widen the grid")
    tot, nd, bd, bo, sc, _ = passing[0]
    res = render_with(nd, bd, bo)
    S.write(res, "_tuned")
    json.dump({"A_upper_cluster_level_knots_s_db": a_knots, "noise_env_knots_s_db": n_knots,
               "B_rising_low_tone_level_knots_s_db": boosted_b(bo), "B_boost_db": bo,
               "noise_db_rel_tones": nd, "broadband_db_rel_body": bd},
              open(os.path.join(HERE, "wmata_tuning.json"), "w"), indent=1)
    print(f"\nchosen: noise {nd:+} dB rel tones, broadband {bd:+} dB rel body, tone B +{bo} dB -> wmata_tuning.json")
