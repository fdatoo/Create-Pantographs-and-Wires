package de.mrjulsen.paw.config;

import javax.annotation.Nullable;

/** Which recorded train the electric traction sound is modelled on. */
public enum TractionSoundProfile {
    /** BART Fleet of the Future (D/E cars), played from the BART traction sound pack. */
    BART("bart"),
    /** Washington Metro 6000-series (Alstom), played from the WMATA traction sound pack. */
    WMATA("wmata"),
    /** Paris Metro MP 89, matched to a departure recording. */
    MP89(null);

    @Nullable
    private final String pack;

    TractionSoundProfile(@Nullable String pack) {
        this.pack = pack;
    }

    /** The profile's sound pack folder under assets/pantographsandwires/traction, or null when it plays no pack. */
    @Nullable
    public String pack() {
        return pack;
    }
}
