package dev.mccomm.cell.workforce;

import dev.mccomm.cell.CellPlugin;
import dev.mccomm.cell.broadband.Isp;
import dev.mccomm.cell.model.Agreement;
import dev.mccomm.cell.model.BlockKey;
import dev.mccomm.cell.model.Carrier;
import dev.mccomm.cell.model.Company;
import dev.mccomm.cell.model.Tower;
import dev.mccomm.cell.util.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Operations: tower wear and repairs, and the virtual operations budget every carrier and ISP pays.
 * <ul>
 *   <li>Towers gain wear from the traffic they carry; worn towers slow down, lose bands, then go silent.</li>
 *   <li>Past the dispatch level a repair job is queued. Field crews take it at once (faster the better they're
 *       funded); with field crews below their minimum funding, a slow and expensive contractor does it.</li>
 *   <li>The budget has three parts (field crews, customer care, network ops). Each has a full cost that grows with
 *       the business; the owner picks how much of it to fund. It is billed hourly, pay-what-you-can, and every effect
 *       reads a 3-day moving average of what was really paid. Everything paid is spent (a sink).</li>
 * </ul>
 */
public final class WorkforceService {
    private static final long MINUTE = 60_000L, HOUR = 3_600_000L, DAY = 86_400_000L;

    private final CellPlugin plugin;
    private final Map<Integer, RepairJob> jobs = new LinkedHashMap<>(); // tower id -> job
    private final Map<String, OpsState> states = new HashMap<>();      // company ref key -> budget
    private final Map<Integer, Integer> lastStage = new HashMap<>();
    private final Map<String, double[]> fullCache = new HashMap<>();    // company ref key -> full cost per day, per part
    private final Set<BlockKey> staleSigns = new LinkedHashSet<>();      // old facility signs still to clear
    private final Map<String, double[]> migrate = new HashMap<>();       // carrier id -> {hadTech, supportCover, engineerCover}
    private Map<String, Integer> realSubs = new HashMap<>();
    private long frozenAt; // last tick while operations were switched off, 0 = running

    public WorkforceService(CellPlugin plugin) {
        this.plugin = plugin;
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
        return w().wearIdlePerDay() + w().wearPerSubscriberDay() * subscribers * (t.small() ? plugin.settings().smallCells.wearFactor() : 1);
    }

    /** Real data carried by a tower (called on data use): wear, and transit for the network carrying it. */
    public void addTraffic(Tower t, double mb) {
        if (!enabled() || t == null || mb <= 0) return;
        t.wear(t.wear() + mb / 1000.0 * w().wearPerGb());
        OpsState st = state(new Company.Ref(Company.Kind.CARRIER, t.carrierId()));
        st.transitDue(st.transitDue() + mb / 1000.0 * w().transitPerGb());
    }

    // =============================================================== queries

    public RepairJob job(Tower t) { return enabled() ? jobs.get(t.id()) : null; }
    public Collection<RepairJob> jobs() { return jobs.values(); }

    public OpsState state(Company.Ref ref) {
        return states.computeIfAbsent(ref.key(), k -> {
            double[] d = new double[OpsPart.values().length];
            for (OpsPart p : OpsPart.values()) d[p.ordinal()] = w().defaultFunding(p);
            return new OpsState(d);
        });
    }

    public OpsState state(Company c) {
        return state(c.ref());
    }

    /** The carrier's budget (older call sites pass a carrier id). */
    public OpsState state(String carrierId) {
        return state(new Company.Ref(Company.Kind.CARRIER, carrierId));
    }

    /** Money a carrier keeps back for its next upkeep (and MVNO share): paid before anything else. */
    public long upkeepReserve(Carrier c) {
        return plugin.billing().upkeepCost(c) + c.mvnoOwed();
    }

    private long upkeepReserve(Company c) {
        if (c instanceof Carrier car) return upkeepReserve(car);
        if (c instanceof Isp isp) return plugin.broadband().upkeepDue(isp);
        return 0;
    }

