package de.mrjulsen.paw.traction.pack;

/** What the traction is doing, which decides which of a pack's layer banks sound. */
public enum TractionMode {
    POWER("power"),
    BRAKE("brake"),
    COAST("coast");

    private final String csvName;

    TractionMode(String csvName) {
        this.csvName = csvName;
    }

    public String csvName() {
        return csvName;
    }

    public static TractionMode fromCsv(String name) {
        for (TractionMode mode : values()) {
            if (mode.csvName.equals(name)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("unknown mode '" + name + "' (expected power, brake or coast)");
    }
}
