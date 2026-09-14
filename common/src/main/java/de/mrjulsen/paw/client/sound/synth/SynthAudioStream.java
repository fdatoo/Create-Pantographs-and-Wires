package de.mrjulsen.paw.client.sound.synth;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import javax.sound.sampled.AudioFormat;

import org.lwjgl.BufferUtils;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.sounds.AudioStream;

/**
 * Feeds generated audio to Minecraft's streaming sound path in place of a decoded file. Minecraft
 * reads a stream from its sound thread, keeps a few buffers queued and refills them every client
 * tick, so each buffer here is short: the delay between a train changing and hearing it is about
 * the queue's length.
 */
@Environment(EnvType.CLIENT)
public final class SynthAudioStream implements AudioStream {
    public static final int SAMPLE_RATE = 48000;
    /** Samples per buffer. Minecraft queues four, so this puts the sound a quarter second behind the train. */
    public static final int BLOCK_SAMPLES = 3000;

    private static final AudioFormat FORMAT = new AudioFormat(SAMPLE_RATE, 16, 1, true, ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN);

    /** Something that fills a block of mono samples in -1..1. Called from Minecraft's sound thread. */
    public interface Source {
        void render(float[] block, int count);
    }

    private final Source source;
    private final float[] block = new float[BLOCK_SAMPLES];
    private volatile boolean closed;

    public SynthAudioStream(Source source) {
        this.source = source;
    }

    @Override
    public AudioFormat getFormat() {
        return FORMAT;
    }

    @Override
    public ByteBuffer read(int requestedBytes) {
        if (closed) {
            return null;
        }
        source.render(block, BLOCK_SAMPLES);
        ByteBuffer buffer = BufferUtils.createByteBuffer(BLOCK_SAMPLES * 2);
        for (int i = 0; i < BLOCK_SAMPLES; i++) {
            float sample = Math.max(-1f, Math.min(1f, block[i]));
            buffer.putShort((short) Math.round(sample * 32767f));
        }
        buffer.flip();
        return buffer;
    }

    @Override
    public void close() {
        closed = true;
    }
}
