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
import java.util.Set;

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

    @Test
    void parsesCruisingAddonSettings() throws IOException {
        String json = "{\"ducked_layers\":[\"coast/cruising\"],\"duck_depth\":0.85,\"mode_persistence_seconds\":0.2,"
            + "\"mode_blend_seconds\":0.25,\"mode_blend_curve\":\"smoothstep\",\"departure_speed_mps\":1.0}";
        PackSettings s = PackSettings.parse(new StringReader(json));
        assertEquals(Set.of("coast/cruising"), s.duckedLayers());
        assertEquals(0.85, s.duckDepth(), 0);
        assertEquals(0.2, s.modePersistenceSeconds(), 0);
        assertEquals(0.25, s.modeBlendSeconds(), 0);
        assertEquals(PackSettings.Shape.SMOOTHSTEP, s.modeBlendShape());
        assertEquals(1.0, s.departureSpeedMps(), 0);
        PackSettings none = PackSettings.parse(new StringReader("{}"));
        assertEquals(0, none.modePersistenceSeconds(), 0, "persistence is off unless a pack asks");
        assertEquals(0, none.modeBlendSeconds(), 0);
    }

    @Test
    void steadyModesAreSteadyPowerAndBrakeLayers() throws IOException {
        String csv = "layer,mode,speed_mps,pitch,volume\n"
            + "power_steady/v14_a,power_steady,12.5,1,0\npower_steady/v14_a,power_steady,14,1,0.7\n"
            + "brake_steady/v14_a,brake_steady,14,1,0.7\n"
            + "power/tone,power,14,1,0.7\n";
        Map<String, TractionPack.CurveEntry> curves = TractionPack.parseCurves(new StringReader(csv));
        assertEquals(TractionMode.POWER, curves.get("power_steady/v14_a").mode());
        assertTrue(curves.get("power_steady/v14_a").steady());
        assertEquals(TractionMode.BRAKE, curves.get("brake_steady/v14_a").mode());
        assertTrue(curves.get("brake_steady/v14_a").steady());
        assertFalse(curves.get("power/tone").steady());
        assertThrows(IOException.class, () -> TractionPack.parseCurves(new StringReader("layer,mode,speed_mps,pitch,volume\nx,coast_steady,1,1,1\n")));
    }

    @Test
    void parsesSteadyLoadSettings() throws IOException {
        String json = "{\"steady_blend_seconds\":1.5,\"steady_hold_seconds\":1.25,\"steady_enter_abs_acceleration_mps2\":0.08,"
            + "\"steady_speed_span_mps\":0.18,\"steady_exit_abs_acceleration_mps2\":0.15,\"steady_exit_hold_seconds\":0.15,"
            + "\"steady_min_speed_mps\":5.0,\"steady_max_speed_mps\":40.0,\"steady_status\":\"text\"}";
        PackSettings.Steady s = PackSettings.parse(new StringReader(json)).steady();
        assertEquals(new PackSettings.Steady(1.5, 1.25, 0.08, 0.18, 0.15, 0.15, 5, 40, 0.5), s);
    }

    @Test
    void settingsNamingAMissingLayerAreRejected() {
        Map<String, byte[]> files = new HashMap<>();
        files.put("curves.csv", "layer,mode,speed_mps,pitch,volume\npower/tone,power,0,1,1\n".getBytes(StandardCharsets.UTF_8));
        files.put("player_settings.json", "{\"ducked_layers\":[\"coast/cruising\"]}".getBytes(StandardCharsets.UTF_8));
        files.put("power/tone.wav", wav(1, 16, 1, 48000, new byte[16], false));
        IOException e = assertThrows(IOException.class, () -> TractionPack.load(path -> files.containsKey(path) ? new ByteArrayInputStream(files.get(path)) : null));
        assertTrue(e.getMessage().contains("coast/cruising"), e.getMessage());
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
