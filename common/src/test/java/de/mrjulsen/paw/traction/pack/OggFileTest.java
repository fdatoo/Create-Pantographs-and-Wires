package de.mrjulsen.paw.traction.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class OggFileTest {
    private static byte[] resource(String name) throws IOException {
        try (InputStream in = OggFileTest.class.getResourceAsStream("/traction/" + name)) {
            return in.readAllBytes();
        }
    }

    @Test
    void decodesToTheSameLengthAndCloseToTheSource() throws IOException {
        WavFile source;
        try (InputStream in = OggFileTest.class.getResourceAsStream("/traction/tone440.wav")) {
            source = WavFile.read(in);
        }
        byte[] ogg = resource("tone440.ogg");
        OggFile.Info info = OggFile.info(ogg);
        assertEquals(source.samples().length, info.frames());
        assertEquals(48000, info.sampleRate());

        WavFile decoded = OggFile.decode(ogg);
        assertEquals(source.samples().length, decoded.samples().length);
        double signal = 0;
        double error = 0;
        for (int i = 0; i < source.samples().length; i++) {
            signal += source.samples()[i] * source.samples()[i];
            double e = decoded.samples()[i] - source.samples()[i];
            error += e * e;
        }
        double snr = 10 * Math.log10(signal / error);
        assertTrue(snr > 30, "SNR " + snr);
    }

    @Test
    void rejectsSomethingElse() {
        assertThrows(IOException.class, () -> OggFile.decode("not audio at all".getBytes(StandardCharsets.US_ASCII)));
    }
}
