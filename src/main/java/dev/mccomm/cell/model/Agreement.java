package dev.mccomm.cell.model;

import dev.mccomm.cell.band.Band;

/**
 * A deal where the host carrier lets a partner use its towers. Stored on the host.
 * <ul>
 *   <li>ROAMING: the partner's subscribers fall back to the host's towers when their own network has
 *       no coverage. The partner pays {@code perGb} for data its roamers use.</li>
 *   <li>MVNO: the partner is a virtual carrier whose subscribers use the host's towers as home.
 *       The host takes {@code sharePercent} of the MVNO's plan revenue.</li>
 * </ul>
 */
public final class Agreement {
    public enum Kind { ROAMING, MVNO }

    private final Kind kind;
    private final String partnerId;
    private long perGb = 5;
    private double sharePercent = 30;
    private Band maxBand;          // null = no cap
    private boolean deprioritize = true;
    private long endsAt;           // 0 = running; otherwise ends at this time (notice period)

    public Agreement(Kind kind, String partnerId) {
        this.kind = kind;
        this.partnerId = partnerId;
    }

    public Kind kind() { return kind; }
    public String partnerId() { return partnerId; }
    public long perGb() { return perGb; }
    public void perGb(long v) { this.perGb = v; }
    public double sharePercent() { return sharePercent; }
    public void sharePercent(double v) { this.sharePercent = v; }
    public Band maxBand() { return maxBand; }
    public void maxBand(Band b) { this.maxBand = b; }
    public boolean deprioritize() { return deprioritize; }
    public void deprioritize(boolean v) { this.deprioritize = v; }
    public long endsAt() { return endsAt; }
    public void endsAt(long t) { this.endsAt = t; }

    public String key() {
        return key(kind, partnerId);
    }

    public static String key(Kind kind, String partnerId) {
        return (kind == Kind.ROAMING ? "R:" : "M:") + partnerId;
    }

    public Agreement copy() {
        Agreement a = new Agreement(kind, partnerId);
        a.perGb = perGb;
        a.sharePercent = sharePercent;
        a.maxBand = maxBand;
        a.deprioritize = deprioritize;
        a.endsAt = endsAt;
        return a;
    }
}