    /** Money kept back before paying for repairs: the next upkeep and the next hour of operations. */
    public long repairReserve(Carrier c) {
        return upkeepReserve(c) + (long) Math.ceil(hourlyBill(c));
    }

    // ============================================================ the budget

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
            if (a.kind() != Agreement.Kind.MVNO) continue;
            Carrier m = plugin.store().carrier(a.partnerId());
            if (m != null) n += subscribers(m);
        }
        return n;
    }

    /** Customers an ISP serves: active lines plus simulated households and businesses. */
    public int customers(Isp isp) {
        return plugin.broadband().customers(isp) + isp.simCustomers() + isp.simBusinesses();
    }

    /** What this part would cost per day at 100%, before any discount. */
    public double fullCost(Company c, OpsPart p) {
        double[] cached = fullCache.get(c.ref().key());
        return cached != null ? cached[p.ordinal()] : computeFull(c)[p.ordinal()];
    }

    private double[] computeFull(Company c) {
        double[] out = new double[OpsPart.values().length];
        WorkforceSettings s = w();
        if (c instanceof Carrier car) {
            int subs = subscribers(car);
            double beyond = Math.max(0, subs - s.selfService());
            if (!car.mvno()) {
                int macro = 0, small = 0;
                for (Tower t : plugin.store().towersOf(car.id())) {
                    if (t.small()) small++;
                    else macro++;
                }
                out[OpsPart.FIELD.ordinal()] = macro * s.fieldPerMacro() + small * s.fieldPerSmall()
                        + (macro + small > 0 ? subs * s.fieldPerSubscriber() : 0);
                out[OpsPart.NETWORK.ordinal()] = Math.max(0, networkSubscribers(car) - s.selfService()) * s.networkPerSubscriber();
            }
            out[OpsPart.CARE.ordinal()] = beyond * s.carePerSubscriber();
        } else if (c instanceof Isp isp) {
            int n = customers(isp);
            double beyond = Math.max(0, n - s.selfService());
            out[OpsPart.CARE.ordinal()] = beyond * s.ispCarePerCustomer();
            if (!isp.carrierBacked()) { // 5G Home: the carrier's crews and network do that work
                double upkeep = 0;
                for (var node : plugin.broadband().store().nodesOf(isp.id())) upkeep += plugin.broadband().nodeUpkeep(node);
                out[OpsPart.FIELD.ordinal()] = upkeep * s.ispFieldPerUpkeep() + n * s.ispFieldPerCustomer();
                out[OpsPart.NETWORK.ordinal()] = beyond * s.ispNetworkPerCustomer();
            }
        }
        return out;
    }

    /** Share of the full cost a company pays (parent companies get economies of scale). */
    public double costFactor(Company c) {
        return 1.0 - plugin.groups().opsDiscount(c);
    }

    /** What this part costs per day at the chosen funding. */
    public double cost(Company c, OpsPart p) {
        return state(c).target(p) * fullCost(c, p) * costFactor(c);
    }

    /** Daily transit for simulated customers' data (only while they pay, i.e. revenue is on). */
    public double simTransitPerDay(Carrier c) {
        if (!enabled() || !plugin.store().simulatedRevenue()) return 0;
        return c.simulatedUsers() * w().simGbPerDay() * w().transitPerGb();
    }

    /** Operations per day at the chosen funding, plus simulated transit. Real transit comes on top as it's used. */
    public long dailyCost(Company c) {
        if (!enabled()) return 0;
        double sum = 0;
        for (OpsPart p : OpsPart.values()) sum += cost(c, p);
        if (c instanceof Carrier car) sum += simTransitPerDay(car);
        return Math.round(sum);
    }

    /** The next hourly bill at the chosen funding, including transit owed. */
    public double hourlyBill(Company c) {
        if (!enabled()) return 0;
        return dailyCost(c) / 24.0 + state(c).transitDue();
    }

    /**
     * How well a part is really funded: the 3-day average of what was paid, 1.0 = fully. Parts with nothing to do (a
     * tiny carrier's care, an MVNO's network) count as fully funded. 1 while operations are switched off.
     */
    public double funding(Company c, OpsPart p) {
        if (!enabled() || c == null) return 1;
        return state(c).average(p);
    }

    /** Customer care as a 0..1 service score (the market's support score). */
    public double support(Company c) {
        return Math.min(1, funding(c, OpsPart.CARE));
    }

    /** Speed multiplier from network ops (1 when fully funded). */
    public double networkFactor(Carrier c) {
        if (c == null) return 1;
        return Math.max(w().networkMinSpeed(), Math.min(1, funding(c, OpsPart.NETWORK)));
    }

    public double networkFactor(Tower t) {
        return networkFactor(plugin.store().carrier(t.carrierId()));
    }

    /** True while field crews are funded enough to do repairs themselves. */
    public boolean crews(Company c) {
        return funding(c, OpsPart.FIELD) >= w().fieldMinFunding();
    }

    /** How much faster than standard crews work: the funding level, with diminishing returns above 100%. */
    public double crewSpeed(Company c) {
        double f = funding(c, OpsPart.FIELD);
        return f <= 1 ? Math.max(0.01, f) : 1 + (f - 1) * 0.8;
    }

    /** Every company with a budget: carriers and ISPs. */
    public List<Company> budgeted() {
        List<Company> out = new ArrayList<>(plugin.store().carriers());
        if (plugin.broadband() != null) out.addAll(plugin.broadband().store().isps());
        return out;
    }

    /** Charges the hourly bills that are due: pay what you can, transit first, then the three parts. */
    private void bill(long now) {
        double alpha = 1 - Math.exp(-1.0 / (w().averageDays() * 24));
        for (Company c : budgeted()) {
            OpsState st = state(c);
            if (st.nextBill() == 0) {
                st.nextBill(now + HOUR);
                if (st.nextLog() == 0) st.nextLog(now + DAY);
                continue;
            }
            for (int guard = 0; guard < 72 && now >= st.nextBill(); guard++) {
                st.nextBill(st.nextBill() + HOUR);
                double factor = costFactor(c);
                double[] want = new double[OpsPart.values().length];
                double partsBill = 0;
                for (OpsPart p : OpsPart.values()) {
                    want[p.ordinal()] = st.target(p) * fullCost(c, p) * factor / 24.0;
                    partsBill += want[p.ordinal()];
                }
                double realTransit = st.transitDue();
                double transit = realTransit + (c instanceof Carrier car ? simTransitPerDay(car) / 24.0 : 0);
                double total = partsBill + transit;
                // The next upkeep is kept back, except what a parent company stands behind. A member spends its
                // own wallet first and only borrows what it really can't pay.
                long keep = plugin.groups().ownReserve(c, upkeepReserve(c));
                plugin.groups().backBill(c, (long) Math.ceil(total + keep - c.balance()), "Operations");
                boolean paidUp = c instanceof Carrier car ? car.unpaidSince() == 0 : !(c instanceof Isp isp) || isp.paidUp();
                double available = paidUp ? Math.max(0, c.balance() - keep) : 0;
                double paid = Math.min(total, available);
                // Transit is paid first (traffic must be paid for); real transit not covered waits for the next bill.
                double transitPaid = Math.min(transit, paid);
                st.transitDue(realTransit - Math.min(realTransit, transitPaid));
                double ratio = partsBill <= 0 ? 1 : Math.min(1, (paid - transitPaid) / partsBill);
                for (OpsPart p : OpsPart.values()) {
                    double eff = fullCost(c, p) <= 0 ? 1 : st.target(p) * ratio;
                    st.lastPaid(p, eff);
                    st.average(p, st.average(p) + (eff - st.average(p)) * alpha);
                }
                double due = paid + st.carry();
                long charge = (long) Math.floor(due);
                st.carry(due - charge);
                if (charge > 0) {
                    c.balance(c.balance() - charge);
                    st.paidToday(st.paidToday() + charge);
                }
            }
            if (st.nextLog() == 0) st.nextLog(now + DAY);
            if (now >= st.nextLog()) {
                if (st.paidToday() > 0) c.log(-st.paidToday(), "Operations (last 24 h)");
                st.paidToday(0);
                while (now >= st.nextLog()) st.nextLog(st.nextLog() + DAY);
            }
        }
        plugin.store().markDirty();
        if (plugin.broadband() != null) plugin.broadband().store().markDirty();
    }

    // ================================================================= tick

    /** Runs with the billing tick (every minute, and every step of a timewarp). */
    public void tick(long now) {
        if (!enabled()) {
            // Switched off: freeze every schedule (wear, jobs, bills) so that switching it back on
            // doesn't backdate the whole off period at once.
            if (frozenAt > 0 && now > frozenAt) shift(-(now - frozenAt));
            frozenAt = now;
            return;
        }
        frozenAt = 0;
        countSubscribers();
        fullCache.clear();
        for (Company c : budgeted()) fullCache.put(c.ref().key(), computeFull(c));
        migrateStaff();
        clearStaleSigns();
        accrueWear(now);
        bill(now);
        runJobs(now);
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
                    plugin.billing().notify(c.owner(), "<dark_red>Tower <white><t></white> has worn out and gone silent.</dark_red> <gray>It needs a repair: fund your field crews (Operations) or wait for a contractor.",
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
                if (now >= j.repairAt()) finish(j, t);
                continue;
            }
            if (crews(c)) crewRepair(j, t, c, now);
            else if (state(c).contractors() && now - j.queuedAt() >= w().contractorAfterMinutes() * MINUTE) contractor(j, t, c, now);
        }
    }

    /** Minutes of on-site work for a repair at this wear by standard crews. */
    public double repairMinutes(double wear) {
        return w().repairBaseMinutes() + w().repairPerWearMinutes() * wear;
    }

    /** Repair time for this tower by standard crews (small cells are quicker). */
    public double repairMinutes(Tower t) {
        return repairMinutes(t.wear()) * (t.small() ? plugin.settings().smallCells.repairFactor() : 1);
    }

    /** How long this carrier's crews take on this tower, setting out included. */
    public double crewMinutes(Carrier c, Tower t) {
        return w().setOutMinutes() + repairMinutes(t) / crewSpeed(c);
    }

    public long partsCost(double wear) {
        return (long) Math.ceil(w().partsBase() + w().partsPerWear() * wear);
    }

    public long contractorCost(double wear) {
        double labour = w().contractorLabourPerDay() * repairMinutes(wear) / (24 * 60.0);
        return Math.max(w().contractorMin(), (long) Math.ceil(w().contractorMultiplier() * (partsCost(wear) + labour)));
    }

    private void crewRepair(RepairJob j, Tower t, Carrier c, long now) {
        long parts = partsCost(t.wear());
        if (!charge(c, j, parts, "Repair parts: " + t.name())) return; // can't pay: wait, don't call a contractor either
        j.contractor(false);
        j.repairAt(now + Math.round(crewMinutes(c, t) * MINUTE));
        j.cost(parts);
        plugin.towers().refresh(t);
        plugin.store().markDirty();
    }

    private void contractor(RepairJob j, Tower t, Carrier c, long now) {
        long cost = contractorCost(t.wear());
        if (!charge(c, j, cost, "Contractor repair: " + t.name())) return;
        j.contractor(true);
        j.repairAt(now + w().contractorDelayHours() * HOUR + Math.round(repairMinutes(t) * MINUTE));
        j.cost(cost);
        plugin.billing().notify(c.owner(), "<gray>Your field crews aren't funded, so a contractor was booked for tower <white><t></white> (<white><c></white>). They'll be there in about <white><h> h</white>.",
                Msg.p("t", t.name()), Msg.p("c", plugin.settings().money(cost)), Msg.p("h", "" + w().contractorDelayHours()));
        plugin.towers().refresh(t);
        plugin.store().markDirty();
    }

    private boolean charge(Carrier c, RepairJob j, long cost, String note) {
        long reserve = plugin.groups().ownReserve(c, repairReserve(c));
        plugin.groups().backBill(c, cost + reserve - c.balance(), "Repairs");
        if (c.balance() - cost < reserve) {
            if (!j.notifiedBroke()) {
                j.notifiedBroke(true);
                plugin.billing().notify(c.owner(), "<red>A repair for <c> (<white><a></white>) is on hold: <white><r></white> is kept back for upkeep and operations first. The tower keeps wearing until you deposit.",
                        Msg.p("c", c.name()), Msg.p("a", plugin.settings().money(cost)), Msg.p("r", plugin.settings().money(reserve)));
            }
            return false;
        }
        c.balance(c.balance() - cost);
        c.log(-cost, note);
        return true;
    }

    private void finish(RepairJob j, Tower t) {
        jobs.remove(t.id());
        t.wear(0);
        lastStage.put(t.id(), 0);
        plugin.towers().refresh(t);
        plugin.store().markDirty();
    }

    // =============================================================== cleanup

    /** A company was deleted or liquidated: its budget and jobs go with it. */
    public void companyRemoved(Company.Ref ref) {
        states.remove(ref.key());
        fullCache.remove(ref.key());
        plugin.store().markDirty();
    }

    public void companyRemoved(String carrierId) {
        companyRemoved(new Company.Ref(Company.Kind.CARRIER, carrierId));
    }

    /**
     * Sets every tower's wear back to 0 and cancels all repair jobs.
     * Used when switching operations off for good. Returns how many towers had wear.
     */
    public int resetWear() {
        int worn = 0;
        for (Tower t : plugin.store().towers()) {
            if (t.wear() > 0) worn++;
            t.wear(0);
        }
        jobs.clear();
        lastStage.clear();
        plugin.store().markDirty();
        plugin.towers().refreshAll();
        return worn;
    }

    public void towerRemoved(Tower t) {
        jobs.remove(t.id());
        lastStage.remove(t.id());
    }

    // =============================================================== timewarp

    /** Moves every schedule back by {@code ms}, as if that time had passed. */
    public void shift(long ms) {
        for (Tower t : plugin.store().towers()) if (t.wearAt() > 0) t.wearAt(t.wearAt() - ms);
        for (RepairJob j : jobs.values()) {
            j.queuedAt(j.queuedAt() - ms);
            if (j.repairAt() > 0) j.repairAt(j.repairAt() - ms);
        }
        for (OpsState s : states.values()) {
            if (s.nextBill() > 0) s.nextBill(s.nextBill() - ms);
            if (s.nextLog() > 0) s.nextLog(s.nextLog() - ms);
            if (s.bankruptcyClock() > 0) s.bankruptcyClock(s.bankruptcyClock() - ms);
        }
    }

    // ============================================================= migration

    /** Starting funding for carriers that had staff before operations went virtual (from their old staff). */
    private void migrateStaff() {
        if (migrate.isEmpty()) return;
        for (var e : migrate.entrySet()) {
            Carrier c = plugin.store().carrier(e.getKey());
            if (c == null) continue;
            double[] m = e.getValue();
            OpsState st = state(c);
            int subs = subscribers(c), net = networkSubscribers(c);
            double care = Math.max(0, subs - w().selfService()), network = Math.max(0, net - w().selfService());
            set(st, OpsPart.FIELD, m[0] > 0 ? 1 : 0);
            set(st, OpsPart.CARE, care <= 0 ? 1 : Math.min(1, m[1] / care));
            set(st, OpsPart.NETWORK, network <= 0 ? 1 : Math.min(1, m[2] / network));
            plugin.billing().notify(c.owner(), "<aqua>Operations are virtual now.</aqua> <gray>Stations and staff are gone; <white><c></white>'s budget starts at field crews <white><f></white>, care <white><s></white>, network ops <white><n></white>. Change it in the carrier menu → Operations.",
                    Msg.p("c", c.name()), Msg.p("f", pct(st.target(OpsPart.FIELD))), Msg.p("s", pct(st.target(OpsPart.CARE))),
                    Msg.p("n", pct(st.target(OpsPart.NETWORK))));
        }
        migrate.clear();
        plugin.store().markDirty();
    }

    private static void set(OpsState st, OpsPart p, double v) {
        st.target(p, Math.round(v * 10) / 10.0);
        st.average(p, st.target(p));
        st.lastPaid(p, st.target(p));
    }

    public static String pct(double v) {
        return Math.round(v * 100) + "%";
    }

    /** Clears the text of old facility signs once their chunk is loaded. */
    private void clearStaleSigns() {
        if (staleSigns.isEmpty()) return;
        boolean changed = false;
        for (var it = staleSigns.iterator(); it.hasNext(); ) {
            BlockKey k = it.next();
            World world = Bukkit.getWorld(k.world());
            if (world == null) {
                it.remove();
                changed = true;
                continue;
            }
            if (!world.isChunkLoaded(k.x() >> 4, k.z() >> 4)) continue;
            Block b = world.getBlockAt(k.x(), k.y(), k.z());
            if (b.getState() instanceof org.bukkit.block.Sign sign) {
                SignSide side = sign.getSide(Side.FRONT);
                String first = PlainTextComponentSerializer.plainText().serialize(side.line(0));
                if (first.startsWith("[") && (first.contains("Station") || first.contains("Call") || first.contains("NOC"))) {
                    for (int i = 0; i < 4; i++) side.line(i, Component.empty());
                    sign.setWaxed(false);
                    sign.update(false, false);
                }
            }
            it.remove();
            changed = true;
        }
        if (changed) plugin.store().markDirty();
    }

    // ================================================================ storage

    public void load() {
        File file = new File(plugin.getDataFolder(), "workforce.yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection js = y.getConfigurationSection("jobs");
        if (js != null) for (String key : js.getKeys(false)) {
            ConfigurationSection s = js.getConfigurationSection(key);
            RepairJob j = new RepairJob(Integer.parseInt(key), s.getLong("queued-at"));
            j.contractor(s.getBoolean("contractor"));
            j.repairAt(s.getLong("repair-at"));
            j.cost(s.getLong("cost"));
            j.notifiedBroke(s.getBoolean("notified"));
            jobs.put(j.towerId(), j);
        }
        ConfigurationSection os = y.getConfigurationSection("operations");
        if (os != null) for (String key : os.getKeys(false)) {
            ConfigurationSection s = os.getConfigurationSection(key);
            Company.Ref ref = Company.Ref.parse(key);
            if (s == null || ref == null) continue;
            OpsState st = state(ref);
            for (OpsPart p : OpsPart.values()) {
                st.target(p, s.getDouble("target." + p.key, w().defaultFunding(p)));
                st.average(p, s.getDouble("average." + p.key, st.target(p)));
                st.lastPaid(p, s.getDouble("last." + p.key, st.average(p)));
            }
            st.nextBill(s.getLong("next-bill"));
            st.transitDue(s.getDouble("transit-due"));
            st.carry(s.getDouble("carry"));
            st.paidToday(s.getLong("paid-today"));
            st.nextLog(s.getLong("next-log"));
            st.bankruptcyClock(s.getLong("bankruptcy-clock"));
            st.contractors(s.getBoolean("contractors", true));
        }
        for (String k : y.getStringList("stale-signs")) {
            String[] p = k.split(",");
            try {
                staleSigns.add(new BlockKey(UUID.fromString(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3])));
            } catch (Exception ignored) {
            }
        }
        migrateOld(y);
        jobs.values().removeIf(j -> plugin.store().tower(j.towerId()) == null);
    }

    /** Reads the staff, facilities and payday state saved before operations went virtual. */
    private void migrateOld(YamlConfiguration y) {
        ConfigurationSection fs = y.getConfigurationSection("facilities");
        if (fs != null) for (String key : fs.getKeys(false)) {
            ConfigurationSection s = fs.getConfigurationSection(key);
            try {
                staleSigns.add(new BlockKey(UUID.fromString(s.getString("world")), s.getInt("x"), s.getInt("y"), s.getInt("z")));
            } catch (Exception ignored) {
            }
        }
        ConfigurationSection es = y.getConfigurationSection("employees");
        if (es != null) {
            var cfg = plugin.getConfig();
            double[] prod = {cfg.getDouble("workforce.junior-productivity", 0.75), 1.0, cfg.getDouble("workforce.senior-productivity", 1.35)};
            int supportEach = cfg.getInt("workforce.support.subscribers-each", 60), engineerEach = cfg.getInt("workforce.engineers.subscribers-each", 100);
            for (String key : es.getKeys(false)) {
                ConfigurationSection s = es.getConfigurationSection(key);
                if (s == null) continue;
                double[] m = migrate.computeIfAbsent(s.getString("company", "?"), k -> new double[3]);
                double p = prod[Math.clamp(s.getInt("level", 1), 0, 2)];
                switch (s.getString("role", "technician")) {
                    case "support" -> m[1] += p * supportEach;
                    case "engineer" -> m[2] += p * engineerEach;
                    default -> m[0] += 1;
                }
            }
        }
        ConfigurationSection ss = y.getConfigurationSection("companies");
        if (ss != null) for (String key : ss.getKeys(false)) {
            ConfigurationSection s = ss.getConfigurationSection(key);
            if (s == null) continue;
            OpsState st = state(key);
            st.transitDue(s.getDouble("transit-due"));
            st.bankruptcyClock(s.getLong("bankruptcy-clock"));
            st.contractors(s.getBoolean("contractors", true));
            // Wages owed are forgiven: there are no staff left to pay.
        }
        if (fs != null || es != null || ss != null) {
            plugin.getLogger().info("Operations are virtual now: converted " + (es == null ? 0 : es.getKeys(false).size())
                    + " staff and " + (fs == null ? 0 : fs.getKeys(false).size()) + " facilities into operations budgets.");
            plugin.store().markDirty();
        }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (RepairJob j : jobs.values()) {
            String p = "jobs." + j.towerId() + ".";
            y.set(p + "queued-at", j.queuedAt());
            y.set(p + "contractor", j.contractor());
            y.set(p + "repair-at", j.repairAt());
            y.set(p + "cost", j.cost());
            y.set(p + "notified", j.notifiedBroke());
        }
        for (var e : states.entrySet()) {
            OpsState st = e.getValue();
            String p = "operations." + e.getKey() + ".";
            for (OpsPart part : OpsPart.values()) {
                y.set(p + "target." + part.key, st.target(part));
                y.set(p + "average." + part.key, Math.round(st.average(part) * 10000) / 10000.0);
                y.set(p + "last." + part.key, Math.round(st.lastPaid(part) * 10000) / 10000.0);
            }
            y.set(p + "next-bill", st.nextBill());
            y.set(p + "transit-due", Math.round(st.transitDue() * 1000) / 1000.0);
            y.set(p + "carry", Math.round(st.carry() * 1000) / 1000.0);
            y.set(p + "paid-today", st.paidToday());
            y.set(p + "next-log", st.nextLog());
            y.set(p + "bankruptcy-clock", st.bankruptcyClock());
            y.set(p + "contractors", st.contractors());
        }
        if (!staleSigns.isEmpty()) {
            List<String> out = new ArrayList<>();
            for (BlockKey k : staleSigns) out.add(k.world() + "," + k.x() + "," + k.y() + "," + k.z());
            y.set("stale-signs", out);
        }
        dev.mccomm.cell.util.YamlFiles.save(y, new File(plugin.getDataFolder(), "workforce.yml"), plugin.getLogger());
    }

    public void shutdown() {
        // Nothing to clean up in the world: operations are virtual.
    }
}
