"""WMATA 6000-series reference-matching synthesis, from wmata-agent-settings.json.

Synthesis time 0 = source second 8.0. All levels are relative synthesis controls.

render() takes optional tuning overrides so tune_wmata.py can adjust against the
reference without editing the spec. With no overrides it renders the brief's
proposed starting settings exactly.

CLI: synth_wmata.py [--no-optional] [--tag NAME]   (starting settings, written to disk)
"""
import argparse, json, os
import numpy as np
import soundfile as sf
from scipy.interpolate import PchipInterpolator
from scipy.signal import butter, sosfilt, iirpeak, lfilter
from scipy.ndimage import gaussian_filter1d

HERE = os.path.dirname(os.path.abspath(__file__))


def load_spec():
    return json.load(open(os.path.join(HERE, "wmata-agent-settings.json")))["proposed_synthesis_starting_settings"]


def pchip_hold(knots, tt):
    """Monotone cubic through knots; hold the endpoint values outside the knot range."""
    k = np.asarray(knots, dtype=float)
    f = PchipInterpolator(k[:, 0], k[:, 1], extrapolate=False)
    return f(np.clip(tt, k[0, 0], k[-1, 0]))


def level_env(knots, tt):
    """Envelope interpolated smoothly in dB, then converted to linear. -60 dB counts as silence."""
    db = pchip_hold(knots, tt)
    lin = 10 ** (db / 20)
    lin[db <= -59.999] = 0.0
    return lin


def rms(x):
    return float(np.sqrt(np.mean(x ** 2)))


