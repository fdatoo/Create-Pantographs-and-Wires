package de.mrjulsen.paw.traction.pack;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A layer's loop. Either decoded once when the pack loads, or kept encoded and decoded only while a
 * mixer wants it, then dropped again after it goes unwanted for a while. Steady-load layers are held the
 * second way: there are over a hundred of them, ten seconds each, and only the few around the current
 * speed can play.
 *
 * The length and rate are known from the header either way, so a mixer can keep a silent layer's read
 * head moving before its samples exist.
 */
public final class LayerAudio {
    private static final Logger LOGGER = LoggerFactory.getLogger("pantographsandwires");

    /** Decodes a layer's encoded file to mono samples. */
    @FunctionalInterface
    public interface Decoder {
        float[] decode(byte[] encoded) throws IOException;
    }

    private final int frames;
    private final int sampleRate;
    @Nullable
    private final byte[] encoded;
    @Nullable
    private final Decoder decoder;
    @Nullable
    private volatile float[] samples;
    private volatile long lastWantedNanos;
    private final AtomicBoolean decoding = new AtomicBoolean();

    private LayerAudio(int frames, int sampleRate, @Nullable float[] samples, @Nullable byte[] encoded, @Nullable Decoder decoder) {
        this.frames = frames;
        this.sampleRate = sampleRate;
        this.samples = samples;
        this.encoded = encoded;
        this.decoder = decoder;
    }

    public static LayerAudio decoded(float[] samples, int sampleRate) {
        return new LayerAudio(samples.length, sampleRate, samples, null, null);
    }

    public static LayerAudio onDemand(byte[] encoded, int frames, int sampleRate, Decoder decoder) {
        return new LayerAudio(frames, sampleRate, null, encoded, decoder);
    }

    public int frames() {
        return frames;
    }

    public int sampleRate() {
        return sampleRate;
    }

    public boolean isOnDemand() {
        return decoder != null;
    }

    /** The decoded loop, or null while an on-demand layer isn't decoded. */
    @Nullable
    public float[] samples() {
        return samples;
    }

    /** Marks the layer wanted now, and starts decoding it on the executor if it isn't decoded or decoding. */
    public void want(long nowNanos, Executor executor) {
        lastWantedNanos = nowNanos;
        if (decoder == null || samples != null || !decoding.compareAndSet(false, true)) {
            return;
        }
        executor.execute(() -> {
            try {
                float[] decoded = decoder.decode(encoded);
                if (decoded.length != frames) {
                    throw new IOException("decoded " + decoded.length + " samples, header says " + frames);
                }
                samples = decoded;
            } catch (IOException | RuntimeException e) {
                LOGGER.error("Could not decode a traction sound layer", e);
            } finally {
                decoding.set(false);
            }
        });
    }

    /** Drops an on-demand layer's samples once nothing has wanted it for idleNanos. */
    public void releaseIfIdle(long nowNanos, long idleNanos) {
        if (decoder != null && samples != null && nowNanos - lastWantedNanos > idleNanos) {
            samples = null;
        }
    }
}
