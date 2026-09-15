package de.mrjulsen.paw.traction.pack;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Renders a pack's documented preview journeys through the real mixer, for comparing by ear or by
 * analysis with the pack author's own previews. Runs only when PAW_TRACTION_PACK_DIR points at a pack
 * folder; writes to PAW_TRACTION_RENDER_OUT (default build/traction-renders).
 *
 * PAW_STEADY_PREVIEW_DIR adds the steady-load addon's timeline previews (CSV per game tick);
 * PAW_PREVIEW_REPORT adds previews described by a report of speed and mode every 0.25 s, as the BART pack has.
 */
@EnabledIfEnvironmentVariable(named = "PAW_TRACTION_PACK_DIR", matches = ".+")
class TractionPackPreviewRenderTest {
    private static final int RATE = 48000;
    private static final int BLOCK = 2400;

    @Test
    void renderPreviewJourneys() throws IOException {
        Path pack = Path.of(System.getenv("PAW_TRACTION_PACK_DIR"));
        Path out = Path.of(System.getenv().getOrDefault("PAW_TRACTION_RENDER_OUT", "build/traction-renders"));
        Files.createDirectories(out);
        TractionPack loaded = TractionPack.load(path -> {
            Path file = pack.resolve(path);
            return Files.exists(file) ? Files.newInputStream(file) : null;
        }, Runnable::run);
        render(loaded, out.resolve("render_14mps.wav"), 14, 14, 5, 14);
        render(loaded, out.resolve("render_40mps.wav"), 40, 32, 5, 32);
        cruise(loaded, out.resolve("render_cruising_14mps.wav"), 14, 8);

        String steadyPreviews = System.getenv("PAW_STEADY_PREVIEW_DIR");
        if (steadyPreviews != null) {
            for (String name : List.of("climb_14", "descent_14", "cap_lifts")) {
                timeline(loaded, Path.of(steadyPreviews).resolve(name + "_timeline.csv"), out.resolve("render_" + name + ".wav"),
                    out.resolve("render_" + name + "_timeline.csv"));
            }
        }

        String report = System.getenv("PAW_PREVIEW_REPORT");
        if (report != null) {
            JsonObject previews = JsonParser.parseString(Files.readString(Path.of(report))).getAsJsonObject();
            for (String name : previews.keySet()) {
                reported(loaded, previews.getAsJsonObject(name), out.resolve("render_" + name + ".wav"), out.resolve("render_" + name + "_timeline.csv"));
            }
        }
    }

    /** Replays a steady-load addon timeline: one CSV row per game tick with speed and the classified mode. */
    private static void timeline(TractionPack pack, Path timeline, Path file, Path trace) throws IOException {
        List<String> rows = Files.readAllLines(timeline);
        double[] speeds = new double[rows.size() - 1];
        TractionMode[] modes = new TractionMode[rows.size() - 1];
        for (int i = 1; i < rows.size(); i++) {
            String[] cells = rows.get(i).split(",");
            speeds[i - 1] = Double.parseDouble(cells[1]);
            int classified = (int) Double.parseDouble(cells[2]);
            modes[i - 1] = classified > 0 ? TractionMode.POWER : classified < 0 ? TractionMode.BRAKE : TractionMode.COAST;
        }
        replay(pack, speeds, modes, file, trace);
    }

    /**
     * Replays a preview from a report logged every 0.25 s as [time, speed, mode letter C/P/B, ...]: speed is
     * interpolated to game ticks and the mode held from the latest row.
     */
    private static void reported(TractionPack pack, JsonObject preview, Path file, Path trace) throws IOException {
        JsonArray log = preview.getAsJsonArray("log_every_0.25s");
        int ticks = (int) Math.round(preview.get("seconds").getAsDouble() * 20);
        double[] speeds = new double[ticks];
        TractionMode[] modes = new TractionMode[ticks];
        for (int tick = 0; tick < ticks; tick++) {
            double time = tick / 20.0;
            int row = Math.min(log.size() - 1, (int) Math.floor(time / 0.25 + 1e-9));
            JsonArray at = log.get(row).getAsJsonArray();
            JsonArray next = log.get(Math.min(log.size() - 1, row + 1)).getAsJsonArray();
            double t0 = at.get(0).getAsDouble();
            double t1 = next.get(0).getAsDouble();
            double u = t1 > t0 ? Math.min(1, (time - t0) / (t1 - t0)) : 0;
            speeds[tick] = at.get(1).getAsDouble() + (next.get(1).getAsDouble() - at.get(1).getAsDouble()) * u;
            modes[tick] = switch (at.get(2).getAsString()) {
                case "P" -> TractionMode.POWER;
                case "B" -> TractionMode.BRAKE;
                default -> TractionMode.COAST;
            };
        }
        replay(pack, speeds, modes, file, trace);
    }

