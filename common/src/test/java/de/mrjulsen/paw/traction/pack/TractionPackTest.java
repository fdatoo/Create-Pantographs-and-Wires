package de.mrjulsen.paw.traction.pack;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class TractionPackTest {

    // ---------------------------------------------------------------- WAV
    static byte[] wav(int format, int bits, int channels, int rate, byte[] pcm, boolean extraChunk) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer header = ByteBuffer.allocate(44 + (extraChunk ? 12 : 0)).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + (extraChunk ? 12 : 0) + pcm.length).put("WAVE".getBytes(StandardCharsets.US_ASCII));
        header.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) format).putShort((short) channels).putInt(rate)
            .putInt(rate * channels * bits / 8).putShort((short) (channels * bits / 8)).putShort((short) bits);
        if (extraChunk) {
            header.put("LIST".getBytes(StandardCharsets.US_ASCII)).putInt(4).put("INFO".getBytes(StandardCharsets.US_ASCII));
        }
        header.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length);
        out.writeBytes(header.array());
        out.writeBytes(pcm);
        return out.toByteArray();
    }

    @Test
    void reads24BitPcmExactly() throws IOException {
        int[] values = {0, 4194304, -4194304, 8388607, -8388608};
        ByteBuffer pcm = ByteBuffer.allocate(values.length * 3);
        for (int v : values) {
            pcm.put((byte) v).put((byte) (v >> 8)).put((byte) (v >> 16));
        }
        WavFile file = WavFile.read(new ByteArrayInputStream(wav(1, 24, 1, 48000, pcm.array(), true)));
        assertEquals(48000, file.sampleRate());
        assertArrayEquals(new float[] {0f, 0.5f, -0.5f, 8388607 / 8388608f, -1f}, file.samples(), 1e-7f);
    }

    @Test
    void mixesStereo16BitToMono() throws IOException {
        ByteBuffer pcm = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        pcm.putShort((short) 16384).putShort((short) 0).putShort((short) -32768).putShort((short) -32768);
        WavFile file = WavFile.read(new ByteArrayInputStream(wav(1, 16, 2, 44100, pcm.array(), false)));
        assertArrayEquals(new float[] {0.25f, -1f}, file.samples(), 1e-7f);
    }

    @Test
    void readsFloatWav() throws IOException {
        ByteBuffer pcm = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putFloat(0.125f).putFloat(-0.75f);
        WavFile file = WavFile.read(new ByteArrayInputStream(wav(3, 32, 1, 48000, pcm.array(), false)));
        assertArrayEquals(new float[] {0.125f, -0.75f}, file.samples(), 0f);
    }

    @Test
    void rejectsNonWav() {
        assertThrows(IOException.class, () -> WavFile.read(new ByteArrayInputStream("not audio at all".getBytes(StandardCharsets.US_ASCII))));
    }

    // ---------------------------------------------------------------- curves
    @Test
    void curveIsSilentOutsideAndInterpolatesInside() {
        LayerCurve curve = new LayerCurve(List.of(new double[] {10, 1.5, 0.4}, new double[] {0, 1.0, 0.0}, new double[] {5, 1.0, 0.8}));
        double[] out = new double[2];
        assertFalse(curve.sample(-0.01, out));
        assertFalse(curve.sample(10.01, out));
        assertTrue(curve.sample(0, out));
        assertArrayEquals(new double[] {1.0, 0.0}, out, 1e-12);
        assertTrue(curve.sample(2.5, out));
        assertArrayEquals(new double[] {1.0, 0.4}, out, 1e-12);
        assertTrue(curve.sample(7.5, out));
        assertArrayEquals(new double[] {1.25, 0.6}, out, 1e-12);
        assertTrue(curve.sample(10, out));
        assertArrayEquals(new double[] {1.5, 0.4}, out, 1e-12);
    }

    @Test
    void parsesCurvesGroupedByLayerInCsvOrder() throws IOException {
        String csv = "layer,mode,speed_mps,pitch,volume\n"
            + "power/b,power,0,1,0\n\n"
            + "coast/a,coast,0,0.5,0.1\n"
            + "power/b,power,10,1.2,0.5\n";
        Map<String, TractionPack.CurveEntry> curves = TractionPack.parseCurves(new StringReader(csv));
        assertEquals(List.of("power/b", "coast/a"), List.copyOf(curves.keySet()));
        assertEquals(TractionMode.POWER, curves.get("power/b").mode());
        assertEquals(10, curves.get("power/b").curve().lastSpeed(), 0);
    }

    @Test
    void columnsMayComeInAnyOrder() throws IOException {
        String csv = "volume,pitch,speed_mps,mode,layer\n0.3,1.1,4,brake,brake/x\n";
        TractionPack.CurveEntry entry = TractionPack.parseCurves(new StringReader(csv)).get("brake/x");
        double[] out = new double[2];
        assertTrue(entry.curve().sample(4, out));
        assertArrayEquals(new double[] {1.1, 0.3}, out, 1e-12);
    }

    @Test
    void rejectsALayerInTwoModes() {
        String csv = "layer,mode,speed_mps,pitch,volume\nx,power,0,1,0\nx,brake,1,1,0\n";
        assertThrows(IOException.class, () -> TractionPack.parseCurves(new StringReader(csv)));
    }

    @Test
    void rejectsLayerNamesThatCannotBeResources() {
        String csv = "layer,mode,speed_mps,pitch,volume\nPower/Tone 1,power,0,1,0\n";
        assertThrows(IOException.class, () -> TractionPack.parseCurves(new StringReader(csv)));
    }

    // ---------------------------------------------------------------- settings
    @Test
    void parsesThePackSettingsAndFillsGaps() throws IOException {
        String json = "{\"continuous_mechanical_layers\":[\"coast/rolling_low\",\"coast/gear_high\"],\"mechanical_gain\":1,"
            + "\"power_on_seconds\":0.06,\"power_off_seconds\":0.18,\"power_off_curve\":\"smoothstep release\","
            + "\"brake_on_seconds\":0.35,\"brake_on_curve\":\"smoothstep attack\",\"unrelated\":true}";
        PackSettings s = PackSettings.parse(new StringReader(json));
        assertEquals(0.06, s.powerOnSeconds(), 0);
        assertEquals(PackSettings.Shape.LINEAR, s.powerOnShape());
        assertEquals(PackSettings.Shape.SMOOTHSTEP, s.powerOffShape());
        assertEquals(0.35, s.brakeOnSeconds(), 0);
        assertEquals(PackSettings.Shape.SMOOTHSTEP, s.brakeOnShape());
        assertEquals(0.18, s.brakeOffSeconds(), 0);
        assertEquals(PackSettings.Shape.SMOOTHSTEP, s.brakeOffShape());
        assertTrue(s.continuousLayers().contains("coast/gear_high"));
        assertEquals(1.0, s.mechanicalGain(), 0);
    }

    // ---------------------------------------------------------------- loading
    @Test
    void loadsAPackThroughAnOpener() throws IOException {
        Map<String, byte[]> files = new HashMap<>();
        files.put("curves.csv", "layer,mode,speed_mps,pitch,volume\npower/tone,power,0,1,1\npower/tone,power,40,1,1\n".getBytes(StandardCharsets.UTF_8));
        files.put("power/tone.wav", wav(1, 16, 1, 48000, new byte[16], false));
        TractionPack pack = TractionPack.load(path -> files.containsKey(path) ? new ByteArrayInputStream(files.get(path)) : null);
        assertEquals(1, pack.layers().size());
        assertEquals(PackSettings.defaults(), pack.settings());
        assertFalse(pack.layers().get(0).continuous());
    }

    @Test
    void missingLayerFileNamesTheLayer() {
        Map<String, byte[]> files = Map.of("curves.csv", "layer,mode,speed_mps,pitch,volume\npower/gone,power,0,1,1\n".getBytes(StandardCharsets.UTF_8));
        IOException e = assertThrows(IOException.class, () -> TractionPack.load(path -> files.containsKey(path) ? new ByteArrayInputStream(files.get(path)) : null));
        assertTrue(e.getMessage().contains("power/gone"), e.getMessage());
    }
}