def render(spec=None, *, include_optional=True, level_overrides=None, noise_env_knots=None,
           noise_db=None, broadband_db=-6.0, seed=6000, layer_gains_db=None, a_diffuse=None):
    """Render the scene. Returns full, tones, noise (all after one shared master gain), layers and gain.

    level_overrides: {layer_name: level_knots_s_db} replacing a layer's proposed levels (tuning).
    noise_env_knots: dB knots shaping the noise over time (tuning; the brief's noise is flat).
    noise_db: noise RMS relative to all tones, dB; None uses the brief's starting value.
    broadband_db: broadband 80 Hz-6 kHz noise relative to the summed body bands. The brief
        gives body bands but no broadband share, so this is a free choice.
    layer_gains_db: {layer_name: constant dB offset} applied on top of the layer's envelope (tuning).
    a_diffuse: tuning component not in the brief. Narrowband noise riding layer A's own
        frequency track, for the stretch where the reference cluster turns broad and diffuse.
        {"width_hz": total bandwidth, "level_knots_s_db": level relative to A's centre
        amplitude at 0 dB, "line_cut_knots_s_db": dB cut applied to A's line (centre and
        satellites) over time}. It is heard as part of the tones, so it counts as tonal.
    """
    layer_gains_db = layer_gains_db or {}
    spec = spec or load_spec()
    level_overrides = level_overrides or {}
    SR = spec["sample_rate_hz"]; N = SR * spec["duration_s"]
    t = np.arange(N) / SR
    rng = np.random.default_rng(seed)

    def osc(freq):
        return np.sin(2 * np.pi * np.cumsum(freq) / SR)

    CONTROL_RATE = 1000  # smoothing at 1 kHz then interpolating is equivalent and ~2000x cheaper

    def smoothed_random(smoothing_s):
        n = int(np.ceil(N / SR * CONTROL_RATE)) + 2
        x = gaussian_filter1d(rng.standard_normal(n), sigma=smoothing_s * CONTROL_RATE)
        return np.interp(t, np.arange(n) / CONTROL_RATE, x)

    def smoothed_drift(max_abs, smoothing_s):
        x = smoothed_random(smoothing_s)
        return x / np.max(np.abs(x)) * max_abs

    layers = {}
    for L in spec["layers"]:
        name = L["name"]
        if not include_optional and "optional" in name:
            continue
        f = pchip_hold(L["frequency_knots_s_hz"], t)
        a = level_env(level_overrides.get(name, L["level_knots_s_db"]), t)
        sig = osc(f)
        if "satellite_offsets_hz" in L:
            for off, rel in zip(L["satellite_offsets_hz"], L["satellite_levels_db_relative_to_center"]):
                drift = smoothed_drift(L["satellite_drift_hz"], L["satellite_drift_smoothing_s"])
                sig = sig + 10 ** (rel / 20) * osc(f + off + drift)
        a = a * 10 ** (layer_gains_db.get(name, 0.0) / 20)
        if a_diffuse is not None and name.startswith("A_"):
            sig = sig * level_env(a_diffuse["line_cut_knots_s_db"], t)
            # Band-limited complex noise shifted onto A's centre track: a cluster of energy
            # that follows the same frequency path without being one clean oscillator.
            half = a_diffuse["width_hz"] / 2
            lp_c = butter(4, half, btype="lowpass", fs=SR, output="sos")
            z = sosfilt(lp_c, rng.standard_normal(N)) + 1j * sosfilt(lp_c, rng.standard_normal(N))
            band = np.real(z * np.exp(2j * np.pi * np.cumsum(f) / SR))
            band = band / rms(band) / np.sqrt(2)  # same RMS as a unit sine
            sig = sig + band * level_env(a_diffuse["level_knots_s_db"], t)
        layers[name] = a * sig
    tones = sum(layers.values())

    # Continuous independent noise; 24 dB/oct = 4th-order Butterworth.
    nz = spec["noise"]
    white = rng.standard_normal(N)
    hp = butter(4, nz["highpass_hz"], btype="highpass", fs=SR, output="sos")
    lp = butter(4, nz["lowpass_hz"], btype="lowpass", fs=SR, output="sos")
    broad = sosfilt(lp, sosfilt(hp, white))
    body = np.zeros(N)
    for (fc, q), g in zip(nz["body_parallel_bandpass_hz_q"], nz["body_band_relative_gains_db"]):
        b, a_ = iirpeak(fc, q, fs=SR)
        band = lfilter(b, a_, broad)
        body += 10 ** (g / 20) * band / rms(band)
    noise = body + broad / rms(broad) * rms(body) * 10 ** (broadband_db / 20)
    am = smoothed_random(nz["am_smoothing_s"])
    noise *= 1 + nz["irregular_am_depth_fraction"] * am / np.max(np.abs(am))
    if noise_env_knots is not None:
        noise *= level_env(noise_env_knots, t)

    rel = nz["start_noise_rms_relative_to_all_tones_db"] if noise_db is None else noise_db
    noise *= rms(tones) * 10 ** (rel / 20) / rms(noise)

    full = tones + noise
    out = spec["output"]
    gain = 10 ** (out["whole_clip_rms_target_dbfs"] / 20) / rms(full)
    ceiling = 10 ** (out["sample_peak_ceiling_dbfs"] / 20)
    if np.max(np.abs(full * gain)) > ceiling:
        gain = ceiling / np.max(np.abs(full))  # lower master gain; never clip
    return {
        "full": full * gain, "tones": tones * gain, "noise": noise * gain,
        "layers": {k: v * gain for k, v in layers.items()}, "gain": gain, "sr": SR,
    }


def write(result, tag):
    sr = result["sr"]
    sf.write(os.path.join(HERE, f"wmata_synth_full{tag}.wav"), result["full"], sr, subtype="PCM_24")
    sf.write(os.path.join(HERE, f"wmata_synth_tonal{tag}.wav"), result["tones"], sr, subtype="PCM_24")
    db = lambda x: 20 * np.log10(max(x, 1e-12))
    f, tn, nz = result["full"], result["tones"], result["noise"]
    print(f"[{tag or 'start'}] layers {list(result['layers'])}")
    print(f"  master gain {db(result['gain']):+.2f} dB | full RMS {db(rms(f)):.2f} dBFS, peak {db(np.max(np.abs(f))):.2f} dBFS")
    print(f"  tonal RMS {db(rms(tn)):.2f} dBFS | noise RMS {db(rms(nz)):.2f} dBFS (rel {db(rms(nz)) - db(rms(tn)):+.2f} dB)")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--no-optional", action="store_true", help="omit layers C and D")
    ap.add_argument("--tag", default=None)
    a = ap.parse_args()
    write(render(include_optional=not a.no_optional), a.tag if a.tag is not None else ("_AB" if a.no_optional else ""))
