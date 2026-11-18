package dev.mccomm.cell.signal;

import dev.mccomm.cell.CellPlugin;
import dev.mccomm.cell.Settings;
import dev.mccomm.cell.band.BackhaulSpec;
import dev.mccomm.cell.band.Band;
import dev.mccomm.cell.band.BandSpec;
import dev.mccomm.cell.model.Carrier;
import dev.mccomm.cell.model.PlayerProfile;
import dev.mccomm.cell.model.Tower;
import dev.mccomm.cell.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Works out, for every online player, which tower and band each active SIM is on.
 * Results are cached and refreshed every {@code signal.update-interval-ticks}.
 */
public final class SignalService {

    /** A radio link to one tower on one band. */
    public record Link(Tower tower, Band band, double dbm, int bars, double distance) {
    }

    /** One active SIM. {@code link} is null when there's no service (no coverage, no plan or no phone). {@code plan} null = no plan. */
    /**
     * @param roamingOn    the partner network this SIM is roaming on, or null (home or MVNO host)
     * @param deprioritized the host puts this user behind its own customers when a band is busy
     */
    public record SimStatus(int slot, Carrier carrier, Link link, dev.mccomm.cell.model.Plan plan, String issue, boolean throttled,
                            Carrier roamingOn, boolean deprioritized) {
        public boolean texts() { return link != null && plan != null && plan.texts(); }
        public boolean calls() { return link != null && plan != null && plan.calls(); }
    }

    /**
     * @param sims      active SIMs in slot order
     * @param emergency  true if any tower of any carrier reaches the player (emergency calls work)
     * @param dataCarrier the player's chosen data SIM (carrier id), or null for automatic
     * @param phone       the phone in use, or null (no phone = no service at all)
     */
    public record Status(List<SimStatus> sims, boolean emergency, String dataCarrier, dev.mccomm.cell.economy.PhoneModel phone) {
        public static final Status NONE = new Status(List.of(), false, null, null);

        /** The SIM for texts (calls=false) or calls (calls=true): the data SIM if its plan allows it, else another that does. */
        public SimStatus outgoing(boolean calls) {
            SimStatus p = primary();
            if (p != null && (calls ? p.calls() : p.texts())) return p;
            for (SimStatus s : sims) if (calls ? s.calls() : s.texts()) return s;
            return null;
        }

        /** The SIM used for data and outgoing calls/texts: the chosen data SIM, or the first with service if it has none. */
        public SimStatus primary() {
            if (dataCarrier != null) {
                for (SimStatus s : sims) if (s.link() != null && s.carrier().id().equals(dataCarrier)) return s;
            }
            for (SimStatus s : sims) if (s.link() != null) return s;
            return null;
        }

        /** True if this SIM is the one chosen for data (or is the automatic pick when nothing is chosen). */
        public boolean isDataSim(SimStatus s) {
            if (dataCarrier != null && sims.stream().anyMatch(x -> x.carrier().id().equals(dataCarrier))) {
                return s.carrier().id().equals(dataCarrier);
            }
            SimStatus p = primary();
            return p != null ? p == s : !sims.isEmpty() && sims.getFirst() == s;
        }

        public boolean hasService() {
            return primary() != null;
        }

        public SimStatus slot(int slot) {
            for (SimStatus s : sims) if (s.slot() == slot) return s;
            return null;
        }
    }

    private final CellPlugin plugin;
    private final Map<UUID, Status> status = new ConcurrentHashMap<>();
    private Map<Integer, EnumMap<Band, Integer>> usage = new HashMap<>();     // real players
    private Map<Integer, EnumMap<Band, Integer>> simUsage = new HashMap<>();  // simulated users
    private final Map<Integer, Integer> shownUsers = new HashMap<>();

    public SignalService(CellPlugin plugin) {
        this.plugin = plugin;
    }

    private Settings s() {
        return plugin.settings();
    }

