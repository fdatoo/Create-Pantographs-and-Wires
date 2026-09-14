package de.mrjulsen.paw.traction.pack;

import java.io.IOException;
import java.io.Reader;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * A pack's player settings (player_settings.json): mode envelope timings and shapes, and which layers
 * are mechanical, playing in every mode without mode gating. Missing keys fall back to defaults;
 * unknown keys are ignored.
 */
public record PackSettings(
    double powerOnSeconds, Shape powerOnShape,
    double powerOffSeconds, Shape powerOffShape,
    double brakeOnSeconds, Shape brakeOnShape,
    double brakeOffSeconds, Shape brakeOffShape,
    Set<String> continuousLayers,
    double mechanicalGain
) {
    public enum Shape {
        LINEAR,
        /** s(u) = 3u^2 - 2u^3 */
        SMOOTHSTEP
    }

    /**
     * Used for anything a pack leaves out. The pack format names no power onset curve or brake release,
     * so onset is linear and brake release matches power release.
     */
    public static PackSettings defaults() {
        return new PackSettings(0.06, Shape.LINEAR, 0.18, Shape.SMOOTHSTEP, 0.35, Shape.SMOOTHSTEP, 0.18, Shape.SMOOTHSTEP, Set.of(), 1.0);
    }

    public static PackSettings parse(Reader reader) throws IOException {
        JsonObject json;
        try {
            json = JsonParser.parseReader(reader).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("player settings are not a JSON object", e);
        }
        PackSettings d = defaults();
        double powerOff = number(json, "power_off_seconds", d.powerOffSeconds());
        Shape powerOffShape = shape(json, "power_off_curve", d.powerOffShape());
        Set<String> continuous = new LinkedHashSet<>();
        JsonElement layers = json.get("continuous_mechanical_layers");
        if (layers != null && layers.isJsonArray()) {
            JsonArray array = layers.getAsJsonArray();
            for (JsonElement layer : array) {
                continuous.add(layer.getAsString());
            }
        }
        return new PackSettings(
            number(json, "power_on_seconds", d.powerOnSeconds()), shape(json, "power_on_curve", d.powerOnShape()),
            powerOff, powerOffShape,
            number(json, "brake_on_seconds", d.brakeOnSeconds()), shape(json, "brake_on_curve", d.brakeOnShape()),
            number(json, "brake_off_seconds", powerOff), shape(json, "brake_off_curve", powerOffShape),
            Set.copyOf(continuous),
            number(json, "mechanical_gain", d.mechanicalGain())
        );
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
