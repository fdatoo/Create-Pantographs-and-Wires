"""Physical model of the WMATA 6000-series traction sound, synthesised from the drive rather than
imitated from a recording.

The 6000s (like the rebuilt 2000s and 3000s) run Alstom ONIX 2-level IGBT inverters feeding
asynchronous traction motors. What is heard is the inverter's switching pattern radiated by the
motors: lines at multiples of the carrier frequency plus or minus multiples of the motor supply
frequency f1, with a carrier that steps between a handful of values as the motors speed up, and
a line family locked to rotor speed.

Measured (tools/sound/wmata/RESEARCH.md):
  - carrier schedules while powering and braking, and the modulation index, from a pickup-coil
    recording of the same drive (no acoustic background at all);
  - that the airborne recordings follow that schedule, with the second carrier group strongest;
  - the rotor-locked family at harmonics of about 11.7 x rotor frequency.
Levels between lines come from PWM theory for a naturally sampled 2-level inverter, weighted by
current (1/f) and by one smooth acoustic response. No noise, no recorded material.

This file is the reference the client synthesiser (OnixTractionSynth.java) mirrors; golden tests
pin the two together.
"""
import math

SAMPLE_RATE = 48000

# Motor supply frequency per metre per second of train speed: 117 Hz at the recorded cruise.
F1_PER_MPS = 4.5
MPS_PER_BLOCK_PER_TICK = 20.0
# Slip at full demand: f1 runs above rotor frequency while powering and below it while braking.
SLIP_HZ = 3.0

# Carrier frequency (Hz) against f1 (Hz): (upper limit of f1, carrier). Measured switch points.
POWER_SCHEDULE = ((6.0, 1235.0), (29.3, 1186.0), (35.2, 1207.0), (46.9, 1237.0), (52.7, 1455.0),
                  (55.7, 1207.0), (85.0, 1230.0), (math.inf, 1186.0))
BRAKE_SCHEDULE = ((23.4, 1186.0), (32.2, 1207.0), (41.0, 1237.0), (46.9, 1455.0), (49.8, 1207.0),
                  (82.0, 1230.0), (math.inf, 1186.0))
# Modulation index against f1, from the 2fc group's J5/J1 ratio; flat once field weakening starts.
MODULATION = ((0.0, 0.50), (10.0, 0.57), (20.0, 0.70), (30.0, 0.72), (40.0, 0.78), (50.0, 0.83),
              (60.0, 0.87), (70.0, 0.97), (80.0, 1.01), (90.0, 1.05), (100.0, 1.07), (110.0, 1.08))

# Electrical lines kept: (carrier multiple m, f1 multiple n). Line-to-line voltage of a 2-level
# inverter has lines only where m + n is odd and n is not a multiple of 3.
ELECTRICAL_LINES = ((1, -4), (1, -2), (1, 2), (1, 4), (2, -1), (2, 1), (3, -2), (3, 2))
# Each electrical line is heard directly and through the magnetic force it makes with the
# fundamental flux, which lands one f1 either side of it.
DIRECT_WEIGHT = 0.5
FORCE_WEIGHT = 1.0
# Acoustic response: a lift around 2.45 kHz, where the airborne recordings put the energy. Narrow
# enough that the first carrier group near 1.2 kHz, strong electrically, stays well under the
# second group, as it does on the platform recording.
RESPONSE_CENTRE_HZ = 2450.0
RESPONSE_WIDTH_HZ = 350.0
RESPONSE_GAIN_DB = 16.0
PWM_LEVEL = 0.05

# Rotor-locked family: harmonics k of ROTOR_ORDER x rotor frequency. The platform recording shows
# mainly the second harmonic (23.4 x rotor), a faint first, and a trace of the third.
ROTOR_ORDER = 11.7
ROTOR_HARMONICS_DB = (-12.0, 0.0, -14.0)
ROTOR_LEVEL = 0.035
# Share of the rotor family left while coasting (it is partly mechanical).
ROTOR_COAST = 0.12
# The rotor lines fade as they climb (gone by about 2 kHz on the platform) and are not rendered as
# low rumble: they fade in between ROTOR_LOW_FADE_HZ.
ROTOR_ROLLOFF_HZ = 1500.0
ROTOR_LOW_FADE_HZ = (150.0, 350.0)

MAX_LINE_HZ = 5000.0
LINE_COUNT = len(ELECTRICAL_LINES) * 3 + len(ROTOR_HARMONICS_DB)


def smoothstep(edge0, edge1, x):
    t = min(1.0, max(0.0, (x - edge0) / (edge1 - edge0)))
    return t * t * (3 - 2 * t)