    public Status status(Player p) {
        return status.getOrDefault(p.getUniqueId(), Status.NONE);
    }

    public Status status(UUID id) {
        return status.getOrDefault(id, Status.NONE);
    }

    public void clear(UUID id) {
        status.remove(id);
    }

    public int usersOn(Tower t) {
        EnumMap<Band, Integer> m = usage.get(t.id());
        if (m == null) return 0;
        int sum = 0;
        for (int v : m.values()) sum += v;
        return sum;
    }

    public int usersOn(Tower t, Band b) {
        EnumMap<Band, Integer> m = usage.get(t.id());
        return m == null ? 0 : m.getOrDefault(b, 0);
    }

    /** Simulated users on a tower (all bands). */
    public int simOn(Tower t) {
        EnumMap<Band, Integer> m = simUsage.get(t.id());
        if (m == null) return 0;
        int sum = 0;
        for (int v : m.values()) sum += v;
        return sum;
    }

    public int simOn(Tower t, Band b) {
        EnumMap<Band, Integer> m = simUsage.get(t.id());
        return m == null ? 0 : m.getOrDefault(b, 0);
    }

    /** Load on one band for congestion: real users plus simulated users at their (much lighter) weight. */
    public double load(Tower t, Band b) {
        return usersOn(t, b) + simOn(t, b) * s().simulationWeight;
    }

    /** Load on the whole tower (for the load meter). */
    public double load(Tower t) {
        return usersOn(t) + simOn(t) * s().simulationWeight;
    }

    public void forget(Tower t) {
        usage.remove(t.id());
        simUsage.remove(t.id());
        shownUsers.remove(t.id());
    }

    // ------------------------------------------------------------------ update

    public void tick() {
        Map<Integer, EnumMap<Band, Integer>> newUsage = new HashMap<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Status st = compute(p);
            status.put(p.getUniqueId(), st);
            SimStatus prim = st.primary();
            if (prim != null) {
                newUsage.computeIfAbsent(prim.link().tower().id(), k -> new EnumMap<>(Band.class))
                        .merge(prim.link().band(), 1, Integer::sum);
            }
            plugin.hud().update(p, st);
            plugin.phone().refreshIfOpen(p);
        }
        Map<Integer, EnumMap<Band, Integer>> newSim = new HashMap<>();
        plugin.simulation().addTo(newSim);
        usage = newUsage;
        simUsage = newSim;

        // Refresh screens whose user counts changed.
        for (Tower t : plugin.store().towers()) {
            int now = usersOn(t) * 100_003 + simOn(t);
            Integer before = shownUsers.put(t.id(), now);
            if (before != null && before != now) plugin.towers().refresh(t);
            else if (before == null && now != 0) plugin.towers().refresh(t);
        }

