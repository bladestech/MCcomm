package dev.mccomm.cell.workforce;

/** Per-company workforce bookkeeping: payday schedule, wages owed and options. */
public final class StaffState {
    private long nextPayday;       // epoch ms, 0 = not scheduled
    private long wagesOwed;        // missed wages and rent, collected before anything else
    private boolean contractors = true;

    public long nextPayday() { return nextPayday; }
    public void nextPayday(long t) { this.nextPayday = t; }
    public long wagesOwed() { return wagesOwed; }
    public void wagesOwed(long v) { this.wagesOwed = Math.max(0, v); }
    public boolean contractors() { return contractors; }
    public void contractors(boolean v) { this.contractors = v; }

    public boolean isDefault() {
        return nextPayday == 0 && wagesOwed == 0 && contractors;
    }
}
