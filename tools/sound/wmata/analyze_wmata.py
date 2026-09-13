"""Compare a WMATA synthesis render against the reference crop, using the brief's method.

Usage: analyze_wmata.py <synth_full.wav> [--png out.png]
"""
import argparse, json, os
import numpy as np
import soundfile as sf
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from scipy.signal import spectrogram

HERE = os.path.dirname(os.path.abspath(__file__))
ap = argparse.ArgumentParser()
ap.add_argument("synth")
ap.add_argument("--png", default=os.path.join(HERE, "wmata_comparison.png"))
args = ap.parse_args()

meas = json.load(open(os.path.join(HERE, "wmata-agent-settings.json")))["measured_data"]
ref, sr = sf.read(os.path.join(HERE, "wmata_ref.wav"))
syn, sr2 = sf.read(args.synth)
assert sr == sr2 == 48000
if ref.ndim > 1: ref = ref.mean(axis=1)
n = min(len(ref), len(syn)); ref, syn = ref[:n], syn[:n]

NFFT = 65536
WIN = int(0.25 * sr)


def peak_at(x, t_center, lo, hi):
    """250 ms Hann window centred on t, 65536-point zero-padded FFT, strongest peak in [lo, hi]."""
    c = int(round(t_center * sr)); s = max(0, c - WIN // 2)
    seg = x[s:s + WIN]
    if len(seg) < WIN: seg = np.pad(seg, (0, WIN - len(seg)))
    spec = np.abs(np.fft.rfft(seg * np.hanning(WIN), NFFT))
    freqs = np.fft.rfftfreq(NFFT, 1 / sr)
    m = (freqs >= lo) & (freqs <= hi)
    i = np.argmax(spec[m])
    return freqs[m][i]


print("=== measured feature points (reference re-measured vs synthesis) ===")
print(f"{'t':>5} {'feature':<20} {'brief':>7} {'ref':>7} {'synth':>7} {'syn-brief':>9} tol  ok")
tol = {"rising_low_ridge": 10, "upper_cluster_peak": 60, "brief_upper_cluster": 60, "later_mid_ridge": 30}
summary = {}
for p in meas["tones"]:
    r = peak_at(ref, p["crop_time_s"], p["search_low_hz"], p["search_high_hz"])
    s = peak_at(syn, p["crop_time_s"], p["search_low_hz"], p["search_high_hz"])
    d = s - p["peak_hz"]; tl = tol[p["feature"]]
    ok = abs(d) <= tl if not p["confidence"].startswith("low") else None
    summary.setdefault(p["feature"], []).append(ok)
    print(f"{p['crop_time_s']:5.2f} {p['feature']:<20} {p['peak_hz']:7.1f} {r:7.1f} {s:7.1f} {d:+9.1f} {tl:>3} {'--' if ok is None else ('yes' if ok else 'NO')}")
for k, v in summary.items():
    vv = [x for x in v if x is not None]
    print(f"  {k}: {sum(vv)}/{len(vv)} within tolerance")

print("\n=== band energy % (whole 10 s) ===")
def bands(x):
    P = np.abs(np.fft.rfft(x)) ** 2; f = np.fft.rfftfreq(len(x), 1 / sr)
    e = meas["band_edges_hz"]; tot = P.sum()
    return [100 * P[(f >= e[i]) & (f < e[i + 1])].sum() / tot for i in range(len(e) - 1)]
rb, sb = bands(ref), bands(syn)
labels = ["0-300", "300-1k", "1-2k", "2-4k", "4-8k", ">8k"]
print(f"{'band':>7} {'brief':>7} {'ref':>7} {'synth':>7}")
for l, b, r_, s_ in zip(labels, meas["band_energy_percent"], rb, sb):
    print(f"{l:>7} {b:7.2f} {r_:7.2f} {s_:7.2f}")

print("\n=== 0.5 s RMS envelope (dBFS), one common offset ===")
def rms_env(x):
    out = []
    for c, _ in meas["rms_at_window_center_s_dbfs"]:
        s = int((c - 0.25) * sr); seg = x[s:s + int(0.5 * sr)]
        out.append(20 * np.log10(np.sqrt(np.mean(seg ** 2)) + 1e-12))
    return np.array(out)
brief_env = np.array([v for _, v in meas["rms_at_window_center_s_dbfs"]])
re, se = rms_env(ref), rms_env(syn)
off = np.mean(re - se)
print(f"reference re-measured vs brief: mean diff {np.mean(re - brief_env):+.2f} dB")
print(f"single offset applied to synth: {off:+.2f} dB")
print(f"{'t':>5} {'ref':>7} {'synth+off':>9} {'resid':>6}")
for (c, _), r_, s_ in zip(meas["rms_at_window_center_s_dbfs"], re, se + off):
    print(f"{c:5.2f} {r_:7.2f} {s_:9.2f} {s_ - r_:+6.2f}")
print(f"residual RMS {np.sqrt(np.mean((se + off - re) ** 2)):.2f} dB, max abs {np.max(np.abs(se + off - re)):.2f} dB")
db = lambda x: 20 * np.log10(np.sqrt(np.mean(x ** 2)))
print(f"\nwhole-clip RMS: ref {db(ref):.2f} dBFS, synth {db(syn):.2f} dBFS; synth peak {20*np.log10(np.max(np.abs(syn))):.2f} dBFS")

# Matched spectrograms, 0-4 kHz, same time/frequency scale and colour range.
fig, axes = plt.subplots(2, 1, figsize=(14, 9), sharex=True, sharey=True)
vmax = None
for ax, x, title in [(axes[0], ref, "Reference: source 8-18 s (crop time)"), (axes[1], syn, os.path.basename(args.synth))]:
    f, tt, S = spectrogram(x, fs=sr, window="hann", nperseg=4096, noverlap=3584, nfft=8192)
    Sdb = 10 * np.log10(S + 1e-14)
    if vmax is None: vmax = np.percentile(Sdb[f <= 4000], 99.7)
    ax.pcolormesh(tt, f, Sdb, vmin=vmax - 70, vmax=vmax, shading="auto", cmap="magma")
    ax.set_ylim(0, 4000); ax.set_title(title); ax.set_ylabel("Hz")
    for p in meas["tones"]:
        mk = {"rising_low_ridge": "c", "upper_cluster_peak": "w", "brief_upper_cluster": "y", "later_mid_ridge": "lime"}[p["feature"]]
        ax.plot(p["crop_time_s"], p["peak_hz"], marker="o", mfc="none", mec=mk, ms=7, mew=1.2)
axes[1].set_xlabel("crop time (s)")
plt.tight_layout(); plt.savefig(args.png, dpi=110)
print(f"\nspectrogram: {args.png}")
