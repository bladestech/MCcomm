package dev.mccomm.cell.signal;

import dev.mccomm.cell.CellPlugin;
import dev.mccomm.cell.band.Band;
import dev.mccomm.cell.model.Carrier;
import dev.mccomm.cell.model.Plan;
import dev.mccomm.cell.model.Tower;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Simulated users for roleplay: a per-carrier count, spread evenly across the network's towers,
 * each on a random one of the carrier's plans. They load towers and cause congestion like real
 * users, but never pay anything. The spread is seeded so it stays stable between updates.
 */
public final class SimulationService {
    private final CellPlugin plugin;

    public SimulationService(CellPlugin plugin) {
        this.plugin = plugin;
    }

    /** Towers a carrier's users connect to: its own, or its host's for an MVNO. */
    private List<Tower> network(Carrier c) {
        String net = c.id();
        if (c.mvno()) {
            var deal = plugin.partners().mvnoHost(c);
            if (deal == null || plugin.partners().suspended(c)) return List.of();
            net = deal.host().id();
        }
        List<Tower> out = new ArrayList<>();
        for (Tower t : plugin.store().towers()) {
            if (t.carrierId().equals(net) && t.online() && plugin.billing().decayStage(t) < plugin.settings().silentStage()
                    && !plugin.workforce().silent(t)) out.add(t);
        }
        return out;
    }

    private static List<Plan> plans(Carrier c) {
        return new ArrayList<>(c.plans().values());
    }

    /** Picks the plan for the next simulated user: a business plan for a share of them (if the carrier sells any), else a personal one. */
    private Plan pick(List<Plan> personal, List<Plan> business, Random rng) {
        boolean biz = !business.isEmpty() && (personal.isEmpty() || rng.nextDouble() < plugin.settings().businessShare);
        List<Plan> pool = biz ? business : personal;
        return pool.isEmpty() ? null : pool.get(rng.nextInt(pool.size()));
    }

    private static List<Plan> ofKind(List<Plan> all, boolean business) {
        List<Plan> out = new ArrayList<>();
        for (Plan p : all) if (p.business() == business) out.add(p);
        return out;
    }

    private static long seed(Carrier c) {
        return c.id().hashCode() * 31L + c.simulatedUsers();
    }

    /** Adds every carrier's simulated users into the per-tower, per-band usage counts. */
    public void addTo(Map<Integer, EnumMap<Band, Integer>> usage) {
        for (Carrier c : plugin.store().carriers()) {
            int n = c.simulatedUsers();
            if (n <= 0) continue;
            List<Tower> towers = network(c);
            if (towers.isEmpty()) continue;
            List<Plan> personal = ofKind(plans(c), false), business = ofKind(plans(c), true);
            Band mvnoCap = null;
            if (c.mvno()) {
                var deal = plugin.partners().mvnoHost(c);
                if (deal != null) mvnoCap = deal.agreement().maxBand();
            }
            Random planRng = new Random(seed(c));
            Random r = new Random(seed(c) ^ 0x5DEECE66DL);
            for (int k = 0; k < towers.size(); k++) {
                Tower t = towers.get(k);
                int here = n / towers.size() + (k < n % towers.size() ? 1 : 0);
                for (int i = 0; i < here; i++) {
                    Plan plan = pick(personal, business, planRng);
                    List<Band> allowed = new ArrayList<>();
                    for (Band b : t.enabledBands()) {
                        if (!plugin.towers().available(t, b)) continue;
                        if (plan != null && plan.maxBand() != null && b.ordinal() > plan.maxBand().ordinal()) continue;
                        if (mvnoCap != null && b.ordinal() > mvnoCap.ordinal()) continue;
                        allowed.add(b);
                    }
                    if (allowed.isEmpty()) continue;
                    Band band = allowed.get(r.nextInt(allowed.size()));
                    usage.computeIfAbsent(t.id(), x -> new EnumMap<>(Band.class)).merge(band, 1, Integer::sum);
                }
            }
        }
    }

    /** How many simulated users are on each of a carrier's plans (plan id → count). */
    public Map<String, Integer> planCounts(Carrier c) {
        Map<String, Integer> out = new LinkedHashMap<>();
        int n = c.simulatedUsers();
        List<Plan> personal = ofKind(plans(c), false), business = ofKind(plans(c), true);
        if (n <= 0 || (personal.isEmpty() && business.isEmpty())) return out;
        Random planRng = new Random(seed(c)); // same sequence addTo uses for plans
        for (int i = 0; i < n; i++) out.merge(pick(personal, business, planRng).id(), 1, Integer::sum);
        return out;
    }

    // ================================================================ revenue

    private final Map<String, Long> lastPaid = new java.util.HashMap<>();
    private final Map<String, Double> accrued = new java.util.HashMap<>();

    /** Timewarp support: moves the last payout times back by {@code ms}. */
    public void shift(long ms) {
        lastPaid.replaceAll((k, v) -> v - ms);
    }

    /** Credits per day a carrier's simulated users would pay (0 when revenue is off). */
    public double revenuePerDay(Carrier c) {
        if (!plugin.store().simulatedRevenue() || c.simulatedUsers() <= 0) return 0;
        if (network(c).isEmpty()) return 0; // no working towers: simulated users get no service and don't pay
        double perDay = 0;
        for (var e : planCounts(c).entrySet()) {
            Plan plan = c.plans().get(e.getKey());
            if (plan != null && plan.periodHours() > 0) perDay += e.getValue() * plan.price() * 24.0 / plan.periodHours();
        }
        return perDay;
    }

    /**
     * When simulated revenue is on, pays each carrier what its simulated users owe for the time
     * since the last payout (once an hour, one ledger line). Off = nothing happens.
     */
    public void payRevenue() {
        long now = System.currentTimeMillis();
        for (Carrier c : plugin.store().carriers()) {
            Long last = lastPaid.get(c.id());
            if (last == null || !plugin.store().simulatedRevenue() || c.simulatedUsers() <= 0) {
                lastPaid.put(c.id(), now);
                continue;
            }
            if (now - last < 3_600_000L) continue;
            double owed = accrued.getOrDefault(c.id(), 0.0) + revenuePerDay(c) * (now - last) / 86_400_000.0;
            long whole = (long) Math.floor(owed);
            accrued.put(c.id(), owed - whole);
            lastPaid.put(c.id(), now);
            if (whole <= 0) continue;
            c.balance(c.balance() + whole);
            c.log(whole, "Simulated users (" + c.simulatedUsers() + ")");
            if (c.mvno()) {
                var deal = plugin.partners().mvnoHost(c);
                if (deal != null) c.mvnoOwed(c.mvnoOwed() + (long) Math.ceil(whole * deal.agreement().sharePercent() / 100.0));
            }
            plugin.store().markDirty();
        }
    }

    public int towerCount(Carrier c) {
        return network(c).size();
    }
}
