package dev.mccomm.cell.broadband;

/**
 * A home or business internet plan an ISP sells: one technology, a speed cap, an optional data allowance and a price per period.
 * Mutable so the owner can edit it. Subscribers keep the terms they signed up under until their next renewal
 * (the router holds a {@link #copy()} of the plan).
 */
public final class InternetPlan {
    private final String id;
    private String name;
    private Technology tech;
    private long price;
    private int periodHours;
    private double speedCapMbps;
    private long installFee;
    private long dataMB = -1; // -1 = unlimited; past the allowance the line is throttled (fair use)
    private Segment segment = Segment.RESIDENTIAL; // who it is sold to
    private final dev.mccomm.cell.model.Sale sale = new dev.mccomm.cell.model.Sale(); // never copied into a router's terms

    public InternetPlan(String id, String name, Technology tech, long price, int periodHours, double speedCapMbps, long installFee) {
        this.id = id;
        this.name = name;
        this.tech = tech;
        this.price = price;
        this.periodHours = periodHours;
        this.speedCapMbps = speedCapMbps;
        this.installFee = installFee;
        this.sale.priceSince(System.currentTimeMillis());
    }

    public String id() { return id; }
    public String name() { return name; }
    public void name(String n) { this.name = n; }
    public Technology tech() { return tech; }
    public void tech(Technology t) { this.tech = t; }
    public long price() { return price; }
    public void price(long p) {
        if (Math.max(0, p) != price) sale.priceSince(System.currentTimeMillis());
        this.price = Math.max(0, p);
    }

    public dev.mccomm.cell.model.Sale sale() { return sale; }
    public int periodHours() { return periodHours; }
    public void periodHours(int h) { this.periodHours = Math.max(1, h); }
    public double speedCapMbps() { return speedCapMbps; }
    public void speedCapMbps(double c) { this.speedCapMbps = Math.max(0.1, c); }
    public long installFee() { return installFee; }
    public void installFee(long f) { this.installFee = Math.max(0, f); }
    public long dataMB() { return dataMB; }
    public void dataMB(long mb) { this.dataMB = mb < 0 ? -1 : mb; }
    public boolean unlimited() { return dataMB < 0; }
    public Segment segment() { return segment; }
    public void segment(Segment s) { this.segment = s == null ? Segment.RESIDENTIAL : s; }
    public boolean business() { return segment == Segment.BUSINESS; }

    public long periodMillis() {
        return periodHours * 3_600_000L;
    }

    /** A full copy (same id): the terms a subscriber keeps until their next renewal. */
    public InternetPlan copy() {
        InternetPlan p = new InternetPlan(id, name, tech, price, periodHours, speedCapMbps, installFee);
        p.dataMB = dataMB;
        p.segment = segment;
        return p;
    }

    /** True if a subscriber on {@code other} would notice a difference to this plan. */
    public boolean sameTermsAs(InternetPlan other) {
        return other != null && price == other.price && periodHours == other.periodHours
                && Double.compare(speedCapMbps, other.speedCapMbps) == 0 && dataMB == other.dataMB;
    }
}
