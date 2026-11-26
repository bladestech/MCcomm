package dev.mccomm.cell.model;

/**
 * A plan's temporary discount, and when its price last changed (a sale's "was" price must have been charged for a
 * while first). Never copied into a subscriber's saved terms.
 */
public final class Sale {
    private int percent;      // 0 = no sale
    private long startedAt;
    private long until;
    private long priceSince;  // when the plan's price last changed (epoch ms), 0 = long ago

    public int percent() { return percent; }
    public long startedAt() { return startedAt; }
    public long until() { return until; }
    public long priceSince() { return priceSince; }
    public void priceSince(long t) { this.priceSince = t; }

    public void start(int percent, long now, long until) {
        this.percent = percent;
        this.startedAt = now;
        this.until = until;
    }

    public void end() {
        percent = 0;
        startedAt = 0;
        until = 0;
    }

    /** Restores a saved sale. */
    public void set(int percent, long startedAt, long until) {
        this.percent = Math.max(0, percent);
        this.startedAt = startedAt;
        this.until = until;
    }

    public boolean on(long now) {
        return percent > 0 && now < until;
    }

    /** The price after this sale's discount (when on). */
    public long apply(long price, long now) {
        return on(now) ? Math.max(0, Math.round(price * (100 - percent) / 100.0)) : price;
    }

    /** Reads a live plan's sale and price date (saved terms never have one). */
    public void read(org.bukkit.configuration.ConfigurationSection s) {
        if (s == null) return;
        priceSince = s.getLong("price-since", 0);
        set(s.getInt("sale.percent", 0), s.getLong("sale.started-at", 0), s.getLong("sale.until", 0));
    }

    public void write(org.bukkit.configuration.file.YamlConfiguration y, String path) {
        y.set(path + "price-since", priceSince);
        if (percent > 0) {
            y.set(path + "sale.percent", percent);
            y.set(path + "sale.started-at", startedAt);
            y.set(path + "sale.until", until);
        }
    }

    /** Moves the schedule back by {@code ms} (timewarp). */
    public void shift(long ms) {
        if (startedAt > 0) startedAt -= ms;
        if (until > 0) until -= ms;
        if (priceSince > 0) priceSince -= ms;
    }
}
