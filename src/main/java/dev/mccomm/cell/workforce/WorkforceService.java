package dev.mccomm.cell.workforce;

import dev.mccomm.cell.CellPlugin;
import dev.mccomm.cell.model.BlockKey;
import dev.mccomm.cell.model.Carrier;
import dev.mccomm.cell.model.Tower;
import dev.mccomm.cell.util.Msg;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Network wear, maintenance stations, technicians and repairs (workforce phase 1).
 * <ul>
 *   <li>Towers gain wear from the traffic they carry; worn towers slow down, lose bands, then go silent.</li>
 *   <li>Past the dispatch level a repair job is queued. The nearest station with a free technician
 *       sends one; travel time depends on distance. With no station in reach, a slow and expensive
 *       contractor does it.</li>
 *   <li>Wages and station rent are paid daily from the carrier's wallet. All of it is spent (a sink).</li>
 * </ul>
 */
public final class WorkforceService {
    private static final long MINUTE = 60_000L, HOUR = 3_600_000L, DAY = 86_400_000L;

    private final CellPlugin plugin;
    private final NamespacedKey techKey;
    private final Map<Integer, Facility> facilities = new LinkedHashMap<>();
    private final Map<BlockKey, Facility> facilityByBlock = new HashMap<>();
    private final Map<Integer, Employee> employees = new LinkedHashMap<>();
    private final Map<Integer, RepairJob> jobs = new LinkedHashMap<>(); // tower id -> job
    private final Map<String, StaffState> states = new HashMap<>();
    private final Map<Integer, Integer> lastStage = new HashMap<>();
    private final Map<Integer, UUID> figures = new HashMap<>(); // tower id -> technician mannequin
    private final Map<String, Set<Integer>> hiredCandidates = new HashMap<>();
    private int nextFacilityId = 1, nextEmployeeId = 1;

    public WorkforceService(CellPlugin plugin) {
        this.plugin = plugin;
        this.techKey = new NamespacedKey(plugin, "technician");
    }

    public WorkforceSettings w() {
        return plugin.settings().workforce;
    }

    public boolean enabled() {
        return w().enabled();
    }

    // ================================================================ wear

    /** 0 = fine, 1 = slow, 2 = top band off, 3 = two bands off, 4 = silent. */
    public int wearStage(Tower t) {
        if (!enabled()) return 0;
        double[] steps = w().wearSteps();
        int stage = 0;
        for (double s : steps) if (t.wear() >= s) stage++;
        return stage;
    }

    public boolean silent(Tower t) {
        return wearStage(t) >= 4;
    }

    public double wearSpeed(Tower t) {
        int st = wearStage(t);
        return st == 0 ? 1.0 : w().wearSpeed()[Math.min(st, 4) - 1];
    }

    public int wearDroppedBands(Tower t) {
        int st = wearStage(t);
        return st >= 2 ? st - 1 : 0;
    }

    /** Wear added per day at this tower's current traffic. */
    public double wearPerDay(Tower t) {
        if (!enabled() || !t.online()) return 0;
        int subscribers = plugin.signal().usersOn(t) + plugin.signal().simOn(t);
        return w().wearIdlePerDay() + w().wearPerSubscriberDay() * subscribers;
    }

    /** Real data carried by a tower (called on data use). */
    public void addTraffic(Tower t, double mb) {
        if (!enabled() || t == null || mb <= 0) return;
        t.wear(t.wear() + mb / 1000.0 * w().wearPerGb());
        // The network carrying the data pays transit for it, settled on payday.
        StaffState st = state(t.carrierId());
        st.transitDue(st.transitDue() + mb / 1000.0 * w().transitPerGb());
    }

    // =============================================================== queries

    public Collection<Facility> facilities() { return facilities.values(); }
    public Facility facility(int id) { return facilities.get(id); }
    public Facility facilityAt(Block b) { return b == null ? null : facilityByBlock.get(BlockKey.of(b)); }
    public Collection<Employee> employees() { return employees.values(); }
    public RepairJob job(Tower t) { return jobs.get(t.id()); }
    public Collection<RepairJob> jobs() { return jobs.values(); }

    public List<Facility> facilitiesOf(String companyId) {
        List<Facility> out = new ArrayList<>();
        for (Facility f : facilities.values()) if (f.companyId().equals(companyId)) out.add(f);
        return out;
    }

    public List<Employee> staffOf(String companyId) {
        List<Employee> out = new ArrayList<>();
        for (Employee e : employees.values()) if (e.companyId().equals(companyId)) out.add(e);
        return out;
    }

    public List<Employee> staffAt(Facility f) {
        List<Employee> out = new ArrayList<>();
        for (Employee e : employees.values()) if (e.facilityId() == f.id()) out.add(e);
        return out;
    }

    public StaffState state(String companyId) {
        return states.computeIfAbsent(companyId, k -> new StaffState());
    }

    public Facility facilityByName(String companyId, String name) {
        for (Facility f : facilitiesOf(companyId)) if (f.name().equalsIgnoreCase(name)) return f;
        return null;
    }

