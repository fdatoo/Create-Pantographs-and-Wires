package de.mrjulsen.paw.traction.pack;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

/**
 * A traction sound pack: looping layers, each with a pitch and volume curve against speed in one mode,
 * plus player settings. The layout is the one agreed for hand-made packs:
 *
 * <pre>
 * curves.csv            layer,mode,speed_mps,pitch,volume   (layer names omit .wav)
 * player_settings.json  optional envelope timings and mechanical layers (see PackSettings)
 * power/*.wav, brake/*.wav, coast/*.wav   periodic loops, any sample rate, mono or mixed to mono
 * </pre>
 */
public final class TractionPack {
    public static final String CURVES = "curves.csv";
    public static final String SETTINGS = "player_settings.json";

    private static final Pattern LAYER_NAME = Pattern.compile("[a-z0-9_.-]+(/[a-z0-9_.-]+)*");

    /** Opens a file by its path inside the pack, or returns null when the pack has no such file. */
    @FunctionalInterface
    public interface Opener {
        @Nullable
        InputStream open(String path) throws IOException;
    }

    public record Layer(String name, TractionMode mode, LayerCurve curve, float[] samples, int sampleRate, boolean continuous) {}

    public record CurveEntry(TractionMode mode, LayerCurve curve) {}

    private final List<Layer> layers;
    private final PackSettings settings;

    public TractionPack(List<Layer> layers, PackSettings settings) {
        this.layers = List.copyOf(layers);
        this.settings = settings;
    }

    public List<Layer> layers() {
        return layers;
    }

    public PackSettings settings() {
        return settings;
    }

    public static TractionPack load(Opener opener) throws IOException {
        Map<String, CurveEntry> curves;
        try (InputStream in = require(opener, CURVES)) {
            curves = parseCurves(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        PackSettings settings = PackSettings.defaults();
        try (InputStream in = opener.open(SETTINGS)) {
            if (in != null) {
                settings = PackSettings.parse(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        }
        List<Layer> layers = new ArrayList<>();
        for (Map.Entry<String, CurveEntry> entry : curves.entrySet()) {
            String name = entry.getKey();
            WavFile wav;
            try (InputStream in = require(opener, name + ".wav")) {
                wav = WavFile.read(in);
            } catch (IOException e) {
                throw new IOException("layer " + name + ": " + e.getMessage(), e);
            }
            if (wav.samples().length < 4) {
                throw new IOException("layer " + name + " is too short to loop");
            }
            layers.add(new Layer(name, entry.getValue().mode(), entry.getValue().curve(), wav.samples(), wav.sampleRate(),
                settings.continuousLayers().contains(name)));
        }
        return new TractionPack(layers, settings);
    }

    /** Parses curves.csv into one curve per layer, in the order layers first appear. */
    public static Map<String, CurveEntry> parseCurves(Reader reader) throws IOException {
        BufferedReader lines = new BufferedReader(reader);
        String header = null;
        int lineNumber = 0;
        while (header == null) {
            String line = lines.readLine();
            lineNumber++;
            if (line == null) {
                throw new IOException("curves.csv is empty");
            }
            if (!line.isBlank()) {
                header = line;
            }
        }
        List<String> columns = new ArrayList<>();
        for (String column : header.split(",", -1)) {
            columns.add(column.trim().replace("﻿", ""));
        }
        int layerColumn = column(columns, "layer");
        int modeColumn = column(columns, "mode");
        int speedColumn = column(columns, "speed_mps");
        int pitchColumn = column(columns, "pitch");
        int volumeColumn = column(columns, "volume");

        Map<String, TractionMode> modes = new LinkedHashMap<>();
        Map<String, List<double[]>> points = new LinkedHashMap<>();
        String line;
        while ((line = lines.readLine()) != null) {
            lineNumber++;
            if (line.isBlank()) {
                continue;
            }
            String[] cells = line.split(",", -1);
            if (cells.length < columns.size()) {
                throw new IOException("curves.csv line " + lineNumber + ": expected " + columns.size() + " columns");
            }
            String name = cells[layerColumn].trim();
            if (!LAYER_NAME.matcher(name).matches()) {
                throw new IOException("curves.csv line " + lineNumber + ": invalid layer name '" + name + "'");
            }
            TractionMode mode;
            double[] row;
            try {
                mode = TractionMode.fromCsv(cells[modeColumn].trim());
                row = new double[] {
                    Double.parseDouble(cells[speedColumn].trim()),
                    Double.parseDouble(cells[pitchColumn].trim()),
                    Double.parseDouble(cells[volumeColumn].trim())
                };
            } catch (IllegalArgumentException e) {
                throw new IOException("curves.csv line " + lineNumber + ": " + e.getMessage(), e);
            }
            TractionMode previous = modes.putIfAbsent(name, mode);
            if (previous != null && previous != mode) {
                throw new IOException("curves.csv line " + lineNumber + ": layer " + name + " appears in both " + previous.csvName() + " and " + mode.csvName());
            }
            points.computeIfAbsent(name, key -> new ArrayList<>()).add(row);
        }
        Map<String, CurveEntry> curves = new LinkedHashMap<>();
        for (Map.Entry<String, List<double[]>> entry : points.entrySet()) {
            curves.put(entry.getKey(), new CurveEntry(modes.get(entry.getKey()), new LayerCurve(entry.getValue())));
        }
        return curves;
    }

    private static int column(List<String> columns, String name) throws IOException {
        int index = columns.indexOf(name);
        if (index < 0) {
            throw new IOException("curves.csv has no '" + name + "' column");
        }
        return index;
    }

    private static InputStream require(Opener opener, String path) throws IOException {
        InputStream in = opener.open(path);
        if (in == null) {
            throw new IOException("missing " + path);
        }
        return in;
    }
}
