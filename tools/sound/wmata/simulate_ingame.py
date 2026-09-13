"""Render what the client plays, so a change can be judged by ear before building a jar.

Uses the shipped .ogg loops, the tables build_wmata_ingame.py wrote (wmata_ingame_tables.json), the
same demand and mixing logic as the client (traction_model.py), and TractionHumSoundInstance's
per-tick pitch, load and fade smoothing.

Two scenarios, each written as a full mix plus an upper-whine stem and a lower-tone/body stem that
sum to it: a slow 10 s departure (every stage audible) and a gameplay test at Create's own rates
(accelerating, steady speed, coasting, braking). Every file uses the same fixed master gain, so
levels compare across files and revisions; nothing is normalised.

CLI: simulate_ingame.py OUT_DIR
"""
import argparse, json, os
import numpy as np
import soundfile as sf
import traction_model as TM

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
SOUNDS = os.path.join(REPO, "common/src/main/resources/assets/pantographsandwires/sounds/electric")
SR, TPS = 44100, 20
TICK = SR // TPS

# The same master gain as the upper-whine evaluation renders, so this revision's levels compare to them.
MASTER_GAIN_DB = 3.0
TRACTION_VOLUME = 0.5  # the client config default
SPEED_AT_TOP = 0.7
SPEED_AT_FULL_MOTION = 0.08
SPEED_CONSIDERED_STOPPED = 0.01

tables = json.load(open(os.path.join(HERE, "wmata_ingame_tables.json")))


class Voice:
    """TractionHumSoundInstance: glides pitch and load toward their targets and fades in and out."""

    def __init__(self, loop, reference_hz, start_tick):
        self.loop = loop
        self.reference = reference_hz
        self.start_tick = start_tick
        self.pitch = None
        self.load = None
        self.target_pitch = 1.0
        self.target_load = 0.0
        self.fade = 0.0
        self.active = True
        self.history = []

    def set_frequency(self, hz):
        self.target_pitch = min(2.0, max(0.5, hz / self.reference))
        if self.pitch is None:
            self.pitch = self.target_pitch

    def set_load(self, load):
        self.target_load = load
        if self.load is None:
            self.load = load

    def tick(self):
        self.pitch += (self.target_pitch - self.pitch) * 0.22
        self.load += (self.target_load - self.load) * (0.25 if self.target_load < self.load else 0.05)
        self.fade = min(1.0, self.fade + 0.1) if self.active else max(0.0, self.fade - 0.1)
        self.history.append((self.pitch, TRACTION_VOLUME * self.fade * self.load))

    def audio(self, total_ticks):
        out = np.zeros(total_ticks * TICK)
        if not self.history:
            return out
        h = np.array(self.history)
        n = len(h) * TICK
        ticks = np.arange(n) / TICK
        pitch = np.interp(ticks, np.arange(len(h)), h[:, 0])
        vol = np.interp(ticks, np.arange(len(h)), h[:, 1])
        pos = np.cumsum(pitch) % len(self.loop)
        i0 = np.floor(pos).astype(int)
        frac = pos - i0
        signal = (self.loop[i0] * (1 - frac) + self.loop[(i0 + 1) % len(self.loop)] * frac) * vol
        start = self.start_tick * TICK
        end = min(len(out), start + n)
        out[start:end] = signal[:end - start]
        return out


def render(speeds):
    loops = {name: sf.read(os.path.join(SOUNDS, name + ".ogg"))[0] for name in set(TM.SAMPLES.values()) | {"wmata_noise"}}
    banks, current, demand = [], None, None
    power_trace, brake_trace = [], []
    for tick, speed in enumerate(speeds):
        if speed >= SPEED_CONSIDERED_STOPPED:
            if current is None:
                current = {v: Voice(loops[TM.SAMPLES[v]], tables["reference_hz"][i], tick) for i, v in enumerate(TM.VOICES)}
                current["NOISE"] = Voice(loops["wmata_noise"], 1.0, tick)
                demand = TM.TractionDemand()
                banks.append(current)
            demand.update(speed)
            fraction = min(1.0, speed / SPEED_AT_TOP)
            motion = min(1.0, speed / SPEED_AT_FULL_MOTION)
            for v in TM.VOICES:
                current[v].set_frequency(TM.frequency(tables, v, fraction))
                current[v].set_load(motion * TM.volume(tables, v, fraction, demand.power, demand.brake))
            current["NOISE"].set_frequency(1.0)
            current["NOISE"].set_load(motion * TM.sample(tables["noise_level"], tables["steps"], fraction))
        elif current is not None:
            for voice in current.values():
                voice.active = False
            current = None
        power_trace.append(demand.power if current is not None else 0.0)
        brake_trace.append(demand.brake if current is not None else 0.0)
        for bank in banks:
            for voice in bank.values():
                if voice.active or voice.fade > 0:
                    voice.tick()

    total = len(speeds) + 12
    stems = {"upper_whine": np.zeros(total * TICK), "lower_tone_body": np.zeros(total * TICK)}
    for bank in banks:
        for name, voice in bank.items():
            stem = "upper_whine" if name in TM.UPPER_STEM else "lower_tone_body"
            stems[stem] += voice.audio(total)
    return stems, np.array(power_trace), np.array(brake_trace)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out_dir")
    args = ap.parse_args()
    os.makedirs(args.out_dir, exist_ok=True)
    gain = 10 ** (MASTER_GAIN_DB / 20)
    db = lambda x: 20 * np.log10(max(float(np.sqrt(np.mean(np.square(x)))), 1e-9))

    for name, scenario in (("departure", TM.departure), ("gameplay_test", TM.gameplay)):
        speeds, phases = scenario()
        stems, power, brake = render(speeds)
        full = stems["upper_whine"] + stems["lower_tone_body"]
        files = {f"{name}_full_mix": full, f"{name}_stem_upper_whine": stems["upper_whine"], f"{name}_stem_lower_tone_body": stems["lower_tone_body"]}
        peak = max(float(np.max(np.abs(x))) for x in files.values()) * gain
        for file, x in files.items():
            sf.write(os.path.join(args.out_dir, file + ".wav"), (x * gain).astype(np.float32), SR, subtype="PCM_24")
        print(f"\n{name}: master gain {MASTER_GAIN_DB:+.1f} dB for all three files, peak {20*np.log10(peak):.2f} dBFS"
              + ("  WARNING: clips" if peak >= 1 else ""))
        print(f"  {'phase':24} {'full mix':>9} {'upper whine':>12} {'lower/body':>11} {'upper vs lower':>15} {'power':>6} {'brake':>6}")
        for phase, (a, b) in phases.items():
            s = slice(a * TICK, b * TICK)
            print(f"  {phase:24} {db(full[s] * gain):8.1f}  {db(stems['upper_whine'][s] * gain):11.1f} {db(stems['lower_tone_body'][s] * gain):11.1f}"
                  f" {db(stems['upper_whine'][s]) - db(stems['lower_tone_body'][s]):+13.1f}dB {power[a:b].mean():6.2f} {brake[a:b].mean():6.2f}")


if __name__ == "__main__":
    main()
