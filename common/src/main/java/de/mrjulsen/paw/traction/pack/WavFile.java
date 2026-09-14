package de.mrjulsen.paw.traction.pack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** A decoded RIFF WAVE file, mixed down to mono floats in -1..1. Reads 16, 24 and 32-bit PCM and 32-bit float. */
public record WavFile(float[] samples, int sampleRate) {
    private static final int FORMAT_PCM = 1;
    private static final int FORMAT_FLOAT = 3;
    private static final int FORMAT_EXTENSIBLE = 0xFFFE;

    public static WavFile read(InputStream input) throws IOException {
        byte[] data = input.readAllBytes();
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (data.length < 12 || !tag(data, 0, "RIFF") || !tag(data, 8, "WAVE")) {
            throw new IOException("not a RIFF WAVE file");
        }
        int format = -1;
        int channels = 0;
        int rate = 0;
        int bits = 0;
        int dataStart = -1;
        int dataLength = 0;
        int position = 12;
        while (position + 8 <= data.length) {
            int size = buffer.getInt(position + 4);
            int body = position + 8;
            if (size < 0 || body + size > data.length) {
                size = data.length - body;
            }
            if (tag(data, position, "fmt ") && size >= 16) {
                format = buffer.getShort(body) & 0xFFFF;
                channels = buffer.getShort(body + 2) & 0xFFFF;
                rate = buffer.getInt(body + 4);
                bits = buffer.getShort(body + 14) & 0xFFFF;
                if (format == FORMAT_EXTENSIBLE && size >= 26) {
                    format = buffer.getShort(body + 24) & 0xFFFF;
                }
            } else if (tag(data, position, "data")) {
                dataStart = body;
                dataLength = size;
            }
            position = body + size + (size & 1);
        }
        if (dataStart < 0 || channels <= 0 || rate <= 0) {
            throw new IOException("missing fmt or data chunk");
        }
        boolean supported = (format == FORMAT_PCM && (bits == 16 || bits == 24 || bits == 32)) || (format == FORMAT_FLOAT && bits == 32);
        if (!supported) {
            throw new IOException("unsupported WAV encoding: format " + format + ", " + bits + " bits");
        }

        int bytesPerSample = bits / 8;
        int frames = dataLength / (bytesPerSample * channels);
        float[] samples = new float[frames];
        int offset = dataStart;
        for (int frame = 0; frame < frames; frame++) {
            double sum = 0;
            for (int channel = 0; channel < channels; channel++) {
                sum += sample(data, buffer, offset, format, bits);
                offset += bytesPerSample;
            }
            samples[frame] = (float) (sum / channels);
        }
        return new WavFile(samples, rate);
    }

    private static double sample(byte[] data, ByteBuffer buffer, int offset, int format, int bits) {
        if (format == FORMAT_FLOAT) {
            return buffer.getFloat(offset);
        }
        return switch (bits) {
            case 16 -> buffer.getShort(offset) / 32768.0;
            case 24 -> ((data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8) | (data[offset + 2] << 16)) / 8388608.0;
            default -> buffer.getInt(offset) / 2147483648.0;
        };
    }

    private static boolean tag(byte[] data, int offset, String name) {
        return offset + 4 <= data.length && new String(data, offset, 4, StandardCharsets.US_ASCII).equals(name);
    }
}
