package de.mrjulsen.paw.traction.pack;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class LayerAudioTest {
    private static final Executor NOW = Runnable::run;
    private static final long SECOND = 1_000_000_000L;

    @Test
    void decodesWhenWantedAndDropsAfterGoingUnwanted() {
        AtomicInteger decodes = new AtomicInteger();
        LayerAudio audio = LayerAudio.onDemand(new byte[] {1}, 3, 48000, bytes -> {
            decodes.incrementAndGet();
            return new float[] {0.1f, 0.2f, 0.3f};
        });
        assertTrue(audio.isOnDemand());
        assertNull(audio.samples());
        assertEquals(3, audio.frames());

        audio.want(0, NOW);
        assertArrayEquals(new float[] {0.1f, 0.2f, 0.3f}, audio.samples());
        audio.want(SECOND, NOW);
        assertEquals(1, decodes.get(), "decoded once while it stays");

        audio.releaseIfIdle(10 * SECOND, 30 * SECOND);
        assertNotNull(audio.samples());
        audio.releaseIfIdle(32 * SECOND, 30 * SECOND);
        assertNull(audio.samples());

        audio.want(40 * SECOND, NOW);
        assertEquals(2, decodes.get());
    }

    @Test
    void aDecodeOfTheWrongLengthLeavesTheLayerSilent() {
        LayerAudio audio = LayerAudio.onDemand(new byte[] {1}, 3, 48000, bytes -> new float[2]);
        audio.want(0, NOW);
        assertNull(audio.samples());
    }

    @Test
    void theMixerDecodesSteadyLayersOnlyNearTheirSpeedInTheirMode() {
        LayerCurve near14 = new LayerCurve(List.of(new double[] {12.5, 1, 0}, new double[] {14, 1, 0.5}, new double[] {16, 1, 0}));
        LayerCurve near28 = new LayerCurve(List.of(new double[] {26, 1, 0}, new double[] {28, 1, 0.5}, new double[] {32, 1, 0}));
        float[] loop = new float[4800];
        LayerAudio a14 = LayerAudio.onDemand(new byte[] {1}, loop.length, 48000, bytes -> loop.clone());
        LayerAudio a28 = LayerAudio.onDemand(new byte[] {1}, loop.length, 48000, bytes -> loop.clone());
        TractionPack pack = new TractionPack(List.of(
            new TractionPack.Layer("power_steady/v14_a", TractionMode.POWER, near14, a14, false, false, true),
            new TractionPack.Layer("power_steady/v28_a", TractionMode.POWER, near28, a28, false, false, true)
        ), PackSettings.defaults(), NOW);
        long[] clock = {0};
        TractionMixer mixer = new TractionMixer(pack, 48000, () -> clock[0]);
        float[] block = new float[2400];

        mixer.setState(14, TractionMode.COAST);
        mixer.render(block, block.length);
        assertNull(a14.samples(), "not while coasting");

        mixer.setState(14, TractionMode.POWER);
        mixer.render(block, block.length);
        assertNotNull(a14.samples());
        assertNull(a28.samples());

        mixer.setState(14, TractionMode.COAST);
        clock[0] = 31 * SECOND;
        mixer.render(block, block.length);
        assertNull(a14.samples(), "dropped after half a minute unwanted");
    }

    @Test
    void aPackPlaysOggBeforeWavAndHoldsSteadyLayersEncoded() throws IOException {
        byte[] ogg;
        try (InputStream in = LayerAudioTest.class.getResourceAsStream("/traction/tone440.ogg")) {
            ogg = in.readAllBytes();
        }
        Map<String, byte[]> files = new HashMap<>();
        files.put("curves.csv", ("layer,mode,speed_mps,pitch,volume\n"
            + "power/tone,power,0,1,1\npower/tone,power,40,1,1\n"
            + "power_steady/v14_a,power_steady,12.5,1,0\npower_steady/v14_a,power_steady,14,1,0.7\n").getBytes(StandardCharsets.UTF_8));
        files.put("power/tone.ogg", ogg);
        files.put("power/tone.wav", "not read".getBytes(StandardCharsets.US_ASCII));
        files.put("power_steady/v14_a.ogg", ogg);
        TractionPack pack = TractionPack.load(path -> files.containsKey(path) ? new ByteArrayInputStream(files.get(path)) : null, NOW);

        LayerAudio tone = pack.layers().get(0).audio();
        assertFalse(tone.isOnDemand());
        assertNotNull(tone.samples());
        assertEquals(12000, tone.frames());

        TractionPack.Layer steady = pack.layers().get(1);
        assertTrue(steady.steady());
        assertTrue(steady.audio().isOnDemand());
        assertNull(steady.audio().samples());
        assertEquals(12000, steady.audio().frames());
        steady.audio().want(0, NOW);
        assertEquals(12000, steady.audio().samples().length);
    }
}
