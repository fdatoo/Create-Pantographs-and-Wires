"""Python mirror of TractionDemand.java, for offline renders (render_onix.py)."""

CREATE_ACCELERATION = 0.0075  # Create's default train acceleration, blocks per tick squared


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