        plugin.calls().onSignalUpdate();
        plugin.sms().onSignalUpdate();
    }

    public Status compute(Player p) {
        PlayerProfile prof = plugin.store().profile(p.getUniqueId());
        dev.mccomm.cell.economy.PhoneModel phone = plugin.billing().phoneOf(p);
        boolean emergency = phone != null && !bestLinks(p, cid -> phone.maxBand()).isEmpty();
        Location eye = p.getEyeLocation();
        double env = environmentLoss(p);
        List<SimStatus> sims = new ArrayList<>();
        boolean blacklisted = phone != null && prof.blacklist().containsKey(phone.id());
        String lock = phone == null ? null : prof.phoneLocks().get(phone.id());
        int slot = 1;
        for (String id : prof.active()) {
            Carrier c = plugin.store().carrier(id);
            dev.mccomm.cell.model.Plan plan = plugin.billing().planOf(prof, id);
            int thisSlot = slot++;
            if (c == null) continue;
            String issue = null;
            if (phone == null) issue = "No phone";
            else if (plugin.billing().paperweight(prof, phone)) issue = "Phone is a paperweight";
            else if (blacklisted) issue = "Phone blacklisted";
            else if (lock != null && !lock.equals(id)) {
                Carrier lc = plugin.store().carrier(lock);
                issue = "Phone locked to " + (lc == null ? lock : lc.name());
            } else if (plan == null) issue = "No plan";
            else if (plugin.billing().expired(prof, id)) issue = "Prepaid expired";

            // Home network: the carrier's own towers, or its host's for an MVNO.
            Carrier home = c;
            Band cap = phone == null ? Band.G2 : phone.maxBand();
            if (plan != null && plan.maxBand() != null && plan.maxBand().ordinal() < cap.ordinal()) cap = plan.maxBand();
            boolean deprioritized = false;
            if (issue == null && c.mvno()) {
                var deal = plugin.partners().mvnoHost(c);
                if (deal == null) issue = "No network";
                else if (plugin.partners().suspended(c)) issue = "Network suspended";
                else {
                    home = deal.host();
                    cap = min(cap, deal.agreement().maxBand());
                    deprioritized = deal.agreement().deprioritize();
                }
            }
            Link link = null;
            Carrier roamingOn = null;
            if (issue == null) {
                link = bestOn(home.id(), eye, env, cap, p.getWorld().getUID());
                // No home coverage: fall back to roaming partners (if the plan allows and the carrier can pay).
                if (link == null && plan.roaming() && c.balance() > 0) {
                    for (var deal : plugin.partners().roamingHosts(c)) {
                        Link l = bestOn(deal.host().id(), eye, env, min(cap, deal.agreement().maxBand()), p.getWorld().getUID());
                        if (l != null && (link == null || better(l, link))) {
                            link = l;
                            roamingOn = deal.host();
                            deprioritized = deal.agreement().deprioritize();
                        }
                    }
                }
            }
            sims.add(new SimStatus(thisSlot, c, link, plan, issue, issue == null && plugin.billing().throttled(prof, id),
                    roamingOn, link != null && deprioritized));
        }
        return new Status(List.copyOf(sims), emergency, prof.dataSim(), phone);
    }

    private static Band min(Band a, Band b) {
        if (b == null) return a;
        return b.ordinal() < a.ordinal() ? b : a;
    }

    /** Best link on one network's towers. */
    private Link bestOn(String networkId, Location eye, double env, Band cap, java.util.UUID world) {
        Link best = null;
        for (Tower t : plugin.store().towers()) {
            if (!t.carrierId().equals(networkId) || !t.online() || !t.worldId().equals(world)) continue;
            Link l = linkTo(t, eye, env, cap);
            if (l != null && (best == null || better(l, best))) best = l;
        }
        return best;
    }

    /** Coverage per carrier here, limited by the player's phone (all bands if they have none). */
    public Map<String, Link> bestLinks(Player p) {
        dev.mccomm.cell.economy.PhoneModel phone = plugin.billing().phoneOf(p);
        Band cap = phone == null ? Band.G5_MMWAVE : phone.maxBand();
        return bestLinks(p, cid -> cap);
    }

    /** Best link per carrier at the player's position; {@code capFor} gives the highest band allowed per carrier. */
    public Map<String, Link> bestLinks(Player p, java.util.function.Function<String, Band> capFor) {
        World w = p.getWorld();
        Location eye = p.getEyeLocation();
        double env = environmentLoss(p);

        Map<String, Link> best = new HashMap<>();
        for (Tower t : plugin.store().towers()) {
            if (!t.online() || !t.worldId().equals(w.getUID())) continue;
            Band cap = capFor.apply(t.carrierId());
            if (cap == null) continue;
            Link l = linkTo(t, eye, env, cap);
            if (l == null) continue;
            Link cur = best.get(t.carrierId());
            if (cur == null || better(l, cur)) best.put(t.carrierId(), l);
        }
        return best;
    }

    private static boolean better(Link a, Link b) {
        if (a.band() != b.band()) return a.band().ordinal() > b.band().ordinal();
        return a.dbm() > b.dbm();
    }

    /** Best band this tower can give at {@code eye}, falling back to lower bands as range runs out. */
    public Link linkTo(Tower t, Location eye, double envLoss, Band cap) {
        Location ant = t.antenna(s().antennaHeight);
        if (ant == null) return null;
        int stage = plugin.billing().decayStage(t);
        if (stage >= s().silentStage()) return null; // unpaid upkeep: tower is silent
        if (plugin.workforce().silent(t)) return null; // worn out: needs a repair
        double dist = eye.distance(ant);
        int dropped = Math.max(plugin.billing().decayDroppedBands(t), plugin.workforce().wearDroppedBands(t));

        double mult = s().rangeMultiplier(t.heightAboveGround());
        double maxRange = 0;
        for (Band b : t.enabledBands()) {
            if (s().supports(t.generation(), b)) maxRange = Math.max(maxRange, s().spec(b).range() * mult);
        }
        if (dist > maxRange) return null;

        int obstructions = -1; // computed lazily
        Band[] bands = Band.values();
        for (int i = bands.length - 1; i >= 0; i--) {
            Band b = bands[i];
            if (!t.enabledBands().contains(b) || !s().supports(t.generation(), b)) continue;
            if (dropped > 0) { // decay shuts off the tower's highest bands first
                dropped--;
                continue;
            }
            if (b.ordinal() > cap.ordinal()) continue; // phone or plan can't use this band
            BandSpec spec = s().spec(b);
            double range = spec.range() * mult;
            if (dist > range) continue;
            if (obstructions < 0) obstructions = countObstructions(eye, ant, dist);
            double ratio = dist / range;
            double dbm = s().bestDbm + (s().cutoffDbm - s().bestDbm) * Math.pow(ratio, s().falloffExponent)
                    - Math.min(obstructions * spec.obstructionDb(), spec.maxObstructionDb()) - envLoss;
            if (dbm >= s().cutoffDbm) return new Link(t, b, dbm, bars(dbm), dist);
        }
        return null;
    }

    private double environmentLoss(Player p) {
        World w = p.getWorld();
        double loss = 0;
        if (w.isThundering()) loss += s().thunderDb;
        else if (w.hasStorm()) loss += s().rainDb;
        Location l = p.getEyeLocation();
        if (w.isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) {
            int top = w.getHighestBlockYAt(l.getBlockX(), l.getBlockZ(), HeightMap.MOTION_BLOCKING_NO_LEAVES);
            double depth = top - l.getY() - s().undergroundGrace;
            if (depth > 0) loss += Math.min(s().undergroundMaxDb, depth * s().undergroundDbPerBlock);
        }
        return loss;
    }

    /** Counts solid blocks on the line towards the tower within a few blocks of the player (walls around you). */
    private int countObstructions(Location from, Location to, double dist) {
        World w = from.getWorld();
        Vector dir = to.toVector().subtract(from.toVector());
        if (dir.lengthSquared() < 1e-6) return 0;
        dir.normalize().multiply(0.5);
        double limit = Math.min(dist - 1.5, s().obstructionScanDistance);
        int count = 0;
        int lx = Integer.MIN_VALUE, ly = 0, lz = 0;
        double x = from.getX(), y = from.getY(), z = from.getZ();
        for (double travelled = 0; travelled < limit; travelled += 0.5) {
            x += dir.getX();
            y += dir.getY();
            z += dir.getZ();
            int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
            if (bx == lx && by == ly && bz == lz) continue;
            lx = bx;
            ly = by;
            lz = bz;
            if (by < w.getMinHeight() || by >= w.getMaxHeight()) continue;
            if (!w.isChunkLoaded(bx >> 4, bz >> 4)) break;
            if (w.getBlockAt(bx, by, bz).getType().isOccluding()) {
                if (++count >= s().maxObstructionBlocks) break;
            }
        }
        return count;
    }

    public int bars(double dbm) {
        double[] th = s().barThresholds;
        if (dbm >= th[0]) return 4;
        if (dbm >= th[1]) return 3;
        if (dbm >= th[2]) return 2;
        return 1;
    }

    // ------------------------------------------------------------- throughput

    /** 0..1 quality from the dBm, used to scale speed. */
    public double signalFactor(double dbm) {
        double q = (dbm - s().cutoffDbm) / (s().bestDbm - s().cutoffDbm);
        q = Math.clamp(q, 0, 1);
        return s().minSignalFactor + (1 - s().minSignalFactor) * q;
    }

    public double backhaulMultiplier(Tower t) {
        BackhaulSpec bh = t.backhaul() == null ? null : s().backhaul.get(t.backhaul());
        return bh == null ? 0 : bh.multiplier();
    }

    /** Download speed in Mbps for this link, after backhaul, signal and congestion. */
    public double downloadMbps(Link l) {
        BandSpec spec = s().spec(l.band());
        double users = Math.max(1, load(l.tower(), l.band()));
        int cap = s().capacity(l.tower().generation());
        double share = users <= cap ? 1 : cap / users;
        return spec.maxMbps() * backhaulMultiplier(l.tower()) * signalFactor(l.dbm()) * share
                * Math.min(plugin.billing().decaySpeed(l.tower()), plugin.workforce().wearSpeed(l.tower()))
                * plugin.workforce().engineerSpeed(l.tower());
    }

    public double uploadMbps(Link l) {
        return downloadMbps(l) * s().spec(l.band()).uploadRatio();
    }

    /** Download for a SIM, after its plan's speed cap. */
    public double downloadMbps(SimStatus sim) {
        double d = downloadMbps(sim.link());
        if (sim.plan() != null && sim.plan().speedCapMbps() > 0) d = Math.min(d, sim.plan().speedCapMbps());
        if (sim.throttled()) d = Math.min(d, s().throttleMbps);
        if (sim.deprioritized() && load(sim.link().tower(), sim.link().band()) > s().capacity(sim.link().tower().generation())) d *= s().deprioritizedSpeed;
        return d;
    }

    public double uploadMbps(SimStatus sim) {
        double ratio = s().spec(sim.link().band()).uploadRatio();
        double u = uploadMbps(sim.link());
        if (sim.plan() != null && sim.plan().speedCapMbps() > 0) u = Math.min(u, sim.plan().speedCapMbps() * ratio);
        if (sim.throttled()) u = Math.min(u, s().throttleMbps * ratio);
        if (sim.deprioritized() && load(sim.link().tower(), sim.link().band()) > s().capacity(sim.link().tower().generation())) u *= s().deprioritizedSpeed;
        return u;
    }

    public double pingMs(Link l) {
        BandSpec spec = s().spec(l.band());
        return spec.pingMs() + l.distance() / 100.0 + (1 - signalFactor(l.dbm())) * spec.pingMs();
    }

    // ---------------------------------------------------------------- display

    public String hudLine(Status st) {
        if (st.phone() == null) return "<red>No phone</red> <gray>· /cell eshop";
        if (st.sims().isEmpty() || !st.hasService()) {
            if (st.emergency()) return "<red>No Service</red> <gray>· Emergency calls only (" + s().emergencyNumber + ")";
            return "<dark_gray>No Signal";
        }
        StringBuilder sb = new StringBuilder();
        for (SimStatus sim : st.sims()) {
            if (!sb.isEmpty()) sb.append(" <dark_gray>|</dark_gray> ");
            if (sim.link() == null) {
                sb.append(sim.carrier().styled()).append(" <red>No Service");
            } else {
                sb.append(sim.carrier().styled()).append(sim.roamingOn() != null ? " <gold>R</gold>" : "").append(" ")
                        .append(Msg.bars(sim.link().bars())).append(" <white>").append(s().spec(sim.link().band()).name());
            }
        }
        return sb.toString();
    }
}
