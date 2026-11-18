package dev.mccomm.cell.workforce;

/**
 * A repair for one tower. Queued when wear passes the dispatch level, then taken by a technician
 * from a station (or a contractor). The tower is repaired at {@code repairAt}.
 */
public final class RepairJob {
    private final int towerId;
    private long queuedAt;
    private int technicianId;  // 0 = not assigned
    private int stationId;
    private boolean contractor;
    private long repairAt;     // 0 = not started
    private long cost;         // parts (and contractor fee) charged at the start
    private boolean notifiedBroke; // owner told the company couldn't pay for it

    public RepairJob(int towerId, long queuedAt) {
        this.towerId = towerId;
        this.queuedAt = queuedAt;
    }

    public int towerId() { return towerId; }
    public long queuedAt() { return queuedAt; }
    public void queuedAt(long t) { this.queuedAt = t; }
    public int technicianId() { return technicianId; }
    public void technicianId(int id) { this.technicianId = id; }
    public int stationId() { return stationId; }
    public void stationId(int id) { this.stationId = id; }
    public boolean contractor() { return contractor; }
    public void contractor(boolean c) { this.contractor = c; }
    public long repairAt() { return repairAt; }
    public void repairAt(long t) { this.repairAt = t; }
    public long cost() { return cost; }
    public void cost(long c) { this.cost = c; }
    public boolean notifiedBroke() { return notifiedBroke; }
    public void notifiedBroke(boolean b) { this.notifiedBroke = b; }

    public boolean started() {
        return repairAt > 0;
    }
}
