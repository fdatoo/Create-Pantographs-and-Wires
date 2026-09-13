"""Python mirror of the client's WMATA playback logic, shared by build_wmata_ingame.py (golden test
values) and simulate_ingame.py (renders). It must stay in step with TractionDemand.java and
WmataTraction.java; WmataTractionTest pins the two together.
"""
import math

VOICES = ["STAGE_A", "STAGE_B", "UPPER_DIFFUSE", "BRAKE", "RIDGE", "BRIEF"]
SAMPLES = {
    "STAGE_A": "wmata_upper_line",
    "STAGE_B": "wmata_upper_line",
    "UPPER_DIFFUSE": "wmata_upper_diffuse",
    "BRAKE": "wmata_upper_line",
    "RIDGE": "wmata_ridge",
    "BRIEF": "wmata_brief",
}
# Stems: everything in the 2.4-2.9 kHz band, and the rising lower tone with the noise bed.
UPPER_STEM = ("STAGE_A", "STAGE_B", "UPPER_DIFFUSE", "BRAKE", "BRIEF")
LOWER_BODY_STEM = ("RIDGE", "NOISE")


class TractionDemand:
    """Mirror of TractionDemand.java."""
    ACCELERATION_SMOOTHING = 0.25
    DEAD_ZONE = 0.0008
    FULL_DEMAND = 0.004
    RISE = 0.15
    FALL = 0.06

    def __init__(self):
        self.previous_speed = 0.0
        self.has_previous_speed = False
        self.acceleration = 0.0
        self.power = 0.0
        self.brake = 0.0

    def update(self, speed):
        change = speed - self.previous_speed if self.has_previous_speed else 0.0
        self.previous_speed = speed
        self.has_previous_speed = True
        self.acceleration += (change - self.acceleration) * self.ACCELERATION_SMOOTHING
        self.power = self._approach(self.power, self.ramp(self.acceleration))
        self.brake = self._approach(self.brake, self.ramp(-self.acceleration))

    @classmethod
    def ramp(cls, acceleration):
        return min(1.0, max(0.0, (acceleration - cls.DEAD_ZONE) / (cls.FULL_DEMAND - cls.DEAD_ZONE)))

    @classmethod
    def _approach(cls, current, target):
        return current + (target - current) * (cls.RISE if target > current else cls.FALL)


def sample(table, steps, speed_fraction):
    """Mirror of WmataTraction.sample: linear interpolation over evenly spaced speed fractions."""
    x = min(1.0, max(0.0, speed_fraction)) * steps
    i = min(int(math.floor(x)), steps - 1)
    f = x - i
    return table[i] * (1 - f) + table[i + 1] * f


def volume(tables, voice, speed_fraction, power, brake):
    """Mirror of WmataTraction.volume."""
    i = VOICES.index(voice)
    steps = tables["steps"]
    p = min(1.0, max(0.0, power))
    b = min(1.0, max(0.0, brake))
    coast = tables["coast_gain"]
    powered = (coast + (1 - coast) * p) * sample(tables["powered_level"][i], steps, speed_fraction)
    return (1 - b) * powered + b * sample(tables["braking_level"][i], steps, speed_fraction)


def frequency(tables, voice, speed_fraction):
    return sample(tables["frequency"][VOICES.index(voice)], tables["steps"], speed_fraction)


# ---------------------------------------------------------------- test scenarios (speed per tick)
CREATE_ACCELERATION = 0.0075  # Create's default, blocks per tick squared
TOP_SPEED = 0.7


def departure():
    """A slow 10 s departure, so each stage can be heard, then top speed, then a stop."""
    speeds, phases = [0.0] * 10, {}
    start = len(speeds)
    speeds += [TOP_SPEED * k / 200 for k in range(1, 201)]
    phases["accelerating over 10 s"] = (start, len(speeds))
    start = len(speeds)
    speeds += [TOP_SPEED] * 80
    phases["steady at top speed"] = (start, len(speeds))
    start = len(speeds)
    v = TOP_SPEED
    while v > 0:
        v = max(0.0, v - CREATE_ACCELERATION)
        speeds.append(v)
    phases["braking to a stop"] = (start, len(speeds))
    return speeds + [0.0] * 20, phases


def gameplay():
    """Create's own rates: accelerate to top speed, hold it, coast, then brake to a stop."""
    speeds, phases = [0.0] * 10, {}
    v = 0.0
    start = len(speeds)
    while v < TOP_SPEED - 1e-12:
        v = min(TOP_SPEED, v + CREATE_ACCELERATION)
        speeds.append(v)
    phases["accelerating"] = (start, len(speeds))
    start = len(speeds)
    speeds += [v] * 100
    phases["steady speed"] = (start, len(speeds))
    start = len(speeds)
    for _ in range(100):
        v -= 0.0005  # drifting down, inside the dead zone
        speeds.append(v)
    phases["coasting"] = (start, len(speeds))
    start = len(speeds)
    while v > 0:
        v = max(0.0, v - CREATE_ACCELERATION)
        speeds.append(v)
    phases["braking to a stop"] = (start, len(speeds))
    return speeds + [0.0] * 20, phases
