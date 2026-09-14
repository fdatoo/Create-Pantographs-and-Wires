"""Render the ONIX model for comparison with recordings and for gameplay auditions.

CLI: render_onix.py OUT_DIR [REFERENCE_DIR]
  OUT_DIR       where renders and comparison plots go
  REFERENCE_DIR folder with wmata_full_mono.wav (platform) and src/aQlV_W8bvaY.wav (pickup coil),
                for the comparison plot; skipped when absent
"""
import os, sys
import numpy as np
import soundfile as sf
import onix_model as OM
import traction_model as TM

SR = OM.SAMPLE_RATE
TICK = SR // 20
MASTER_GAIN_DB = 0.0


def render(speeds, powers, brakes, stems=False):
    synth = OM.Synth()
    pwm_count = len(OM.ELECTRICAL_LINES) * 3
    full, pwm = [], []
    for speed, power, brake in zip(speeds, powers, brakes):
        synth.set_state(speed, power, brake)
        if stems:
            saved = list(synth.target)
            rotor_only = [(f, 0.0) if i < pwm_count else (f, a) for i, (f, a) in enumerate(saved)]
        full.append(synth.render(TICK))
    return np.concatenate(full)


def stem_render(speeds, powers, brakes, keep):
    """Render with only the lines keep(i) is true for, so stems sum to the full mix."""
    synth = OM.Synth()
    out = []
    for speed, power, brake in zip(speeds, powers, brakes):
        synth.set_state(speed, power, brake)
        synth.target = [(f, a if keep(i) else 0.0) for i, (f, a) in enumerate(synth.target)]
        out.append(synth.render(TICK))
    return np.concatenate(out)


def with_demand(speeds):
    demand = TM.TractionDemand()
    powers, brakes = [], []
    for s in speeds:
        demand.update(s)
        powers.append(demand.power)
        brakes.append(demand.brake)
    return powers, brakes


def gameplay_speeds(top):
    speeds, phases, v = [0.0] * 10, {}, 0.0
    start = len(speeds)
    while v < top - 1e-12:
        v = min(top, v + TM.CREATE_ACCELERATION)
        speeds.append(v)
    phases["accelerating"] = (start, len(speeds))
    start = len(speeds); speeds += [v] * 100; phases["steady speed"] = (start, len(speeds))
    start = len(speeds)
    for _ in range(100):
        v -= 0.0005
        speeds.append(v)
    phases["coasting"] = (start, len(speeds))
    start = len(speeds)
    while v > 0:
        v = max(0.0, v - TM.CREATE_ACCELERATION)
        speeds.append(v)
    phases["braking to a stop"] = (start, len(speeds))
    return speeds + [0.0] * 20, phases


def comparison_plot(out_dir, ref_dir):
    import matplotlib; matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from scipy.signal import stft
    # Platform departure: f1 = 6.52 (t + 0.99) Hz in crop seconds, full demand.
    ticks = np.arange(-20, 240)
    f1 = np.maximum(0.0, 6.52 * (ticks / 20 + 0.99))
    speeds = np.maximum(0.0, f1 - OM.SLIP_HZ) / (OM.MPS_PER_BLOCK_PER_TICK * OM.F1_PER_MPS)
    model = render(speeds, np.ones(len(ticks)), np.zeros(len(ticks)))
    sf.write(os.path.join(out_dir, "model_platform_departure.wav"), (model * 10 ** (MASTER_GAIN_DB / 20)).astype(np.float32), SR, subtype="PCM_24")
    panels = [("model", model, SR, -1.0)]
    platform, sr_p = sf.read(os.path.join(ref_dir, "wmata_full_mono.wav"))
    panels.insert(0, ("platform recording (3hwLMeuslhY)", platform[int(7 * sr_p):int(20 * sr_p)], sr_p, -1.0))
    coil, sr_c = sf.read(os.path.join(ref_dir, "src", "aQlV_W8bvaY.wav"))
    panels.append(("pickup coil, same drive (aQlV_W8bvaY), electrical", coil[int(7.5 * sr_c):int(24 * sr_c)], sr_c, 0.0))
    fig, axes = plt.subplots(len(panels), 1, figsize=(16, 4.2 * len(panels)))
    for ax, (title, x, sr, offset) in zip(axes, panels):
        f, t, Z = stft(x, sr, nperseg=8192, noverlap=8192 - 1200)
        S = 20 * np.log10(np.abs(Z) + 1e-9)
        m = f < 3300
        ax.pcolormesh(t + offset, f[m], S[m], shading="auto", cmap="magma", vmin=np.percentile(S[m], 50), vmax=np.percentile(S[m], 99.8))
        ax.set_title(title); ax.set_ylabel("Hz")
    axes[-1].set_xlabel("s")
    fig.tight_layout(); fig.savefig(os.path.join(out_dir, "model_vs_recordings.png"), dpi=70)


def main():
    out_dir = sys.argv[1]
    os.makedirs(out_dir, exist_ok=True)
    if len(sys.argv) > 2 and os.path.exists(os.path.join(sys.argv[2], "wmata_full_mono.wav")):
        comparison_plot(out_dir, sys.argv[2])
    gain = 10 ** (MASTER_GAIN_DB / 20)
    pwm_count = len(OM.ELECTRICAL_LINES) * 3
    db = lambda x: 20 * np.log10(max(float(np.sqrt(np.mean(np.square(x)))), 1e-9))
    for name, top in (("gameplay_top_0.7", 0.7), ("gameplay_top_1.3", 1.3)):
        speeds, phases = gameplay_speeds(top)
        powers, brakes = with_demand(speeds)
        inverter = stem_render(speeds, powers, brakes, lambda i: i < pwm_count)
        rotor = stem_render(speeds, powers, brakes, lambda i: i >= pwm_count)
        full = inverter + rotor
        for label, x in (("full_mix", full), ("stem_inverter", inverter), ("stem_rotor", rotor)):
            sf.write(os.path.join(out_dir, f"{name}_{label}.wav"), (x * gain).astype(np.float32), SR, subtype="PCM_24")
        print(f"{name}: peak {20*np.log10(np.max(np.abs(full)) * gain + 1e-12):.2f} dBFS")
        for phase, (a, b) in phases.items():
            s = slice(a * TICK, b * TICK)
            print(f"  {phase:18} full {db(full[s]*gain):6.1f}  inverter {db(inverter[s]*gain):6.1f}  rotor {db(rotor[s]*gain):6.1f} dBFS")


if __name__ == "__main__":
    main()