    /** Plays one game tick per entry, letting the real steady detector decide steady load, and writes what the mixer did. */
    private static void replay(TractionPack pack, double[] speeds, TractionMode[] modes, Path file, Path trace) throws IOException {
        TractionMixer mixer = new TractionMixer(pack, RATE);
        SteadyLoadDetector steady = new SteadyLoadDetector();
        steady.configure(pack.settings().steady());
        float[] all = new float[speeds.length * BLOCK];
        float[] block = new float[BLOCK];
        StringBuilder csv = new StringBuilder("time_s,speed_mps,mode,power,brake,steady_power,steady_brake\n");
        TractionMode previous = TractionMode.COAST;
        double previousSpeed = 0;
        boolean slopeDriven = false;
        for (int i = 0; i < speeds.length; i++) {
            double speed = speeds[i];
            TractionMode mode = modes[i];
            if (mode != previous) {
                // A load that arrives while the speed holds is the grade's doing, as on the previews' climbs.
                slopeDriven = Math.abs(speed - previousSpeed) < 1e-6 && speed > 0;
                previous = mode;
            }
            previousSpeed = speed;
            steady.update(speed, mode);
            mixer.setState(speed, mode, slopeDriven, steady.powerTarget(), steady.brakeTarget());
            mixer.render(block, BLOCK);
            System.arraycopy(block, 0, all, i * BLOCK, BLOCK);
            TractionMixer.Diagnostics d = mixer.diagnostics(0);
            csv.append(String.format(Locale.ROOT, "%.2f,%.3f,%s,%.5f,%.5f,%.5f,%.5f%n", i / 20.0, speed, mode, d.power(), d.brake(),
                d.steadyPower(), d.steadyBrake()));
        }
        writeFloatWav(file, all);
        Files.writeString(trace, csv);
    }

    /** Holds a steady speed with no traction demand, as the cruising addon's preview does. */
    private static void cruise(TractionPack pack, Path file, double speed, double seconds) throws IOException {
        TractionMixer mixer = new TractionMixer(pack, RATE);
        int blocks = (int) Math.round(seconds * RATE / BLOCK);
        float[] all = new float[blocks * BLOCK];
        float[] block = new float[BLOCK];
        for (int b = 0; b < blocks; b++) {
            mixer.setState(speed, TractionMode.COAST);
            mixer.render(block, BLOCK);
            System.arraycopy(block, 0, all, b * BLOCK, BLOCK);
        }
        writeFloatWav(file, all);
    }

    /** Accelerates to top speed over accelerate seconds, coasts, then brakes to rest over brake seconds. */
    private static void render(TractionPack pack, Path file, double top, double accelerate, double coast, double brake) throws IOException {
        TractionMixer mixer = new TractionMixer(pack, RATE);
        int blocks = (int) Math.round((accelerate + coast + brake) * RATE / BLOCK);
        float[] all = new float[blocks * BLOCK];
        float[] block = new float[BLOCK];
        for (int b = 0; b < blocks; b++) {
            double start = b * BLOCK / (double) RATE;
            double end = (b + 1) * BLOCK / (double) RATE;
            TractionMode mode = start < accelerate ? TractionMode.POWER : start < accelerate + coast ? TractionMode.COAST : TractionMode.BRAKE;
            double speed = end <= accelerate ? top * end / accelerate
                : end <= accelerate + coast ? top
                : Math.max(0, top * (1 - (end - accelerate - coast) / brake));
            mixer.setState(speed, mode);
            mixer.render(block, BLOCK);
            System.arraycopy(block, 0, all, b * BLOCK, BLOCK);
        }
        writeFloatWav(file, all);
    }

    private static void writeFloatWav(Path file, float[] samples) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(44 + samples.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + samples.length * 4).put("WAVE".getBytes(StandardCharsets.US_ASCII));
        buffer.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 3).putShort((short) 1).putInt(RATE).putInt(RATE * 4)
            .putShort((short) 4).putShort((short) 32);
        buffer.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(samples.length * 4);
        for (float s : samples) {
            buffer.putFloat(s);
        }
        Files.write(file, buffer.array());
    }
}
