"""Stage 2: match how far each tone stands out, not just where it sits.

Starts from stage 1 (wmata_tuning.json: A level shape, noise envelope, noise level,
broadband share, tone B boost) and adds tuning knobs, none of them measurements:
  - constant level offsets for B, C and D;
  - after ~3 s, a cut to A's clean line with a narrowband noise band on A's own track
    filling the energy back in, since the reference cluster turns broad and diffuse there.
Hard constraint as before: every high/medium-confidence feature point stays in tolerance.
Objective: band error + RMS envelope error + mean per-layer prominence error.
"""
import itertools, json, os
import numpy as np
import soundfile as sf
import synth_wmata as S
import tune_wmata as T

HERE = os.path.dirname(os.path.abspath(__file__))
SR = 48000
st1 = json.load(open(os.path.join(HERE, "wmata_tuning.json")))
meas = T.meas["tones"]
F = np.fft.rfftfreq(T.NFFT, 1 / SR)


def prominence(x, t, lo, hi):
    c = int(round(t * SR)); s = max(0, c - T.WIN // 2)
    P = 20 * np.log10(np.abs(np.fft.rfft(x[s:s + T.WIN] * T.HANN, T.NFFT)) + 1e-12)
    m = (F >= lo) & (F <= hi); i = np.argmax(P[m]); fpk = F[m][i]
    ring = (np.abs(F - fpk) > 25) & (np.abs(F - fpk) < 120)
    return P[m][i] - np.median(P[ring])


def prom_by_feature(x):
    out = {}
    for p in meas:
        if p["confidence"].startswith("low"):
            continue
        out.setdefault(p["feature"], []).append(prominence(x, p["crop_time_s"], p["search_low_hz"], p["search_high_hz"]))
    return {k: float(np.mean(v)) for k, v in out.items()}


def prom_late_a(x):
    """A's prominence over its broad stretch (>= 3 s), where the stage-1 error sits."""
    v = [prominence(x, p["crop_time_s"], p["search_low_hz"], p["search_high_hz"])
         for p in meas if p["feature"] == "upper_cluster_peak" and p["crop_time_s"] >= 3]
    return float(np.mean(v))


ref_prom = prom_by_feature(T.ref)
ref_prom_late_a = prom_late_a(T.ref)


def line_cut(depth_db):
    return [[0, 0], [2.5, 0], [4, -depth_db], [10, -depth_db]]


def diffuse_level(level_db):
    return [[0, -60], [2.5, -60], [4, level_db], [10, level_db]]


def render_with(b_gain, c_gain, d_gain, cut_db, diff_db):
    return S.render(
        level_overrides={"A_upper_cluster": st1["A_upper_cluster_level_knots_s_db"],
                         "B_rising_low_tone": st1["B_rising_low_tone_level_knots_s_db"]},
        noise_env_knots=st1["noise_env_knots_s_db"], noise_db=st1["noise_db_rel_tones"],
        broadband_db=st1["broadband_db_rel_body"],
        layer_gains_db={"B_rising_low_tone": b_gain, "C_brief_upper_cluster_optional": c_gain,
                        "D_later_mid_tone_optional": d_gain},
        a_diffuse=None if cut_db == 0 and diff_db is None else
        {"width_hz": 160, "line_cut_knots_s_db": line_cut(cut_db),
         "level_knots_s_db": diffuse_level(-60 if diff_db is None else diff_db)})


def evaluate(res):
    tot, band_err, env_err, pct = T.score(res)
    pr = prom_by_feature(res["full"])
    diffs = {k: pr[k] - ref_prom[k] for k in ref_prom}
    diffs["upper_late"] = prom_late_a(res["full"]) - ref_prom_late_a
    prom_err = float(np.mean([abs(diffs[k]) for k in ("rising_low_ridge", "brief_upper_cluster", "later_mid_ridge", "upper_late")]))
    return band_err + env_err + 0.25 * prom_err, band_err, env_err, prom_err, diffs, pct


if __name__ == "__main__":
    print("reference prominence (dB):", {k: round(v, 1) for k, v in ref_prom.items()}, "| A >=3 s:", round(ref_prom_late_a, 1))
    base = render_with(0, 0, 0, 0, None)
    b = evaluate(base)
    print(f"stage 1 result: total {b[0]:.2f} (bands {b[1]:.2f}, env {b[2]:.2f}, prom {b[3]:.2f}) diffs", {k: round(v, 1) for k, v in b[4].items()})

    rows = []
    grid = itertools.product([0, 3, 6], [-12, -8, -4], [0, 3, 6], [0, 4, 8], [None, -6, -3, 0])
    for bg, cg, dg, cut, diff in grid:
        if cut == 0 and diff is not None:
            continue
        res = render_with(bg, cg, dg, cut, diff)
        fails = T.point_failures(res["full"])
        if fails:
            continue
        rows.append((evaluate(res), bg, cg, dg, cut, diff))
    rows.sort(key=lambda r: r[0][0])
    print(f"\n{len(rows)} passing combinations")
    print(f"{'total':>6} {'band':>5} {'env':>5} {'prom':>5} | {'B':>3} {'C':>4} {'D':>3} {'cut':>3} {'diff':>5} | promdiff B C D A>=3s | 0-300 300-1k 1-2k 2-4k 4-8k")
    for (tot, be, ee, pe, d, pct), bg, cg, dg, cut, diff in rows[:10]:
        print(f"{tot:6.2f} {be:5.2f} {ee:5.2f} {pe:5.2f} | {bg:3} {cg:4} {dg:3} {cut:3} {str(diff):>5} | "
              f"{d['rising_low_ridge']:+.1f} {d['brief_upper_cluster']:+.1f} {d['later_mid_ridge']:+.1f} {d['upper_late']:+.1f} | "
              + " ".join(f"{v:5.1f}" for v in pct[:5]))
    print(f"{'ref':>62} | " + " ".join(f"{v:5.1f}" for v in T.ref_pct[:5]))

    (tot, be, ee, pe, d, pct), bg, cg, dg, cut, diff = rows[0]
    res = render_with(bg, cg, dg, cut, diff)
    S.write(res, "_tuned2")
    st2 = dict(st1, stage2={"layer_gains_db": {"B_rising_low_tone": bg, "C_brief_upper_cluster_optional": cg,
                                               "D_later_mid_tone_optional": dg},
                            "a_diffuse": None if cut == 0 and diff is None else
                            {"width_hz": 160, "line_cut_knots_s_db": line_cut(cut),
                             "level_knots_s_db": diffuse_level(-60 if diff is None else diff)}})
    json.dump(st2, open(os.path.join(HERE, "wmata_tuning2.json"), "w"), indent=1)
    print(f"\nchosen: B {bg:+} dB, C {cg:+} dB, D {dg:+} dB, A line cut {cut} dB after 4 s, diffuse band {diff} dB -> wmata_tuning2.json")
