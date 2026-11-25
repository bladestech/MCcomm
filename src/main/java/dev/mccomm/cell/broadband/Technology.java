package dev.mccomm.cell.broadband;

import java.util.Locale;

/** How a home connection reaches the internet. Stats come from config (see {@link BroadbandSettings.TechSpec}). */
public enum Technology {
    DSL("dsl"),
    CABLE("cable"),
    FIBER("fiber"),
    SATELLITE("satellite"),
    /** "5G Home": rides a mobile carrier's towers. Sold only by carrier-backed ISPs. */
    FIXED_WIRELESS("fixed-wireless");

    private final String key;

    Technology(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    /** True for the technologies an ordinary ISP builds nodes for (everything but fixed wireless). */
    public boolean nodeBased() {
        return this != FIXED_WIRELESS;
    }

    public static Technology fromKey(String key) {
        if (key == null) return null;
        String k = key.toLowerCase(Locale.ROOT).replace('_', '-');
        for (Technology t : values()) {
            if (t.key.equals(k)) return t;
        }
        return null;
    }

    /** The technologies an ordinary ISP builds nodes for. */
    public static Technology[] nodeTechs() {
        return new Technology[]{DSL, CABLE, FIBER, SATELLITE};
    }
}