    /** Facility whose sign hangs on this block. */
    public List<Facility> facilitiesAttachedTo(Block support) {
        List<Facility> out = new ArrayList<>();
        for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Block rel = support.getRelative(face);
            Facility f = facilityAt(rel);
            if (f != null && rel.getBlockData() instanceof WallSign ws && ws.getFacing() == face) out.add(f);
        }
        return out;
    }

    public long rent(Facility f) {
        return w().rentBase() + w().rentPerSlot() * f.capacity();
    }

    /** Money a carrier keeps back for its next upkeep (and MVNO share): paid before anything else. */
    public long upkeepReserve(Carrier c) {
        return plugin.billing().upkeepCost(c) + c.mvnoOwed();
    }

    /** Money kept back before paying for repairs: upkeep, wages owed and the next payday. */
    public long repairReserve(Carrier c) {
        StaffState st = states.get(c.id());
        return upkeepReserve(c) + (st == null ? 0 : st.wagesOwed()) + dailyCost(c.id());
    }

    /** Wages plus rent per day. */
    public long dailyCost(String companyId) {
        long sum = 0;
        for (Employee e : staffOf(companyId)) sum += e.wage();
        for (Facility f : facilitiesOf(companyId)) sum += rent(f);
        Carrier c = plugin.store().carrier(companyId);
        if (c != null) sum += simTransitPerDay(c);
        return sum;
    }

    public int freeBeds(String companyId) {
        return freeSlots(companyId, Facility.Type.STATION);
    }

    /** Free places for staff in this company's facilities of one type. */
    public int freeSlots(String companyId, Facility.Type type) {
        int slots = 0;
        for (Facility f : facilitiesOf(companyId)) if (f.type() == type) slots += f.capacity();
        for (Employee e : staffOf(companyId)) if (e.role().facility() == type) slots--;
        return slots;
    }

    public List<Employee> staffOf(String companyId, Employee.Role role) {
        List<Employee> out = new ArrayList<>();
        for (Employee e : employees.values()) if (e.companyId().equals(companyId) && e.role() == role) out.add(e);
        return out;
    }

    // ============================================================ staffing load

    private Map<String, Integer> realSubs = new HashMap<>();

    /** Paying real subscribers per carrier, refreshed every tick. */
    private void countSubscribers() {
        Map<String, Integer> m = new HashMap<>();
        for (var pr : plugin.store().profiles()) {
            for (var e : pr.subs().entrySet()) if (e.getValue().planId() != null) m.merge(e.getKey(), 1, Integer::sum);
        }
        realSubs = m;
    }

    /** Subscribers a carrier serves: real ones plus simulated customers. */
    public int subscribers(Carrier c) {
        return realSubs.getOrDefault(c.id(), 0) + c.simulatedUsers();
    }

    /** Subscribers on a network operator's towers: its own plus those of the MVNOs it hosts. */
    public int networkSubscribers(Carrier c) {
        int n = subscribers(c);
        for (var a : c.agreements().values()) {
            if (a.kind() != dev.mccomm.cell.model.Agreement.Kind.MVNO) continue;
            Carrier m = plugin.store().carrier(a.partnerId());
            if (m != null) n += subscribers(m);
        }
        return n;
    }

    /** Subscribers a role's working staff can cover. */
    public double coverage(Carrier c, Employee.Role role) {
        double sum = 0;
        for (Employee e : staffOf(c.id(), role)) if (e.working()) sum += w().levelProductivity()[e.level()] * w().covers(role);
        return sum;
    }

    /** Work needed of a role divided by what its staff cover: 0 = nothing to do, above 1 = understaffed. */
    public double load(Carrier c, Employee.Role role) {
        int subs = role == Employee.Role.ENGINEER ? networkSubscribers(c) : subscribers(c);
        double need = Math.max(0, subs - w().selfService());
        if (need <= 0) return 0;
        double cov = coverage(c, role);
        return cov <= 0 ? Double.POSITIVE_INFINITY : need / cov;
    }

    /** Staff of a role needed to cover today's subscribers at regular level. */
    public int needed(Carrier c, Employee.Role role) {
        int subs = role == Employee.Role.ENGINEER ? networkSubscribers(c) : subscribers(c);
        double need = Math.max(0, subs - w().selfService());
        return (int) Math.ceil(need / Math.max(1, w().covers(role)));
    }

    /** Speed multiplier on a carrier's towers from engineering staff (1 when staffed). */
    public double engineerSpeed(Carrier c) {
        if (!enabled() || c == null) return 1;
        double load = load(c, Employee.Role.ENGINEER);
        return load <= 1 ? 1 : Math.max(w().engineerMinSpeed(), 1 / load);
    }

    public double engineerSpeed(Tower t) {
        return engineerSpeed(plugin.store().carrier(t.carrierId()));
    }

    /** Daily transit cost for simulated customers' data (only while they pay, i.e. revenue is on). */
    public long simTransitPerDay(Carrier c) {
        if (!enabled() || !plugin.store().simulatedRevenue()) return 0;
        return (long) Math.ceil(c.simulatedUsers() * w().simGbPerDay() * w().transitPerGb());
    }

    public int freeBeds(Facility f) {
        return f.capacity() - staffAt(f).size();
    }

    public boolean free(Employee e, long now) {
        return e.working() && e.busyUntil() <= now;
    }

    // ================================================================= tick

    /** Runs with the billing tick (every minute, and every step of a timewarp). */
    public void tick(long now) {
        if (!enabled()) return;
        countSubscribers();
        accrueWear(now);
        recheckFacilities(now);
        placeIdle();
        payday(now);
        runJobs(now);
        updateFigures(now);
    }

    private void accrueWear(long now) {
        for (Tower t : plugin.store().towers()) {
            if (t.wearAt() == 0 || t.wearAt() > now) {
                t.wearAt(now);
                continue;
            }
            long dt = now - t.wearAt();
            t.wearAt(now);
            boolean silentByUpkeep = plugin.billing().decayStage(t) >= plugin.settings().silentStage();
            if (!silentByUpkeep) t.wear(t.wear() + wearPerDay(t) * dt / (double) DAY);
            int stage = wearStage(t);
            Integer before = lastStage.put(t.id(), stage);
            if (before != null && before != stage) {
                plugin.towers().refresh(t);
                Carrier c = plugin.store().carrier(t.carrierId());
                if (c != null && stage >= 4 && before < 4) {
                    plugin.billing().notify(c.owner(), "<dark_red>Tower <white><t></white> has worn out and gone silent.</dark_red> <gray>It needs a repair: build a maintenance station or wait for a contractor.",
                            Msg.p("t", t.name()));
                } else if (c != null && stage == 2 && before < 2) {
                    plugin.billing().notify(c.owner(), "<gold>Tower <white><t></white> is badly worn and has lost its top band.", Msg.p("t", t.name()));
                }
            }
            if (t.wear() >= w().dispatchAt() && !jobs.containsKey(t.id()) && t.online()) {
                jobs.put(t.id(), new RepairJob(t.id(), now));
            }
        }
        plugin.store().markDirty();
    }

    private void runJobs(long now) {
        for (RepairJob j : new ArrayList<>(jobs.values())) {
            Tower t = plugin.store().tower(j.towerId());
            Carrier c = t == null ? null : plugin.store().carrier(t.carrierId());
            if (t == null || c == null) {
                jobs.remove(j.towerId());
                continue;
            }
            if (j.started()) {
                if (now >= j.repairAt()) finish(j, t, c);
                continue;
            }
            if (!assign(j, t, c, now)) {
                StaffState st = state(c.id());
                if (st.contractors() && now - j.queuedAt() >= w().contractorAfterMinutes() * MINUTE) contractor(j, t, c, now);
            }
        }
    }

    /** Minutes of on-site work for a repair at this wear. */
    public double repairMinutes(double wear, int level, Facility station) {
        double m = (w().repairBaseMinutes() + w().repairPerWearMinutes() * wear) * w().levelSpeed()[Math.clamp(level, 0, 2)];
        if (station != null) {
            double cut = Math.min(w().barrelMax(), station.barrels() / w().barrelsPerStep() * w().barrelStep());
            m *= 1 - cut;
        }
        return m;
    }

    public long partsCost(double wear) {
        return (long) Math.ceil(w().partsBase() + w().partsPerWear() * wear);
    }

    public long contractorCost(double wear) {
        double minutes = repairMinutes(wear, 1, null);
        double labour = w().technicianWage() * minutes / (24 * 60.0);
        return Math.max(w().contractorMin(), (long) Math.ceil(w().contractorMultiplier() * (partsCost(wear) + labour)));
    }

    /** Sends the nearest free technician in range. False if nobody can go. */
    private boolean assign(RepairJob j, Tower t, Carrier c, long now) {
        Employee best = null;
        Facility bestStation = null;
        double bestDist = Double.MAX_VALUE;
        for (Facility f : facilitiesOf(c.id())) {
            if (f.type() != Facility.Type.STATION) continue;
            double d = f.distanceTo(t.worldId(), t.x(), t.z());
            if (d < 0 || d > w().serviceRadius() || d >= bestDist) continue;
            Employee pick = null;
            for (Employee e : staffAt(f)) {
                if (!free(e, now)) continue;
                if (pick == null || e.level() > pick.level()) pick = e; // most senior first
            }
            if (pick != null) {
                best = pick;
                bestStation = f;
                bestDist = d;
            }
        }
        if (best == null) return false;
        long parts = partsCost(t.wear());
        if (!charge(c, j, parts, "Repair parts: " + t.name())) return true; // can't pay: wait, don't call a contractor either
        double travel = bestDist / w().blocksPerMinute();
        double work = repairMinutes(t.wear(), best.level(), bestStation);
        long arrive = now + Math.round((w().setOutMinutes() + travel) * MINUTE);
        j.technicianId(best.id());
        j.stationId(bestStation.id());
        j.repairAt(arrive + Math.round(work * MINUTE));
        j.cost(parts);
        best.busyUntil(j.repairAt() + Math.round(travel * MINUTE));
        best.jobTower(t.id());
        plugin.towers().refresh(t);
        plugin.store().markDirty();
        return true;
    }

    private void contractor(RepairJob j, Tower t, Carrier c, long now) {
        long cost = contractorCost(t.wear());
        if (!charge(c, j, cost, "Contractor repair: " + t.name())) return;
        double work = repairMinutes(t.wear(), 1, null);
        j.contractor(true);
        j.repairAt(now + w().contractorDelayHours() * HOUR + Math.round(work * MINUTE));
        j.cost(cost);
        plugin.billing().notify(c.owner(), "<gray>No technician could reach tower <white><t></white>, so a contractor was booked for <white><c></white>. They'll be there in about <white><h> h</white>.",
                Msg.p("t", t.name()), Msg.p("c", plugin.settings().money(cost)), Msg.p("h", "" + w().contractorDelayHours()));
        plugin.towers().refresh(t);
        plugin.store().markDirty();
    }

    private boolean charge(Carrier c, RepairJob j, long cost, String note) {
        long reserve = repairReserve(c);
        if (c.balance() - cost < reserve) {
            if (!j.notifiedBroke()) {
                j.notifiedBroke(true);
                plugin.billing().notify(c.owner(), "<red>A repair for <c> (<white><a></white>) is on hold: <white><r></white> is kept back for upkeep and wages first. The tower keeps wearing until you deposit.",
                        Msg.p("c", c.name()), Msg.p("a", plugin.settings().money(cost)), Msg.p("r", plugin.settings().money(reserve)));
            }
            return false;
        }
        c.balance(c.balance() - cost);
        c.log(-cost, note);
        return true;
    }

    private void finish(RepairJob j, Tower t, Carrier c) {
        jobs.remove(t.id());
        t.wear(0);
        Employee e = employees.get(j.technicianId());
        if (e != null) {
            e.jobsDone(e.jobsDone() + 1);
            if (e.jobTower() == t.id()) e.jobTower(0);
        }
        removeFigure(t.id());
        lastStage.put(t.id(), 0);
        plugin.towers().refresh(t);
        plugin.store().markDirty();
    }

    // =============================================================== payday

    private void payday(long now) {
        Set<String> companies = new HashSet<>();
        for (Employee e : employees.values()) companies.add(e.companyId());
        for (Facility f : facilities.values()) companies.add(f.companyId());
        for (var e : states.entrySet()) if (e.getValue().wagesOwed() > 0 || e.getValue().transitDue() >= 1) companies.add(e.getKey());
        for (Carrier c : plugin.store().carriers()) if (simTransitPerDay(c) > 0) companies.add(c.id());
        for (String id : companies) {
            Carrier c = plugin.store().carrier(id);
            if (c == null) continue;
            StaffState st = state(id);
            if (st.nextPayday() == 0) {
                st.nextPayday(now + DAY);
                continue;
            }
            for (int guard = 0; guard < 60 && now >= st.nextPayday(); guard++) {
                long transit = (long) Math.floor(st.transitDue());
                st.transitDue(st.transitDue() - transit);
                long bill = dailyCost(id) + transit;
                st.nextPayday(st.nextPayday() + DAY);
                if (bill <= 0 && st.wagesOwed() == 0) continue;
                long total = st.wagesOwed() + bill;
                // Upkeep comes first: wages are only paid if the next upkeep stays covered.
                if (c.unpaidSince() == 0 && c.balance() - total >= upkeepReserve(c)) {
                    c.balance(c.balance() - total);
                    c.log(-total, "Wages, rent & transit (" + staffOf(id).size() + " staff)");
                    st.wagesOwed(0);
                    st.owedSince(0);
                    for (Employee e : staffOf(id)) e.unpaidDays(0);
                } else {
                    st.wagesOwed(total);
                    if (st.owedSince() == 0) st.owedSince(st.nextPayday() - DAY);
                    List<String> quit = new ArrayList<>();
                    for (Employee e : staffOf(id)) {
                        e.unpaidDays(e.unpaidDays() + 1);
                        if (e.unpaidDays() >= w().quitAfterOwedDays()) {
                            employees.remove(e.id());
                            quit.add(e.name());
                        }
                    }
                    plugin.billing().notify(c.owner(), "<red><c> couldn't pay wages and rent (<white><a></white> owed). Unpaid staff stop working"
                                    + (quit.isEmpty() ? "" : ", and <white><q></white> quit") + ". Deposit to pay what's owed.",
                            Msg.p("c", c.name()), Msg.p("a", plugin.settings().money(total)), Msg.p("q", String.join(", ", quit)));
                }
            }
        }
        plugin.store().markDirty();
    }

    /** After money comes in: pays owed wages and rent at once, and staff go back to work. */
    public void retryWages(Carrier c) {
        StaffState st = states.get(c.id());
        if (st == null || st.wagesOwed() <= 0 || c.balance() - st.wagesOwed() < upkeepReserve(c)) return;
        long owed = st.wagesOwed();
        c.balance(c.balance() - owed);
        c.log(-owed, "Owed wages & rent");
        st.wagesOwed(0);
        st.owedSince(0);
        for (Employee e : staffOf(c.id())) e.unpaidDays(0);
        plugin.billing().notify(c.owner(), "<green><c> paid its owed wages and rent. Everyone is back at work.", Msg.p("c", c.name()));
        plugin.store().markDirty();
    }

    // =========================================================== candidates

    public record Candidate(int index, Employee.Role role, String name, int level, long wage) {
    }

    /** Today's job seekers for one role at this company (3, stable for the day). */
    public List<Candidate> candidates(String companyId, Employee.Role role, long now) {
        long day = now / DAY;
        Random r = new Random(companyId.hashCode() * 1_000_003L + day * 31 + role.ordinal());
        Set<Integer> taken = hiredCandidates.getOrDefault(companyId + "|" + role.key + "|" + day, Set.of());
        List<Candidate> out = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String name = NAMES_FIRST[r.nextInt(NAMES_FIRST.length)] + " " + NAMES_LAST[r.nextInt(NAMES_LAST.length)];
            double roll = r.nextDouble();
            double[] odds = w().levelOdds();
            int level = roll < odds[0] ? 0 : roll < odds[0] + odds[1] ? 1 : 2;
            double ask = 0.9 + r.nextDouble() * 0.25;
            long wage = Math.round(w().wage(role, level) * ask);
            if (!taken.contains(i)) out.add(new Candidate(i, role, name, level, wage));
        }
        return out;
    }

    public long recruitFee(long wage) {
        return wage * w().recruitDays();
    }

    public long severance(Employee e) {
        return e.wage() * w().severanceDays();
    }

    /** Hires a candidate into the facility of their role with the most room. Null if it worked, else why not. */
    public String hire(Carrier c, Candidate cand, long now) {
        if (staffOf(c.id()).size() >= w().maxStaff()) return "You're at the staff limit (" + w().maxStaff() + ").";
        Facility.Type type = cand.role().facility();
        Facility best = null;
        for (Facility f : facilitiesOf(c.id())) {
            if (f.type() != type || freeBeds(f) <= 0) continue;
            if (best == null || freeBeds(f) > freeBeds(best)) best = f;
        }
        if (best == null) return "No room: build a " + type.display.toLowerCase() + " (one " + type.needs + ") or enlarge one.";
        long fee = recruitFee(cand.wage());
        if (c.balance() < fee) return "The recruitment fee is " + plugin.settings().money(fee) + ".";
        c.balance(c.balance() - fee);
        c.log(-fee, "Recruitment: " + cand.name());
        Employee e = new Employee(nextEmployeeId++, c.id(), cand.role(), cand.name(), cand.level(), cand.wage(), now);
        e.facilityId(best.id());
        employees.put(e.id(), e);
        hiredCandidates.computeIfAbsent(c.id() + "|" + cand.role().key + "|" + (now / DAY), k -> new HashSet<>()).add(cand.index());
        if (state(c.id()).nextPayday() == 0) state(c.id()).nextPayday(now + DAY);
        plugin.store().markDirty();
        return null;
    }

    /** Fires with severance. Null if it worked, else why not. */
    public String fire(Carrier c, Employee e) {
        long sev = severance(e);
        if (c.balance() < sev) return "Severance is " + plugin.settings().money(sev) + ".";
        c.balance(c.balance() - sev);
        c.log(-sev, "Severance: " + e.name());
        employees.remove(e.id());
        plugin.store().markDirty();
        return null;
    }

    /** Puts staff without a facility into stations with free beds. */
    private void placeIdle() {
        for (Employee e : employees.values()) {
            if (e.facilityId() != 0 && facilities.containsKey(e.facilityId())) continue;
            e.facilityId(0);
            for (Facility f : facilitiesOf(e.companyId())) {
                if (f.type() == e.role().facility() && freeBeds(f) > 0) {
                    e.facilityId(f.id());
                    break;
                }
            }
        }
    }

    // ============================================================ facilities

    /** Result of scanning the room around a facility sign. */
    public record Scan(String error, int beds, int barrels, int seats, int lecterns, int observers) {
        public boolean ok() { return error == null; }

        Scan(String error) {
            this(error, 0, 0, 0, 0, 0);
        }

        /** Staff places this room gives a facility of this type. */
        public int capacity(Facility.Type type) {
            return switch (type) {
                case STATION -> beds;
                case CALL_CENTRE -> Math.min(seats, lecterns);
                case NOC -> Math.min(seats, observers);
            };
        }
    }

    /**
     * Flood-fills the air in front of the sign. The room must be closed (the fill can't escape
     * within the size limit), roofed, and not hold another facility. Counts beds and barrels
     * touching the room.
     */
    public Scan scan(Block sign, Facility self) {
        World world = sign.getWorld();
        int limit = w().maxRoomBlocks();
        Set<BlockKey> air = new HashSet<>();
        ArrayDeque<Block> queue = new ArrayDeque<>();
        queue.add(sign);
        air.add(BlockKey.of(sign));
        Set<BlockKey> beds = new HashSet<>(), barrels = new HashSet<>(), seats = new HashSet<>(), lecterns = new HashSet<>(), observers = new HashSet<>();
        BlockFace[] dirs = {BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
        while (!queue.isEmpty()) {
            Block b = queue.poll();
            if (b != sign && world.getHighestBlockYAt(b.getX(), b.getZ(), HeightMap.MOTION_BLOCKING) <= b.getY()) {
                return new Scan("The room isn't closed or has no roof: the sky is visible from inside.");
            }
            for (BlockFace d : dirs) {
                Block n = b.getRelative(d);
                if (n.getY() < world.getMinHeight() || n.getY() >= world.getMaxHeight()) {
                    return new Scan("The room isn't closed.");
                }
                if (!world.isChunkLoaded(n.getX() >> 4, n.getZ() >> 4)) return new Scan("Part of the room isn't loaded. Stand inside it.");
                BlockKey k = BlockKey.of(n);
                if (air.contains(k)) continue;
                if (n.getBlockData() instanceof Bed bed) {
                    if (bed.getPart() == Bed.Part.HEAD) beds.add(k);
                    continue;
                }
                if (n.getType() == Material.BARREL) {
                    barrels.add(k);
                    continue;
                }
                if (n.getType() == Material.LECTERN) {
                    lecterns.add(k);
                    continue;
                }
                if (n.getType() == Material.OBSERVER) {
                    observers.add(k);
                    continue;
                }
                if (n.getBlockData() instanceof org.bukkit.block.data.type.Stairs) {
                    if (d != BlockFace.UP) seats.add(k); // stairs in the ceiling are not seats
                    continue;
                }
                if (!(n.getType().isAir() || n.isPassable())) continue;
                Facility other = facilityByBlock.get(k);
                if (other != null && other != self) return new Scan("This room already has a facility (" + other.name() + ").");
                if (plugin.store().towerAt(k) != null) return new Scan("A cell tower sign can't be inside a facility.");
                air.add(k);
                if (air.size() > limit) return new Scan("The room isn't closed, or is bigger than " + limit + " blocks. Close the doors and walls.");
                queue.add(n);
            }
        }
        return new Scan(null, beds.size(), barrels.size(), seats.size(), lecterns.size(), observers.size());
    }

    /** Registers a station. Null if it worked, else why not. */
    public String create(Player p, Block sign, Carrier c, Facility.Type type, String name) {
        if (!dev.mccomm.cell.tower.TowerManager.isWallSign(sign)) return "Look at a wall sign inside the building.";
        if (plugin.store().towerAt(BlockKey.of(sign)) != null) return "That sign is a cell tower.";
        if (facilityAt(sign) != null) return "That sign is already a facility.";
        if (facilityByName(c.id(), name) != null) return c.name() + " already has a facility called " + name + ".";
        Scan scan = scan(sign, null);
        if (!scan.ok()) return scan.error();
        if (scan.capacity(type) < 1) return "A " + type.display.toLowerCase() + " needs at least one " + type.needs + ".";
        Facility f = new Facility(nextFacilityId++, c.id(), type, name, sign.getWorld().getUID(),
                sign.getX(), sign.getY(), sign.getZ(), System.currentTimeMillis());
        f.capacity(scan.capacity(type));
        f.barrels(scan.barrels());
        f.checkedAt(System.currentTimeMillis());
        facilities.put(f.id(), f);
        facilityByBlock.put(f.key(), f);
        if (state(c.id()).nextPayday() == 0) state(c.id()).nextPayday(System.currentTimeMillis() + DAY);
        writeSign(f);
        plugin.store().markDirty();
        return null;
    }

    /** Re-scans a facility now. Null if fine, else the problem. */
    public String recheck(Facility f) {
        Block b = f.block();
        if (b == null || !f.isChunkLoaded()) return "The facility isn't loaded.";
        if (!dev.mccomm.cell.tower.TowerManager.isWallSign(b)) {
            remove(f, false);
            return "Its sign is gone, so it was removed.";
        }
        Scan scan = scan(b, f);
        f.checkedAt(System.currentTimeMillis());
        if (!scan.ok()) {
            f.capacity(0);
            f.barrels(0);
        } else {
            f.capacity(scan.capacity(f.type()));
            f.barrels(scan.barrels());
        }
        // Over capacity: the newest staff go idle.
        List<Employee> here = staffAt(f);
        here.sort(Comparator.comparingLong(Employee::hiredAt));
        for (int i = f.capacity(); i < here.size(); i++) here.get(i).facilityId(0);
        writeSign(f);
        plugin.store().markDirty();
        return scan.ok() ? null : scan.error();
    }

    private void recheckFacilities(long now) {
        for (Facility f : new ArrayList<>(facilities.values())) {
            if (!f.isChunkLoaded() || now - f.checkedAt() < w().recheckMinutes() * MINUTE) continue;
            int before = f.capacity();
            String err = recheck(f);
            Carrier c = plugin.store().carrier(f.companyId());
            if (c != null && (err != null || f.capacity() < before) && facilities.containsKey(f.id())) {
                plugin.billing().notify(c.owner(), "<gold>Station <white><f></white> now has <white><n></white> usable bed(s)<e>.",
                        Msg.p("f", f.name()), Msg.p("n", "" + f.capacity()), Msg.p("e", err == null ? "" : ": " + err));
            }
        }
    }

    public void remove(Facility f, boolean clearSign) {
        facilities.remove(f.id());
        facilityByBlock.remove(f.key());
        for (Employee e : employees.values()) if (e.facilityId() == f.id()) e.facilityId(0);
        if (clearSign && f.isChunkLoaded()) {
            Block b = f.block();
            if (b != null && b.getState() instanceof org.bukkit.block.Sign sign) {
                SignSide side = sign.getSide(Side.FRONT);
                for (int i = 0; i < 4; i++) side.line(i, Component.empty());
                sign.setWaxed(false);
                sign.update();
            }
        }
        placeIdle();
        plugin.store().markDirty();
    }

    public void writeSign(Facility f) {
        if (!f.isChunkLoaded()) return;
        Block b = f.block();
        if (b == null || !(b.getState() instanceof org.bukkit.block.Sign sign)) return;
        Carrier c = plugin.store().carrier(f.companyId());
        SignSide side = sign.getSide(Side.FRONT);
        side.line(0, Msg.mm("<dark_aqua>" + f.type().sign));
        side.line(1, c == null ? Component.text("?") : Msg.mm(c.styled()));
        side.line(2, Component.text(f.name()));
        side.line(3, Msg.mm("<dark_gray>Staff " + staffAt(f).size() + "/" + f.capacity()));
        sign.setWaxed(true);
        sign.update(false, false);
    }

    // =============================================================== cleanup

    /** A carrier was deleted or liquidated: its staff, sites and jobs go with it. */
    public void companyRemoved(String companyId) {
        for (Facility f : facilitiesOf(companyId)) remove(f, true);
        employees.values().removeIf(e -> e.companyId().equals(companyId));
        states.remove(companyId);
        plugin.store().markDirty();
    }

    public void towerRemoved(Tower t) {
        jobs.remove(t.id());
        lastStage.remove(t.id());
        removeFigure(t.id());
        for (Employee e : employees.values()) if (e.jobTower() == t.id()) e.jobTower(0);
    }

    /** What liquidation must settle first: wages and rent owed plus severance for everyone. */
    public long liquidationCost(String companyId) {
        StaffState st = states.get(companyId);
        long sum = st == null ? 0 : st.wagesOwed();
        for (Employee e : staffOf(companyId)) sum += severance(e);
        return sum;
    }

    // =============================================================== timewarp

    /** Moves every schedule back by {@code ms}, as if that time had passed. */
    public void shift(long ms) {
        for (Tower t : plugin.store().towers()) if (t.wearAt() > 0) t.wearAt(t.wearAt() - ms);
        for (Employee e : employees.values()) if (e.busyUntil() > 0) e.busyUntil(e.busyUntil() - ms);
        for (RepairJob j : jobs.values()) {
            j.queuedAt(j.queuedAt() - ms);
            if (j.repairAt() > 0) j.repairAt(j.repairAt() - ms);
        }
        for (StaffState s : states.values()) {
            if (s.nextPayday() > 0) s.nextPayday(s.nextPayday() - ms);
            if (s.owedSince() > 0) s.owedSince(s.owedSince() - ms);
            if (s.bankruptcyClock() > 0) s.bankruptcyClock(s.bankruptcyClock() - ms);
        }
        for (Facility f : facilities.values()) f.checkedAt(f.checkedAt() - ms);
    }

    // ============================================================= technicians

    /** When the technician on this job gets to the tower (for showing them there). */
    private long arriveAt(RepairJob j, Tower t) {
        if (j.contractor()) return j.repairAt() - Math.round(repairMinutes(t.wear(), 1, null) * MINUTE);
        Employee e = employees.get(j.technicianId());
        Facility f = facilities.get(j.stationId());
        int level = e == null ? 1 : e.level();
        return j.repairAt() - Math.round(repairMinutes(t.wear(), level, f) * MINUTE);
    }

    /** True while a technician is on site at this tower. */
    public boolean onSite(Tower t) {
        RepairJob j = jobs.get(t.id());
        long now = System.currentTimeMillis();
        return j != null && j.started() && now >= arriveAt(j, t) && now < j.repairAt();
    }

    private void updateFigures(long now) {
        boolean show = plugin.getConfig().getBoolean("workforce.visible-technicians", true);
        for (RepairJob j : jobs.values()) {
            Tower t = plugin.store().tower(j.towerId());
            if (t == null) continue;
            boolean there = show && j.started() && now >= arriveAt(j, t) && now < j.repairAt();
            UUID id = figures.get(t.id());
            Entity existing = id == null ? null : org.bukkit.Bukkit.getEntity(id);
            if (there && (existing == null || !existing.isValid()) && t.isChunkLoaded()) spawnFigure(t, j);
            else if (!there && existing != null) removeFigure(t.id());
        }
        figures.keySet().removeIf(tid -> {
            if (jobs.containsKey(tid)) return false;
            Entity e = org.bukkit.Bukkit.getEntity(figures.get(tid));
            if (e != null) e.remove();
            return true;
        });
    }

    private void spawnFigure(Tower t, RepairJob j) {
        World world = t.world();
        Block b = t.block();
        if (world == null || b == null || !(b.getBlockData() instanceof WallSign ws)) return;
        Block front = b.getRelative(ws.getFacing());
        int gy = world.getHighestBlockYAt(front.getX(), front.getZ(), HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
        Location loc = new Location(world, front.getX() + 0.5, gy, front.getZ() + 0.5);
        loc.setDirection(ws.getFacing().getOppositeFace().getDirection());
        Employee e = employees.get(j.technicianId());
        String name = j.contractor() ? "Contractor" : e == null ? "Technician" : e.name();
        try {
            Mannequin m = world.spawn(loc, Mannequin.class, x -> {
                x.setPersistent(false);
                x.setInvulnerable(true);
                x.setImmovable(true);
                x.setAI(false);
                x.setSilent(true);
                x.customName(Msg.mm("<gold>⚒ " + Msg.escape(name)));
                x.setCustomNameVisible(true);
                x.setDescription(Msg.mm(j.contractor() ? "<gray>Contractor" : "<gray>Field technician"));
                ItemStack helmet = new ItemStack(Material.LEATHER_HELMET);
                helmet.editMeta(LeatherArmorMeta.class, meta -> meta.setColor(Color.fromRGB(0xF2A900)));
                ItemStack vest = new ItemStack(Material.LEATHER_CHESTPLATE);
                vest.editMeta(LeatherArmorMeta.class, meta -> meta.setColor(Color.fromRGB(0xFF6A00)));
                x.getEquipment().setHelmet(helmet);
                x.getEquipment().setChestplate(vest);
                x.getEquipment().setItemInMainHand(new ItemStack(Material.IRON_PICKAXE));
                x.getPersistentDataContainer().set(techKey, PersistentDataType.INTEGER, t.id());
            });
            figures.put(t.id(), m.getUniqueId());
        } catch (Throwable ex) {
            plugin.getLogger().log(Level.FINE, "Couldn't show a technician", ex);
        }
    }

    private void removeFigure(int towerId) {
        UUID id = figures.remove(towerId);
        if (id == null) return;
        Entity e = org.bukkit.Bukkit.getEntity(id);
        if (e != null) e.remove();
    }

    /** Removes technician figures left over in loaded chunks (e.g. after a crash). */
    public void cleanEntities(List<Entity> entities) {
        for (Entity e : entities) {
            Integer tid = e.getPersistentDataContainer().get(techKey, PersistentDataType.INTEGER);
            if (tid != null && !e.getUniqueId().equals(figures.get(tid))) e.remove();
        }
    }

    public void shutdown() {
        for (UUID id : figures.values()) {
            Entity e = org.bukkit.Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
        figures.clear();
    }

    // ================================================================ storage

    public void load() {
        File file = new File(plugin.getDataFolder(), "workforce.yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        nextFacilityId = Math.max(1, y.getInt("next-facility", 1));
        nextEmployeeId = Math.max(1, y.getInt("next-employee", 1));
        ConfigurationSection fs = y.getConfigurationSection("facilities");
        if (fs != null) for (String key : fs.getKeys(false)) {
            ConfigurationSection s = fs.getConfigurationSection(key);
            try {
                Facility.Type type = Facility.Type.of(s.getString("type", "station"));
                Facility f = new Facility(Integer.parseInt(key), s.getString("company"), type == null ? Facility.Type.STATION : type,
                        s.getString("name"), UUID.fromString(s.getString("world")), s.getInt("x"), s.getInt("y"), s.getInt("z"),
                        s.getLong("created-at"));
                f.capacity(s.getInt("capacity"));
                f.barrels(s.getInt("barrels"));
                f.checkedAt(s.getLong("checked-at"));
                facilities.put(f.id(), f);
                facilityByBlock.put(f.key(), f);
                nextFacilityId = Math.max(nextFacilityId, f.id() + 1);
            } catch (Exception ex) {
                plugin.getLogger().log(Level.WARNING, "Skipping broken facility " + key, ex);
            }
        }
        ConfigurationSection es = y.getConfigurationSection("employees");
        if (es != null) for (String key : es.getKeys(false)) {
            ConfigurationSection s = es.getConfigurationSection(key);
            try {
                Employee e = new Employee(Integer.parseInt(key), s.getString("company"), Employee.Role.of(s.getString("role", "technician")),
                        s.getString("name"), s.getInt("level"), s.getLong("wage"), s.getLong("hired-at"));
                e.facilityId(s.getInt("facility"));
                e.unpaidDays(s.getInt("unpaid-days"));
                e.busyUntil(s.getLong("busy-until"));
                e.jobTower(s.getInt("job-tower"));
                e.jobsDone(s.getInt("jobs-done"));
                employees.put(e.id(), e);
                nextEmployeeId = Math.max(nextEmployeeId, e.id() + 1);
            } catch (Exception ex) {
                plugin.getLogger().log(Level.WARNING, "Skipping broken employee " + key, ex);
            }
        }
        ConfigurationSection js = y.getConfigurationSection("jobs");
        if (js != null) for (String key : js.getKeys(false)) {
            ConfigurationSection s = js.getConfigurationSection(key);
            RepairJob j = new RepairJob(Integer.parseInt(key), s.getLong("queued-at"));
            j.technicianId(s.getInt("technician"));
            j.stationId(s.getInt("station"));
            j.contractor(s.getBoolean("contractor"));
            j.repairAt(s.getLong("repair-at"));
            j.cost(s.getLong("cost"));
            j.notifiedBroke(s.getBoolean("notified"));
            jobs.put(j.towerId(), j);
        }
        ConfigurationSection ss = y.getConfigurationSection("companies");
        if (ss != null) for (String key : ss.getKeys(false)) {
            ConfigurationSection s = ss.getConfigurationSection(key);
            StaffState st = state(key);
            st.nextPayday(s.getLong("next-payday"));
            st.wagesOwed(s.getLong("owed"));
            st.owedSince(s.getLong("owed-since"));
            st.transitDue(s.getDouble("transit-due"));
            st.bankruptcyClock(s.getLong("bankruptcy-clock"));
            st.contractors(s.getBoolean("contractors", true));
        }
        // Drop anything whose company or tower no longer exists.
        facilities.values().removeIf(f -> {
            boolean gone = plugin.store().carrier(f.companyId()) == null;
            if (gone) facilityByBlock.remove(f.key());
            return gone;
        });
        employees.values().removeIf(e -> plugin.store().carrier(e.companyId()) == null);
        jobs.values().removeIf(j -> plugin.store().tower(j.towerId()) == null);
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("next-facility", nextFacilityId);
        y.set("next-employee", nextEmployeeId);
        for (Facility f : facilities.values()) {
            String p = "facilities." + f.id() + ".";
            y.set(p + "company", f.companyId());
            y.set(p + "type", f.type().key);
            y.set(p + "name", f.name());
            y.set(p + "world", f.worldId().toString());
            y.set(p + "x", f.x());
            y.set(p + "y", f.y());
            y.set(p + "z", f.z());
            y.set(p + "capacity", f.capacity());
            y.set(p + "barrels", f.barrels());
            y.set(p + "checked-at", f.checkedAt());
            y.set(p + "created-at", f.createdAt());
        }
        for (Employee e : employees.values()) {
            String p = "employees." + e.id() + ".";
            y.set(p + "company", e.companyId());
            y.set(p + "role", e.role().key);
            y.set(p + "name", e.name());
            y.set(p + "level", e.level());
            y.set(p + "wage", e.wage());
            y.set(p + "facility", e.facilityId());
            y.set(p + "unpaid-days", e.unpaidDays());
            y.set(p + "busy-until", e.busyUntil());
            y.set(p + "job-tower", e.jobTower());
            y.set(p + "jobs-done", e.jobsDone());
            y.set(p + "hired-at", e.hiredAt());
        }
        for (RepairJob j : jobs.values()) {
            String p = "jobs." + j.towerId() + ".";
            y.set(p + "queued-at", j.queuedAt());
            y.set(p + "technician", j.technicianId());
            y.set(p + "station", j.stationId());
            y.set(p + "contractor", j.contractor());
            y.set(p + "repair-at", j.repairAt());
            y.set(p + "cost", j.cost());
            y.set(p + "notified", j.notifiedBroke());
        }
        for (var e : states.entrySet()) {
            if (e.getValue().isDefault()) continue;
            String p = "companies." + e.getKey() + ".";
            y.set(p + "next-payday", e.getValue().nextPayday());
            y.set(p + "owed", e.getValue().wagesOwed());
            y.set(p + "owed-since", e.getValue().owedSince());
            y.set(p + "transit-due", Math.round(e.getValue().transitDue() * 1000) / 1000.0);
            y.set(p + "bankruptcy-clock", e.getValue().bankruptcyClock());
            y.set(p + "contractors", e.getValue().contractors());
        }
        try {
            y.save(new File(plugin.getDataFolder(), "workforce.yml"));
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Couldn't save workforce.yml", ex);
        }
    }

    private static final String[] NAMES_FIRST = {"Alex", "Sam", "Jordan", "Robin", "Casey", "Morgan", "Riley", "Jamie",
            "Taylor", "Avery", "Quinn", "Rowan", "Elliot", "Harper", "Kai", "Noel", "Sasha", "Remy", "Drew", "Blair",
            "Ari", "Dana", "Emery", "Frankie", "Jules", "Lane", "Milan", "Nico", "Parker", "Reese", "Skyler", "Toni"};
    private static final String[] NAMES_LAST = {"Fletcher", "Okafor", "Lindqvist", "Moreau", "Tanaka", "Novak", "Reyes",
            "Brennan", "Kowalski", "Haddad", "Silva", "Ivanova", "Mbeki", "Larsen", "Costa", "Nguyen", "Park", "Weber",
            "Dubois", "Rossi", "Kaur", "Schmidt", "Ortega", "Byrne", "Achebe", "Sato", "Holm", "Petrov", "Quint", "Vance"};
}
