package dev.mccomm.cell.broadband;

import java.util.Locale;

/** Who a plan is for: a home, or a business. A router is set up as one or the other and only sees matching plans. */
public enum Segment {
    RESIDENTIAL("residential", "Residential", "Home"),
    BUSINESS("business", "Business", "Business");

    private final String key;
    private final String label;
    private final String site;

    Segment(String key, String label, String site) {
        this.key = key;
        this.label = label;
        this.site = site;
    }

    public String key() { return key; }

    /** "Residential" / "Business": for plans. */
    public String label() { return label; }

    /** "Home" / "Business": for the site a router serves. */
    public String site() { return site; }

    public Segment other() {
        return this == RESIDENTIAL ? BUSINESS : RESIDENTIAL;
    }

    /** Parses "residential", "home", "business"...; null if it isn't one of them. */
    public static Segment fromKey(String s) {
        if (s == null) return null;
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "residential", "home", "house", "res" -> RESIDENTIAL;
            case "business", "biz", "commercial" -> BUSINESS;
            default -> null;
        };
    }
}
