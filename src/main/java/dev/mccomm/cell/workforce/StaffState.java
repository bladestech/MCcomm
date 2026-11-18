package dev.mccomm.cell.workforce;

/** Per-company workforce bookkeeping: payday schedule, wages owed and options. */
public final class StaffState {
    private long nextPayday;       // epoch ms, 0 = not scheduled
    private long wagesOwed;        // missed wages and rent, collected before anything else
    private long owedSince;        // when wages first went unpaid (epoch ms), 0 = paid up
    private boolean contractors = true;
    private long bankruptcyClock;  // when the bankruptcy countdown started (epoch ms), 0 = not in default

    public long nextPayday() { return nextPayday; }
    public void nextPayday(long t) { this.nextPayday = t; }
    public long wagesOwed() { return wagesOwed; }
    public void wagesOwed(long v) { this.wagesOwed = Math.max(0, v); }
    public boolean contractors() { return contractors; }
    public long owedSince() { return owedSince; }
    public void owedSince(long t) { this.owedSince = t; }
    public long bankruptcyClock() { return bankruptcyClock; }
    public void bankruptcyClock(long t) { this.bankruptcyClock = t; }
    public void contractors(boolean v) { this.contractors = v; }

    public boolean isDefault() {
        return nextPayday == 0 && wagesOwed == 0 && owedSince == 0 && bankruptcyClock == 0 && contractors;
    }
}
