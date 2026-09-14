package de.mrjulsen.paw.traction;

import static de.mrjulsen.paw.traction.OnixTractionData.*;

/**
 * The WMATA 6000-series traction sound as a set of lines computed from the drive, not a recording.
 *
 * The 6000s run Alstom ONIX 2-level IGBT inverters feeding asynchronous motors. The motors radiate
 * the inverter's switching pattern: lines at multiples of the carrier frequency plus or minus
 * multiples of the motor supply frequency, heard directly and through the magnetic force they make
 * with the fundamental flux (one supply frequency either side). The carrier steps between measured
 * values as the supply frequency rises, on separate schedules while powering and braking, and the
 * modulation index follows a measured curve. A second family of lines is locked to rotor speed.
 * Levels between inverter lines follow PWM theory for a naturally sampled 2-level inverter, weighted
 * by current and one acoustic response. With no traction demand the inverter is gated off.
 *
 * Measurements and the reasoning behind every constant are in tools/sound/wmata; onix_model.py is the
 * reference this mirrors, and OnixTractionTest pins the two together.
 */
public final class OnixTraction {
    /** Lines per state: three per electrical line (direct, and force lines either side), then the rotor family. */
    public static final int INVERTER_LINE_COUNT = ELECTRICAL_M.length * 3;
    public static final int LINE_COUNT = INVERTER_LINE_COUNT + ROTOR_HARMONICS_DB.length;

    private OnixTraction() {}

    /**
     * Fills every line's frequency (Hz) and amplitude for a train's state. Line order never changes, so
     * each index can drive one oscillator.
     *
     * @param speed blocks per tick
     * @param power traction demand, 0 to 1
     * @param brake braking demand, 0 to 1
     */
    public static void lines(double speed, double power, double brake, double[] frequencies, double[] amplitudes) {
        double demand = Math.max(power, brake);
        boolean braking = brake > power;
        double rotor = Math.abs(speed) * MPS_PER_BLOCK_PER_TICK * F1_PER_MPS;
        double f1 = Math.max(0.0, rotor + (braking ? -SLIP_HZ * brake : SLIP_HZ * power));
        double gate = smoothstep(0.02, 0.2, demand) * (0.55 + 0.45 * demand) * smoothstep(0.5, 3.0, f1);
        double fc = carrier(f1, braking);
        double index = modulation(f1);

        int i = 0;
        for (int line = 0; line < ELECTRICAL_M.length; line++) {
            int m = ELECTRICAL_M[line];
            int n = ELECTRICAL_N[line];
            double f = m * fc + n * f1;
            double electrical = 4 / (m * Math.PI) * Math.abs(bessel(Math.abs(n), m * Math.PI * index / 2)) * Math.abs(Math.sin(n * Math.PI / 3)) * 2 / Math.sqrt(3);
            double level = PWM_LEVEL * gate * electrical * 1000.0 / f;
            frequencies[i] = f;
            amplitudes[i++] = level * DIRECT_WEIGHT * response(f);
            frequencies[i] = f - f1;
            amplitudes[i++] = level * FORCE_WEIGHT * response(f - f1);
            frequencies[i] = f + f1;
            amplitudes[i++] = level * FORCE_WEIGHT * response(f + f1);
        }

        double rotorGain = ROTOR_LEVEL * (ROTOR_COAST + (1 - ROTOR_COAST) * demand) * smoothstep(2.0, 8.0, rotor);
        for (int k = 0; k < ROTOR_HARMONICS_DB.length; k++) {
            double f = (k + 1) * ROTOR_ORDER * rotor;
            double shape = smoothstep(ROTOR_LOW_FADE_FROM_HZ, ROTOR_LOW_FADE_TO_HZ, f) / (1 + square(f / ROTOR_ROLLOFF_HZ));
            frequencies[i] = f;
            amplitudes[i++] = f < MAX_LINE_HZ ? rotorGain * Math.pow(10, ROTOR_HARMONICS_DB[k] / 20) * shape : 0.0;
        }
    }

    /** The inverter's carrier frequency for a motor supply frequency. */
    public static double carrier(double f1, boolean braking) {
        double[] limits = braking ? BRAKE_LIMITS : POWER_LIMITS;
        double[] carriers = braking ? BRAKE_CARRIERS : POWER_CARRIERS;
        for (int i = 0; i < limits.length; i++) {
            if (f1 < limits[i]) {
                return carriers[i];
            }
        }
        return carriers[carriers.length - 1];
    }

    static double modulation(double f1) {
        if (f1 <= MODULATION_F1[0]) {
            return MODULATION_INDEX[0];
        }
        for (int i = 1; i < MODULATION_F1.length; i++) {
            if (f1 <= MODULATION_F1[i]) {
                return MODULATION_INDEX[i - 1] + (MODULATION_INDEX[i] - MODULATION_INDEX[i - 1]) * (f1 - MODULATION_F1[i - 1]) / (MODULATION_F1[i] - MODULATION_F1[i - 1]);
            }
        }
        return MODULATION_INDEX[MODULATION_INDEX.length - 1];
    }

    /** Bessel function of the first kind for integer order n >= 0, by its power series. */
    static double bessel(int n, double x) {
        double half = x / 2;
        double factorial = 1;
        for (int j = 2; j <= n; j++) {
            factorial *= j;
        }
        double term = Math.pow(half, n) / factorial;
        double total = 0.0;
        for (int k = 0; k < 40; k++) {
            total += term;
            term *= -(half * half) / ((k + 1.0) * (k + 1.0 + n));
        }
        return total;
    }

    private static double response(double f) {
        return Math.pow(10, RESPONSE_GAIN_DB / 20 / (1 + square((f - RESPONSE_CENTRE_HZ) / RESPONSE_WIDTH_HZ)));
    }

    private static double smoothstep(double edge0, double edge1, double x) {
        double t = Math.min(1.0, Math.max(0.0, (x - edge0) / (edge1 - edge0)));
        return t * t * (3 - 2 * t);
    }

    private static double square(double x) {
        return x * x;
    }
}
