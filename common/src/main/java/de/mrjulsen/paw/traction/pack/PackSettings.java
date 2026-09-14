package de.mrjulsen.paw.traction.pack;

import java.io.IOException;
import java.io.Reader;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * A pack's player settings (player_settings.json): mode envelope timings and shapes, which layers
 * are mechanical (playing in every mode without mode gating), which are ducked under traction, how mode
 * changes behave while moving, and when steady-load layers take over. Missing keys fall back to defaults;
 * unknown keys are ignored.
 *
 * @param duckedLayers           layers that play in every mode at curve volume times
 *                               {@code 1 - duckDepth * max(power gain, brake gain)}
 * @param modePersistenceSeconds while moving, how long a new mode must hold before it takes effect; 0 for at once
 * @param modeBlendSeconds       while moving, how long every mode envelope takes to reach its new gain;
 *                               0 to use the onset and release timings everywhere
 * @param departureSpeedMps      below this speed a train counts as departing from rest: mode changes take
 *                               effect at once, with the onset and release timings
 * @param slopePowerBlendSeconds while moving, how long power brought in by a climb (not by speeding up)
 *                               takes to swell in; 0 to use modeBlendSeconds
 * @param modeEndPersistenceSeconds while moving, how long the end of demand must hold before cruising takes over;
 *                               0 to use modePersistenceSeconds
 * @param modePowerPersistenceSeconds while moving, how long powering must hold before it takes effect;
 *                               0 to use modePersistenceSeconds
 * @param steady                 when a power or brake load has settled at a constant speed, and how the
 *                               steady-load layers take over from the acceleration and braking layers
 */
public record PackSettings(
    double powerOnSeconds, Shape powerOnShape,
    double powerOffSeconds, Shape powerOffShape,
    double brakeOnSeconds, Shape brakeOnShape,
    double brakeOffSeconds, Shape brakeOffShape,
    Set<String> continuousLayers,
    double mechanicalGain,
    Set<String> duckedLayers,
    double duckDepth,
    double modePersistenceSeconds,
    double modeBlendSeconds,
    Shape modeBlendShape,
    double departureSpeedMps,
    double slopePowerBlendSeconds,
    double modeEndPersistenceSeconds,
    double modePowerPersistenceSeconds,
    Steady steady
) {
    public enum Shape {
        LINEAR,
        /** s(u) = 3u^2 - 2u^3 */
        SMOOTHSTEP
    }

    /**
     * Steady load, from the steady-load addon's rules.
     *
     * @param blendSeconds             smoothstep blend of a family's steady fraction, entering and leaving
     * @param holdSeconds              how long a load must qualify before its steady fraction targets 1
     * @param enterAbsAccelerationMps2 largest actual acceleration that qualifies
     * @param speedSpanMps             largest spread of speed over the hold time that qualifies
     * @param exitAbsAccelerationMps2  actual acceleration that, held for exitHoldSeconds, ends steady load
     * @param exitHoldSeconds          how long that acceleration must last
     * @param minSpeedMps              lowest speed at which steady load applies (inclusive)
     * @param maxSpeedMps              highest speed at which steady load applies (inclusive)
     */
    public record Steady(double blendSeconds, double holdSeconds, double enterAbsAccelerationMps2, double speedSpanMps,
        double exitAbsAccelerationMps2, double exitHoldSeconds, double minSpeedMps, double maxSpeedMps) {

        public static Steady defaults() {
            return new Steady(1.5, 1.25, 0.08, 0.18, 0.15, 0.15, 5, 40);
        }
    }

    /**
     * Used for anything a pack leaves out. The pack format names no power onset curve or brake release,
     * so onset is linear and brake release matches power release. Mode persistence and blending are off
     * unless a pack asks for them.
     */
    public static PackSettings defaults() {
        return new PackSettings(0.06, Shape.LINEAR, 0.18, Shape.SMOOTHSTEP, 0.35, Shape.SMOOTHSTEP, 0.18, Shape.SMOOTHSTEP, Set.of(), 1.0,
            Set.of(), 0.85, 0, 0, Shape.SMOOTHSTEP, 1.0, 0, 0, 0, Steady.defaults());
    }

    public static PackSettings parse(Reader reader) throws IOException {
        JsonObject json;
        try {
            json = JsonParser.parseReader(reader).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("player settings are not a JSON object", e);
        }
        PackSettings d = defaults();
        Steady ds = d.steady();
        double powerOff = number(json, "power_off_seconds", d.powerOffSeconds());
        Shape powerOffShape = shape(json, "power_off_curve", d.powerOffShape());
        return new PackSettings(
            number(json, "power_on_seconds", d.powerOnSeconds()), shape(json, "power_on_curve", d.powerOnShape()),
            powerOff, powerOffShape,
            number(json, "brake_on_seconds", d.brakeOnSeconds()), shape(json, "brake_on_curve", d.brakeOnShape()),
            number(json, "brake_off_seconds", powerOff), shape(json, "brake_off_curve", powerOffShape),
            names(json, "continuous_mechanical_layers"),
            number(json, "mechanical_gain", d.mechanicalGain()),
            names(json, "ducked_layers"),
            number(json, "duck_depth", d.duckDepth()),
            number(json, "mode_persistence_seconds", d.modePersistenceSeconds()),
            number(json, "mode_blend_seconds", d.modeBlendSeconds()),
            shape(json, "mode_blend_curve", d.modeBlendShape()),
            number(json, "departure_speed_mps", d.departureSpeedMps()),
            number(json, "slope_power_blend_seconds", d.slopePowerBlendSeconds()),
            number(json, "mode_end_persistence_seconds", d.modeEndPersistenceSeconds()),
            number(json, "mode_power_persistence_seconds", d.modePowerPersistenceSeconds()),
            new Steady(
                number(json, "steady_blend_seconds", ds.blendSeconds()),
                number(json, "steady_hold_seconds", ds.holdSeconds()),
                number(json, "steady_enter_abs_acceleration_mps2", ds.enterAbsAccelerationMps2()),
                number(json, "steady_speed_span_mps", ds.speedSpanMps()),
                number(json, "steady_exit_abs_acceleration_mps2", ds.exitAbsAccelerationMps2()),
                number(json, "steady_exit_hold_seconds", ds.exitHoldSeconds()),
                number(json, "steady_min_speed_mps", ds.minSpeedMps()),
                number(json, "steady_max_speed_mps", ds.maxSpeedMps()))
        );
    }

    private static Set<String> names(JsonObject json, String key) {
        Set<String> names = new LinkedHashSet<>();
        JsonElement value = json.get(key);
        if (value != null && value.isJsonArray()) {
            for (JsonElement name : value.getAsJsonArray()) {
                names.add(name.getAsString());
            }
        }
        return Set.copyOf(names);
    }

    private static double number(JsonObject json, String key, double fallback) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() ? value.getAsDouble() : fallback;
    }

    private static Shape shape(JsonObject json, String key, Shape fallback) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonPrimitive()) {
            return fallback;
        }
        String text = value.getAsString().toLowerCase(Locale.ROOT);
        if (text.contains("smoothstep")) {
            return Shape.SMOOTHSTEP;
        }
        return text.contains("linear") ? Shape.LINEAR : fallback;
    }
}
