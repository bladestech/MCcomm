package dev.mccomm.cell.band;

import java.util.Locale;

/** Band identities, ordered from lowest to highest. Stats come from config (see {@link BandSpec}). */
public enum Band {
    G2("2g"),
    G3("3g"),
    G4("4g"),
    G4_PLUS("4g+"),
    G5_LOW("5g-low"),
    G5_MID("5g-mid"),
    G5_HIGH("5g-high"),
    G5_MMWAVE("5g-mmwave");

    private final String key;

    Band(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static Band fromKey(String key) {
        if (key == null) return null;
        String k = key.toLowerCase(Locale.ROOT);
        for (Band b : values()) {
            if (b.key.equals(k) || b.name().equalsIgnoreCase(k)) return b;
        }
        return null;
    }
}
