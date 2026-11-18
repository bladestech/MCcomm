package dev.mccomm.cell.workforce;

/**
 * A virtual employee. Phase 1 only has field technicians; the role field is there so later
 * roles (support, engineers, researchers...) share the same storage.
 */
public final class Employee {
    public enum Role {
        TECHNICIAN("Field technician");

        public final String display;

        Role(String display) {
            this.display = display;
        }
    }

    public static final String[] LEVELS = {"Junior", "Regular", "Senior"};

    private final int id;
    private final String companyId;
    private final Role role;
    private final String name;
    private int level;          // 0 junior, 1 regular, 2 senior
    private long wage;          // per day
    private int facilityId;     // 0 = none (idle)
    private int unpaidDays;     // paydays missed in a row
    private long busyUntil;     // epoch ms; 0 = free
    private int jobTower;       // tower being repaired (0 = none)
    private int jobsDone;
    private final long hiredAt;

    public Employee(int id, String companyId, Role role, String name, int level, long wage, long hiredAt) {
        this.id = id;
        this.companyId = companyId;
        this.role = role;
        this.name = name;
        this.level = level;
        this.wage = wage;
        this.hiredAt = hiredAt;
    }

    public int id() { return id; }
    public String companyId() { return companyId; }
    public Role role() { return role; }
    public String name() { return name; }
    public int level() { return level; }
    public void level(int l) { this.level = Math.clamp(l, 0, 2); }
    public String levelName() { return LEVELS[Math.clamp(level, 0, 2)]; }
    public long wage() { return wage; }
    public void wage(long w) { this.wage = Math.max(0, w); }
    public int facilityId() { return facilityId; }
    public void facilityId(int f) { this.facilityId = f; }
    public int unpaidDays() { return unpaidDays; }
    public void unpaidDays(int d) { this.unpaidDays = Math.max(0, d); }
    public long busyUntil() { return busyUntil; }
    public void busyUntil(long t) { this.busyUntil = t; }
    public int jobTower() { return jobTower; }
    public void jobTower(int t) { this.jobTower = t; }
    public int jobsDone() { return jobsDone; }
    public void jobsDone(int n) { this.jobsDone = n; }
    public long hiredAt() { return hiredAt; }

    /** Working: has a facility and isn't owed wages. */
    public boolean working() {
        return facilityId != 0 && unpaidDays == 0;
    }
}
