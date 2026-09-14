package de.mrjulsen.paw.traction.pack;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import org.lwjgl.stb.STBVorbis;
import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryUtil;

/**
 * Ogg Vorbis layers, decoded with the stb_vorbis library Minecraft already ships (through LWJGL).
 * Samples are mixed down to mono floats in -1..1.
 */
public final class OggFile {
    private OggFile() {}

    /** What the stream header says, without decoding any audio. */
    public record Info(int frames, int sampleRate) {}

    public static Info info(byte[] data) throws IOException {
        ByteBuffer native_ = MemoryUtil.memAlloc(data.length);
        STBVorbisInfo info = STBVorbisInfo.malloc();
        long handle = 0;
        try {
            handle = open(native_.put(data).flip());
            STBVorbis.stb_vorbis_get_info(handle, info);
            int frames = STBVorbis.stb_vorbis_stream_length_in_samples(handle);
            if (frames <= 0 || info.sample_rate() <= 0) {
                throw new IOException("Ogg Vorbis stream has no length");
            }
            return new Info(frames, info.sample_rate());
        } finally {
            if (handle != 0) {
                STBVorbis.stb_vorbis_close(handle);
            }
            info.free();
            MemoryUtil.memFree(native_);
        }
    }

    public static WavFile decode(byte[] data) throws IOException {
        ByteBuffer native_ = MemoryUtil.memAlloc(data.length);
        STBVorbisInfo info = STBVorbisInfo.malloc();
        FloatBuffer chunk = null;
        long handle = 0;
        try {
            handle = open(native_.put(data).flip());
            STBVorbis.stb_vorbis_get_info(handle, info);
            int channels = info.channels();
            int frames = STBVorbis.stb_vorbis_stream_length_in_samples(handle);
            if (channels <= 0 || frames <= 0 || info.sample_rate() <= 0) {
                throw new IOException("Ogg Vorbis stream has no audio");
            }
            float[] samples = new float[frames];
            chunk = MemoryUtil.memAllocFloat(4096 * channels);
            int written = 0;
            while (written < frames) {
                chunk.clear();
                int got = STBVorbis.stb_vorbis_get_samples_float_interleaved(handle, channels, chunk);
                if (got <= 0) {
                    break;
                }
                for (int frame = 0; frame < got && written < frames; frame++, written++) {
                    float sum = 0;
                    for (int channel = 0; channel < channels; channel++) {
                        sum += chunk.get(frame * channels + channel);
                    }
                    samples[written] = sum / channels;
                }
            }
            if (written != frames) {
                throw new IOException("Ogg Vorbis stream ended after " + written + " of " + frames + " samples");
            }
            return new WavFile(samples, info.sample_rate());
        } finally {
            if (handle != 0) {
                STBVorbis.stb_vorbis_close(handle);
            }
            if (chunk != null) {
                MemoryUtil.memFree(chunk);
            }
            info.free();
            MemoryUtil.memFree(native_);
        }
    }

    private static long open(ByteBuffer data) throws IOException {
        IntBuffer error = MemoryUtil.memAllocInt(1);
        try {
            long handle = STBVorbis.stb_vorbis_open_memory(data, error, null);
            if (handle == 0) {
                throw new IOException("not an Ogg Vorbis stream (stb_vorbis error " + error.get(0) + ")");
            }
            return handle;
        } finally {
            MemoryUtil.memFree(error);
        }
    }
}
