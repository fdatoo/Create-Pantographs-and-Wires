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
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

/**
 * A traction sound pack: looping layers, each with a pitch and volume curve against speed in one mode,
 * plus player settings. The layout is the one agreed for hand-made packs:
 *
 * <pre>
 * curves.csv            layer,mode,speed_mps,pitch,volume   (layer names omit the file extension)
 * player_settings.json  optional envelope timings, mechanical and ducked layers, steady load (see PackSettings)
 * power/, brake/, coast/, power_steady/, brake_steady/
 *                       periodic loops as .ogg (Ogg Vorbis) or .wav, any sample rate, mono or mixed to mono
 * </pre>
 *
 * Packs are authored as WAV and shipped as Vorbis (scripts/encode-traction-pack.sh); a layer with both
 * files plays the .ogg. Modes are power, brake and coast; power_steady and brake_steady mark a power or
 * brake layer that plays when that load has settled at a constant speed. Steady-load layers in Vorbis are
 * decoded only while a train is near their speed (see LayerAudio).
 */
public final class TractionPack {
    public static final String CURVES = "curves.csv";
    public static final String SETTINGS = "player_settings.json";

    private static final Pattern LAYER_NAME = Pattern.compile("[a-z0-9_.-]+(/[a-z0-9_.-]+)*");
    private static final String STEADY_SUFFIX = "_steady";
    private static final ExecutorService DECODER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "PAW traction decoder");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });

    /** Opens a file by its path inside the pack, or returns null when the pack has no such file. */
    @FunctionalInterface
    public interface Opener {
        @Nullable
        InputStream open(String path) throws IOException;
    }

    /**
     * @param steady whether this power or brake layer is a steady-load layer rather than part of the sweep
     */
    public record Layer(String name, TractionMode mode, LayerCurve curve, LayerAudio audio, boolean continuous, boolean ducked, boolean steady) {
        public Layer(String name, TractionMode mode, LayerCurve curve, float[] samples, int sampleRate, boolean continuous) {
            this(name, mode, curve, LayerAudio.decoded(samples, sampleRate), continuous, false, false);
        }

        public Layer(String name, TractionMode mode, LayerCurve curve, float[] samples, int sampleRate, boolean continuous, boolean ducked) {
            this(name, mode, curve, LayerAudio.decoded(samples, sampleRate), continuous, ducked, false);
        }

        public Layer(String name, TractionMode mode, LayerCurve curve, float[] samples, int sampleRate, boolean continuous, boolean ducked, boolean steady) {
            this(name, mode, curve, LayerAudio.decoded(samples, sampleRate), continuous, ducked, steady);
        }
    }

    public record CurveEntry(TractionMode mode, boolean steady, LayerCurve curve) {}

    private final List<Layer> layers;
    private final PackSettings settings;
    private final Executor decoder;

    public TractionPack(List<Layer> layers, PackSettings settings) {
        this(layers, settings, DECODER);
    }

    /** @param decoder where on-demand layers are decoded */
    public TractionPack(List<Layer> layers, PackSettings settings, Executor decoder) {
        this.layers = List.copyOf(layers);
        this.settings = settings;
        this.decoder = decoder;
    }

    public List<Layer> layers() {
        return layers;
    }

    public PackSettings settings() {
        return settings;
    }

    public Executor decoder() {
        return decoder;
    }

    public static TractionPack load(Opener opener) throws IOException {
        return load(opener, DECODER);
    }

    public static TractionPack load(Opener opener, Executor decoder) throws IOException {
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
        for (String named : settings.continuousLayers()) {
            if (!curves.containsKey(named)) {
                throw new IOException("player_settings.json names layer " + named + ", which curves.csv doesn't have");
            }
        }
        for (String named : settings.duckedLayers()) {
            if (!curves.containsKey(named)) {
                throw new IOException("player_settings.json names layer " + named + ", which curves.csv doesn't have");
            }
        }
        List<Layer> layers = new ArrayList<>();
        for (Map.Entry<String, CurveEntry> entry : curves.entrySet()) {
            String name = entry.getKey();
            LayerAudio audio;
            try {
                audio = readAudio(opener, name, entry.getValue().steady());
            } catch (IOException e) {
                throw new IOException("layer " + name + ": " + e.getMessage(), e);
            }
            if (audio.frames() < 4) {
                throw new IOException("layer " + name + " is too short to loop");
            }
            layers.add(new Layer(name, entry.getValue().mode(), entry.getValue().curve(), audio,
                settings.continuousLayers().contains(name), settings.duckedLayers().contains(name), entry.getValue().steady()));
        }
        return new TractionPack(layers, settings, decoder);
    }

    private static LayerAudio readAudio(Opener opener, String name, boolean onDemand) throws IOException {
        try (InputStream in = opener.open(name + ".ogg")) {
            if (in != null) {
                byte[] data = in.readAllBytes();
                if (onDemand) {
                    OggFile.Info info = OggFile.info(data);
                    return LayerAudio.onDemand(data, info.frames(), info.sampleRate(), encoded -> OggFile.decode(encoded).samples());
                }
                WavFile decoded = OggFile.decode(data);
                return LayerAudio.decoded(decoded.samples(), decoded.sampleRate());
            }
        }
        try (InputStream in = opener.open(name + ".wav")) {
            if (in == null) {
                throw new IOException("missing " + name + ".ogg or " + name + ".wav");
            }
            WavFile wav = WavFile.read(in);
            return LayerAudio.decoded(wav.samples(), wav.sampleRate());
        }
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

        Map<String, String> modeNames = new LinkedHashMap<>();
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
            String modeName = cells[modeColumn].trim();
            double[] row;
            try {
                modeOf(modeName);
                row = new double[] {
                    Double.parseDouble(cells[speedColumn].trim()),
                    Double.parseDouble(cells[pitchColumn].trim()),
                    Double.parseDouble(cells[volumeColumn].trim())
                };
            } catch (IllegalArgumentException e) {
                throw new IOException("curves.csv line " + lineNumber + ": " + e.getMessage(), e);
            }
            String previous = modeNames.putIfAbsent(name, modeName);
            if (previous != null && !previous.equals(modeName)) {
                throw new IOException("curves.csv line " + lineNumber + ": layer " + name + " appears in both " + previous + " and " + modeName);
            }
            points.computeIfAbsent(name, key -> new ArrayList<>()).add(row);
        }
        Map<String, CurveEntry> curves = new LinkedHashMap<>();
        for (Map.Entry<String, List<double[]>> entry : points.entrySet()) {
            String modeName = modeNames.get(entry.getKey());
            curves.put(entry.getKey(), new CurveEntry(modeOf(modeName), modeName.endsWith(STEADY_SUFFIX), new LayerCurve(entry.getValue())));
        }
        return curves;
    }

    private static TractionMode modeOf(String modeName) {
        if (modeName.endsWith(STEADY_SUFFIX)) {
            TractionMode base = TractionMode.fromCsv(modeName.substring(0, modeName.length() - STEADY_SUFFIX.length()));
            if (base == TractionMode.COAST) {
                throw new IllegalArgumentException("coast has no steady-load layers");
            }
            return base;
        }
        return TractionMode.fromCsv(modeName);
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
