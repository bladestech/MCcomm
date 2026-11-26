package dev.mccomm.cell.workforce;

/** One company's operations budget: chosen funding, the 3-day average of what was really paid, and its bill schedule. */
public final class OpsState {
    private final double[] target = new double[OpsPart.values().length];    // chosen funding, 1.0 = 100%
    private final double[] average = new double[OpsPart.values().length];   // moving average of effective funding
    private final double[] lastPaid = new double[OpsPart.values().length];   // effective funding of the last hourly bill
    private long nextBill;          // epoch ms of the next hourly bill, 0 = not scheduled
    private double transitDue;      // real data transit not yet billed (credits)
    private double carry;           // fractions of a credit not yet charged
    private long paidToday;         // charged since the last daily ledger line
    private long nextLog;           // when the daily ledger line is written
    private long bankruptcyClock;   // when the bankruptcy countdown started (epoch ms), 0 = not in default
    private boolean contractors = true;

    public OpsState(double[] defaults) {
        for (OpsPart p : OpsPart.values()) {
            target[p.ordinal()] = defaults[p.ordinal()];
            average[p.ordinal()] = defaults[p.ordinal()];
            lastPaid[p.ordinal()] = defaults[p.ordinal()];
        }
    }

    public double target(OpsPart p) { return target[p.ordinal()]; }
    public void target(OpsPart p, double v) { target[p.ordinal()] = Math.clamp(Math.round(v * 100) / 100.0, 0, p.max); }
    public double average(OpsPart p) { return average[p.ordinal()]; }
    public void average(OpsPart p, double v) { average[p.ordinal()] = Math.clamp(v, 0, p.max); }
    public double lastPaid(OpsPart p) { return lastPaid[p.ordinal()]; }
    public void lastPaid(OpsPart p, double v) { lastPaid[p.ordinal()] = Math.clamp(v, 0, p.max); }
    public long nextBill() { return nextBill; }
    public void nextBill(long t) { this.nextBill = t; }
    public double transitDue() { return transitDue; }
    public void transitDue(double v) { this.transitDue = Math.max(0, v); }
    public double carry() { return carry; }
    public void carry(double v) { this.carry = v; }
    public long paidToday() { return paidToday; }
    public void paidToday(long v) { this.paidToday = v; }
    public long nextLog() { return nextLog; }
    public void nextLog(long t) { this.nextLog = t; }
    public long bankruptcyClock() { return bankruptcyClock; }
    public void bankruptcyClock(long t) { this.bankruptcyClock = t; }
    public boolean contractors() { return contractors; }
    public void contractors(boolean v) { this.contractors = v; }
}
