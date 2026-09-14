package de.mrjulsen.paw.traction.pack;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Renders a pack's documented preview journeys through the real mixer, for comparing by ear or by
 * analysis with the pack author's own previews. Runs only when PAW_TRACTION_PACK_DIR points at a pack
 * folder; writes to PAW_TRACTION_RENDER_OUT (default build/traction-renders).
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
        });
        render(loaded, out.resolve("render_14mps.wav"), 14, 14, 5, 14);
        render(loaded, out.resolve("render_40mps.wav"), 40, 32, 5, 32);
        cruise(loaded, out.resolve("render_cruising_14mps.wav"), 14, 8);
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