def carrier(f1, braking):
    for limit, fc in (BRAKE_SCHEDULE if braking else POWER_SCHEDULE):
        if f1 < limit:
            return fc
    return (BRAKE_SCHEDULE if braking else POWER_SCHEDULE)[-1][1]


def modulation(f1):
    if f1 <= MODULATION[0][0]:
        return MODULATION[0][1]
    for (f_lo, m_lo), (f_hi, m_hi) in zip(MODULATION, MODULATION[1:]):
        if f1 <= f_hi:
            return m_lo + (m_hi - m_lo) * (f1 - f_lo) / (f_hi - f_lo)
    return MODULATION[-1][1]


def bessel(n, x):
    """J_n(x) for integer n >= 0 by its power series; accurate for the arguments used (x < 6)."""
    total, term = 0.0, (x / 2) ** n / math.factorial(n)
    for k in range(40):
        total += term
        term *= -(x / 2) ** 2 / ((k + 1) * (k + 1 + n))
    return total


def response(f):
    return 10 ** (RESPONSE_GAIN_DB / 20 / (1 + ((f - RESPONSE_CENTRE_HZ) / RESPONSE_WIDTH_HZ) ** 2))


def lines(speed, power, brake):
    """Frequencies and amplitudes of every line for a speed (blocks per tick) and demand.

    Returns a list of (frequency, amplitude) in a fixed order, so each position is one oscillator.
    """
    demand = max(power, brake)
    braking = brake > power
    rotor = abs(speed) * MPS_PER_BLOCK_PER_TICK * F1_PER_MPS
    f1 = max(0.0, rotor + (-SLIP_HZ * brake if braking else SLIP_HZ * power))
    gate = smoothstep(0.02, 0.2, demand) * (0.55 + 0.45 * demand) * smoothstep(0.5, 3.0, f1)
    fc = carrier(f1, braking)
    index = modulation(f1)
    out = []
    for m, n in ELECTRICAL_LINES:
        f = m * fc + n * f1
        electrical = 4 / (m * math.pi) * abs(bessel(abs(n), m * math.pi * index / 2)) * abs(math.sin(n * math.pi / 3)) * 2 / math.sqrt(3)
        level = PWM_LEVEL * gate * electrical * 1000.0 / f
        out.append((f, level * DIRECT_WEIGHT * response(f)))
        out.append((f - f1, level * FORCE_WEIGHT * response(f - f1)))
        out.append((f + f1, level * FORCE_WEIGHT * response(f + f1)))
    rotor_gain = ROTOR_LEVEL * (ROTOR_COAST + (1 - ROTOR_COAST) * demand) * smoothstep(2.0, 8.0, rotor)
    for k, db in enumerate(ROTOR_HARMONICS_DB):
        f = (k + 1) * ROTOR_ORDER * rotor
        shape = smoothstep(ROTOR_LOW_FADE_HZ[0], ROTOR_LOW_FADE_HZ[1], f) / (1 + (f / ROTOR_ROLLOFF_HZ) ** 2)
        out.append((f, rotor_gain * 10 ** (db / 20) * shape if f < MAX_LINE_HZ else 0.0))
    return out


class Synth:
    """Mirror of OnixTractionSynth: a bank of phase-continuous oscillators whose targets change once
    per game tick. Amplitudes ramp linearly across each rendered block; a frequency moving less
    than GLIDE_LIMIT glides across the block, a larger step (a carrier change) lands at once."""
    GLIDE_LIMIT = 0.08

    def __init__(self):
        self.phase = [0.0] * LINE_COUNT
        self.freq = [0.0] * LINE_COUNT
        self.amp = [0.0] * LINE_COUNT
        self.target = [(0.0, 0.0)] * LINE_COUNT

    def set_state(self, speed, power, brake):
        self.target = lines(speed, power, brake)

    def render(self, count):
        import numpy as np
        out = np.zeros(count)
        n = np.arange(1, count + 1) / count
        for i, (f_target, a_target) in enumerate(self.target):
            f0 = self.freq[i]
            if f0 <= 0 or abs(f_target - f0) > self.GLIDE_LIMIT * max(f0, 1.0):
                freqs = np.full(count, f_target)
            else:
                freqs = f0 + (f_target - f0) * n
            amps = self.amp[i] + (a_target - self.amp[i]) * n
            phases = self.phase[i] + np.cumsum(freqs) / SAMPLE_RATE
            out += amps * np.sin(2 * np.pi * phases)
            self.phase[i] = float(phases[-1] % 1.0)
            self.freq[i] = f_target
            self.amp[i] = a_target
        return out
