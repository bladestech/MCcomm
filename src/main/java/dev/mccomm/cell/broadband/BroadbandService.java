package dev.mccomm.cell.broadband;

import dev.mccomm.cell.CellPlugin;
import dev.mccomm.cell.band.Band;
import dev.mccomm.cell.broadband.BroadbandSettings.TechSpec;
import dev.mccomm.cell.broadband.BroadbandSettings.WifiTier;
import dev.mccomm.cell.model.BlockKey;
import dev.mccomm.cell.model.Carrier;
import dev.mccomm.cell.model.PlayerProfile;
import dev.mccomm.cell.model.Subscription;
import dev.mccomm.cell.model.Tower;
import dev.mccomm.cell.signal.SignalService;
import dev.mccomm.cell.tower.TowerManager;
import dev.mccomm.cell.util.Msg;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * Home internet: ISPs build nodes, players put a router on a wall sign and subscribe to a plan,
 * and computers and phones in Wi-Fi range get the connection. See design/broadband.md.
 */
public final class BroadbandService {
    private static final long HOUR = 3_600_000L;
    /** One in-game hour: 1,000 ticks, 50 real seconds at 20 TPS. */
    private static final long GAME_HOUR = 50_000L;
    private static final Pattern DEVICE_NAME = Pattern.compile("^[A-Za-z0-9_\\- ]{1,16}$");
    private static final int TEST_PING = 20, TEST_DL = 50, TEST_UL = 50;

    /**
     * A working connection: who serves it, and what it delivers right now. Exactly one of {@code node}
     * (an ISP's node) and {@code tower} (a carrier tower, for fixed wireless) is set.
     */
    public record Link(Router router, Isp isp, InternetPlan plan, Node node, Tower tower, double distance,
                       double downMbps, double upMbps, double pingMs, Extender via) {
        public Technology tech() {
            return plan.tech();
        }

        public String sourceName() {
            return node != null ? node.name() : tower != null ? tower.name() : "?";
        }
    }

    /** What a plan would deliver at a spot. {@code problem} is null when it can be ordered. */
    public record Quote(Isp isp, InternetPlan plan, Node node, Tower tower, double distance, double downMbps,
                        double pingMs, long dropFee, long price, String problem) {
        public boolean ok() {
            return problem == null;
        }

        public String sourceName() {
            return node != null ? node.name() : tower != null ? tower.name() : "?";
        }
    }

    private record FwLine(Tower tower, double down, double ping, double distance) {
    }

    private record Cached(Link link, long until) {
    }

    private final CellPlugin plugin;
    private final BroadbandStore store;
    private final Map<Integer, Integer> users = new HashMap<>();       // nodeId -> active customers
    private final Map<Integer, Integer> simUsers = new HashMap<>();    // nodeId -> simulated households
    private final Map<Integer, Integer> towerUsers = new HashMap<>();  // towerId -> fixed wireless customers
    private final Map<Integer, FwLine> fw = new HashMap<>();           // routerId -> its fixed wireless line
    private final Map<String, Double> transitShare = new HashMap<>();  // ispId -> backbone share (1 = no congestion)
    private final Map<Integer, Set<UUID>> guests = new HashMap<>();    // routerId -> guests allowed on its Wi-Fi
    private final Map<UUID, Cached> wifiCache = new HashMap<>();
    private final Map<BlockKey, String> signText = new HashMap<>();
    private final Map<UUID, Long> testCooldown = new HashMap<>();
    private final Map<UUID, BukkitTask> tests = new HashMap<>();
    private final Map<UUID, BukkitTask> lines = new HashMap<>();
    private long lastTick;
    private boolean simReady;
    private final DeviceScreen screens;

    public BroadbandService(CellPlugin plugin) {
        this.plugin = plugin;
        this.store = new BroadbandStore(plugin);
        this.screens = new DeviceScreen(plugin);
    }

    public BroadbandStore store() {
        return store;
    }

    /** The fancy screens on node, router and computer signs. */
    public DeviceScreen screens() {
        return screens;
    }

    public BroadbandSettings cfg() {
        return plugin.settings().broadband;
    }

    private TechSpec spec(Technology t) {
        return cfg().spec(t);
    }

    private String money(long v) {
        return plugin.settings().money(v);
    }

    /** Router and computer names: 1-16 letters, numbers, spaces, _ or -. */
    public static boolean validName(String s) {
        return DEVICE_NAME.matcher(s).matches();
    }

    public boolean canManage(Player p, Isp isp) {
        return p.hasPermission("mccomm.admin") || isp.isStaff(p.getUniqueId());
    }

    private static String nameOf(UUID id) {
        if (id == null) return "?";
        String n = Bukkit.getOfflinePlayer(id).getName();
        return n == null ? id.toString().substring(0, 8) : n;
    }

    // ================================================================= geometry

    private static boolean sameWorld(BlockKey a, BlockKey b) {
        return a.world().equals(b.world());
    }

    private static double dist(BlockKey a, BlockKey b) {
        double dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double dist(BlockKey a, Location l) {
        double dx = a.x() + 0.5 - l.getX(), dy = a.y() + 0.5 - l.getY(), dz = a.z() + 0.5 - l.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static Location center(BlockKey k) {
        World w = Bukkit.getWorld(k.world());
        return w == null ? null : new Location(w, k.x() + 0.5, k.y() + 0.5, k.z() + 0.5);
    }

    /** True if nothing solid is above this block (a satellite dish needs the sky). Unloaded chunks count as clear. */
    public boolean skyClear(BlockKey k) {
        World w = Bukkit.getWorld(k.world());
        if (w == null || w.getEnvironment() != World.Environment.NORMAL) return false;
        if (!w.isChunkLoaded(k.x() >> 4, k.z() >> 4)) return true;
        return w.getHighestBlockYAt(k.x(), k.z(), HeightMap.MOTION_BLOCKING) <= k.y();
    }

    /** Solid blocks between a router sign and a spot (walls and floors in the way of the Wi-Fi). */
    private int walls(BlockKey from, Location to) {
        World w = to.getWorld();
        if (w == null || !w.getUID().equals(from.world())) return 0;
        double fx = from.x() + 0.5, fy = from.y() + 0.5, fz = from.z() + 0.5;
        double dx = to.getX() - fx, dy = to.getY() - fy, dz = to.getZ() - fz;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.5) return 0;
        double sx = dx / len * 0.5, sy = dy / len * 0.5, sz = dz / len * 0.5;
        int count = 0, lx = from.x(), ly = from.y(), lz = from.z();
        double x = fx, y = fy, z = fz;
        for (double t = 0; t < len - 0.75; t += 0.5) {
            x += sx;
            y += sy;
            z += sz;
            int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
            if (bx == lx && by == ly && bz == lz) continue;
            lx = bx;
            ly = by;
            lz = bz;
            if (by < w.getMinHeight() || by >= w.getMaxHeight()) continue;
            if (!w.isChunkLoaded(bx >> 4, bz >> 4)) break;
            if (w.getBlockAt(bx, by, bz).getType().isOccluding() && ++count >= 12) break;
        }
        return count;
    }

    /** How far this router's Wi-Fi reaches, before walls. */
    public double reach(Router r) {
        return cfg().wifiRange() * cfg().tier(r.tier()).rangeFactor();
    }

    /** True if the router's own Wi-Fi reaches {@code to}: distance plus a penalty for every wall in the way. */
    private boolean directReach(Router r, Location to) {
        if (to == null || to.getWorld() == null || !to.getWorld().getUID().equals(r.key().world())) return false;
        double reach = reach(r);
        double d = dist(r.key(), to);
        if (d > reach) return false;
        return d + walls(r.key(), to) * cfg().wifi().wallLoss() <= reach;
    }

    /** True if the router's Wi-Fi reaches {@code to}, directly or through one of its owner's extenders. */
    public boolean inReach(Router r, Location to) {
        return directReach(r, to) || repeater(r, to) != null;
    }

    // ---- extenders

    /** How far an extender repeating this router reaches, before walls. */
    public double extenderReach(Router r) {
        return reach(r) * cfg().wifi().extender().rangeFactor();
    }

    /** True if this extender picks up router {@code r}: same owner, the router works, and its Wi-Fi reaches the sign. */
    private boolean feeds(Router r, Extender x) {
        if (!r.owner().equals(x.owner()) || r.state() != Router.State.ACTIVE) return false;
        if (x.routerId() != 0 && x.routerId() != r.id()) return false;
        return directReach(r, center(x.key()));
    }

    /** The closest extender that carries router {@code r}'s Wi-Fi to this spot, or null. */
    private Extender repeater(Router r, Location to) {
        if (store.extenders().isEmpty() || to == null || to.getWorld() == null) return null;
        UUID world = to.getWorld().getUID();
        double reach = extenderReach(r);
        Extender best = null;
        double bestDist = Double.MAX_VALUE;
        for (Extender x : store.extenders()) {
            if (!x.key().world().equals(world)) continue;
            double d = dist(x.key(), to);
            if (d > reach || d >= bestDist) continue;
            if (d + walls(x.key(), to) * cfg().wifi().wallLoss() > reach) continue;
            if (!feeds(r, x)) continue;
            best = x;
            bestDist = d;
        }
        return best;
    }

    /** The router an extender repeats right now: the pinned one, else the owner's fastest working router that reaches it. */
    public Router sourceOf(Extender x) {
        Router best = null;
        double bestDown = -1;
        for (Router r : store.routers()) {
            if (!feeds(r, x)) continue;
            Link l = link(r);
            if (l == null) continue;
            if (l.downMbps() > bestDown) {
                best = r;
                bestDown = l.downMbps();
            }
        }
        return best;
    }

    /** The owner's routers whose Wi-Fi reaches this extender's sign (for choosing what to repeat). */
    public List<Router> sourcesInRange(Extender x) {
        List<Router> out = new ArrayList<>();
        Location at = center(x.key());
        for (Router r : store.routersOf(x.owner())) if (directReach(r, at)) out.add(r);
        return out;
    }

    /** What the extender's own Wi-Fi delivers at its sign: the source router's line with the repeater's penalty. */
    public Link extenderLink(Extender x) {
        Router r = sourceOf(x);
        Link l = r == null ? null : link(r);
        return l == null ? null : viaExtender(l, x);
    }

    private Link viaExtender(Link l, Extender x) {
        var e = cfg().wifi().extender();
        return new Link(l.router(), l.isp(), l.plan(), l.node(), l.tower(), l.distance(), l.downMbps() * e.speedFactor(),
                l.upMbps() * e.speedFactor(), l.pingMs() + e.extraPingMs(), x);
    }

    /** The router's connection as seen from {@code to}: reduced if the Wi-Fi gets there through an extender. */
    public Link linkFor(Router r, Location to) {
        Link l = link(r);
        if (l == null || directReach(r, to)) return l;
        Extender x = repeater(r, to);
        return x == null ? l : viaExtender(l, x);
    }

    /**
     * Wi-Fi signal bars (1-4) at {@code to}: the closer to the router, and the fewer walls in between, the more bars.
     * Works out as the share of the router's reach already used up by distance and wall loss.
     */
    public int wifiBars(Router r, Location to) {
        return bars(r.key(), reach(r), to);
    }

    /** Bars for a connection as it reaches {@code to}: measured from the extender when it comes through one. */
    public int wifiBars(Link l, Location to) {
        if (l.via() != null) return bars(l.via().key(), extenderReach(l.router()), to);
        return wifiBars(l.router(), to);
    }

    private int bars(BlockKey from, double reach, Location to) {
        if (reach <= 0 || to == null || to.getWorld() == null || !to.getWorld().getUID().equals(from.world())) return 1;
        double used = (dist(from, to) + walls(from, to) * cfg().wifi().wallLoss()) / reach;
        if (used <= 0.3) return 4;
        if (used <= 0.55) return 3;
        if (used <= 0.8) return 2;
        return 1;
    }

    // ============================================================ speed and load

    /** Speed share kept at this distance from a node: 1 at the node, {@code minFactor} at the edge of the range. */
    public double distanceFactor(TechSpec s, double d) {
        if (s.range() <= 0 || s.falloff() <= 0) return 1;
        double ratio = Math.min(1, d / s.range());
        return 1 - Math.pow(ratio, s.falloff()) * (1 - s.minFactor());
    }

    private double weatherFactor(TechSpec s, BlockKey at) {
        World w = Bukkit.getWorld(at.world());
        if (w == null) return 1;
        if (w.isThundering()) return s.thunderFactor();
        if (w.hasStorm()) return s.rainFactor();
        return 1;
    }

    public double capacity(Node n) {
        return spec(n.tech()).nodeCapacity() * n.tier();
    }

    /** Customers on a node, including simulated households. */
    public int users(Node n) {
        return users.getOrDefault(n.id(), 0) + simUsers.getOrDefault(n.id(), 0);
    }

    public double load(Node n) {
        return users(n) / capacity(n);
    }

    /** Customers who share a node get this part of its speed (1 = no slowdown). */
    private double share(Node n, int users) {
        double cap = capacity(n);
        return users <= cap ? 1 : cap / users;
    }

    /** Business plans get priority on a busy node: they keep their speed longer than home customers. */
    private double share(Node n, int users, InternetPlan plan) {
        double base = share(n, users);
        return plan != null && plan.business() ? Math.min(1, base * cfg().business().priority()) : base;
    }

    public long nodeUpkeep(Node n) {
        TechSpec s = spec(n.tech());
        return Math.round(s.nodeUpkeep() * (1 + cfg().tierUpkeepStep() * (n.tier() - 1)));
    }

    /** Daily upkeep: every node, plus the transit tiers above the first. Carrier-backed ISPs pay none. */
    public long upkeepDue(Isp isp) {
        long sum = 0;
        for (Node n : store.nodesOf(isp.id())) sum += nodeUpkeep(n);
        if (!isp.carrierBacked()) sum += (isp.transitTier() - 1L) * cfg().transit().upkeepPerTier();
        return sum;
    }

    public long upgradeCost(Node n) {
        return Math.round(spec(n.tech()).nodeCost() * cfg().tierUpgradePercent() * n.tier());
    }

    // ---- transit and peering

    /** Backbone capacity in Mbps: the bought tiers, plus a bonus for every peer. */
    public double transitCapacity(Isp isp) {
        int peers = 0;
        for (String id : isp.peers()) if (store.isp(id) != null) peers++;
        return cfg().transit().mbpsPerTier() * isp.transitTier() * (1 + cfg().transit().peerBonus() * peers) * networkFactor(isp);
    }

    /** How well the ISP's network ops are funded, as a share of its backbone that works (1 = all of it). */
    public double networkFactor(Isp isp) {
        if (isp.carrierBacked()) return 1;
        return Math.max(plugin.settings().workforce.networkMinSpeed(), Math.min(1, plugin.workforce().funding(isp, dev.mccomm.cell.workforce.OpsPart.NETWORK)));
    }

    /** In-game hours before a crew fixes an unpaid fault: 6 at 0% field crews, half an hour at 100%, less above. */
    public double autoRepairHours(Isp isp) {
        double f = plugin.workforce().funding(isp, dev.mccomm.cell.workforce.OpsPart.FIELD);
        return cfg().faults().autoRepairHours() / (1 + 11 * f);
    }

    /** Plan income per day: active lines at their current price, plus simulated households and businesses. */
    public double incomePerDay(Isp isp) {
        double sum = 0;
        for (Router r : store.routersOfIsp(isp.id())) {
            if (r.state() != Router.State.ACTIVE) continue;
            InternetPlan live = isp.plans().get(r.planId());
            if (live != null) sum += priceFor(isp, live, r.owner()) * 24.0 / Math.max(1, live.periodHours());
        }
        if (cfg().market().pays()) {
            sum += avgPerHour(isp, Segment.RESIDENTIAL) * 24 * isp.simCustomers();
            sum += avgPerHour(isp, Segment.BUSINESS) * 24 * isp.simBusinesses();
        }
        return sum;
    }

    /** The average current hourly price of the ISP's plans for one kind of customer (what simulated ones pay). */
    private double avgPerHour(Isp isp, Segment seg) {
        double perHour = 0;
        int n = 0;
        for (InternetPlan p : isp.plans().values()) {
            if (p.segment() != seg) continue;
            perHour += plugin.sales().price(p) / (double) Math.max(1, p.periodHours());
            n++;
        }
        return n == 0 ? 0 : perHour / n;
    }

    /** What the customers would pull at once: plan speeds times the utilisation factor. */
    public double transitDemand(Isp isp) {
        double sum = 0, caps = 0;
        int plans = 0;
        for (Router r : store.routersOfIsp(isp.id())) {
            if (r.state() != Router.State.ACTIVE) continue;
            InternetPlan t = termsOf(r, isp);
            if (t != null) sum += Math.min(t.speedCapMbps(), spec(t.tech()).maxMbps());
        }
        for (InternetPlan p : isp.plans().values()) {
            caps += Math.min(p.speedCapMbps(), spec(p.tech()).maxMbps());
            plans++;
        }
        int sims = isp.simCustomers() + isp.simBusinesses();
        if (sims > 0 && plans > 0) sum += sims * (caps / plans);
        return sum * cfg().transit().utilization();
    }

    /** 1 when the backbone keeps up; below 1 every customer of the ISP slows down. */
    public double transitShare(Isp isp) {
        if (isp.carrierBacked()) return 1;
        return transitShare.getOrDefault(isp.id(), 1.0);
    }

    private void refreshTransit() {
        transitShare.clear();
        for (Isp isp : store.isps()) {
            if (isp.carrierBacked()) continue;
            double demand = transitDemand(isp), cap = transitCapacity(isp);
            transitShare.put(isp.id(), demand <= cap || demand <= 0 ? 1.0 : cap / demand);
        }
    }

    public boolean upgradeTransit(Player p, Isp isp) {
        if (!canManage(p, isp) || isp.carrierBacked()) return false;
        if (isp.transitTier() >= cfg().transit().maxTier()) {
            Msg.send(p, "<red>The ISP already buys the top transit tier.");
            return false;
        }
        long cost = p.hasPermission("mccomm.admin") ? 0 : cfg().transit().upgradeCost() * isp.transitTier();
        if (isp.balance() < cost) {
            Msg.send(p, "<red>More transit costs <white><amt></white> and <isp> has <white><bal></white>.",
                    Msg.p("amt", money(cost)), Msg.p("isp", isp.name()), Msg.p("bal", money(isp.balance())));
            return false;
        }
        isp.balance(isp.balance() - cost);
        if (cost > 0) isp.log(-cost, "Transit tier " + (isp.transitTier() + 1));
        isp.transitTier(isp.transitTier() + 1);
        store.markDirty();
        Msg.send(p, "<green><isp> now buys transit tier <white><t></white>: <white><cap></white> of backbone.",
                Msg.p("isp", isp.name()), Msg.p("t", "" + isp.transitTier()), Msg.p("cap", Msg.speed(transitCapacity(isp))));
        return true;
    }

    public boolean offerPeer(Player p, Isp mine, Isp other) {
        if (!canManage(p, mine) || mine == other || mine.carrierBacked() || other.carrierBacked()) {
            Msg.send(p, "<red>You can't peer with that ISP.");
            return false;
        }
        if (mine.peers().contains(other.id())) {
            Msg.send(p, "<gray>You already peer with <n>.", Msg.p("n", other.name()));
            return false;
        }
        if (mine.peerOffers().contains(other.id())) { // they already offered: that's a yes
            return acceptPeer(p, mine, other);
        }
        other.peerOffers().add(mine.id());
        store.markDirty();
        plugin.billing().notify(other.owner(), "<aqua><isp></aqua> offers to peer with <white><me></white>: <white>/isp peer accept <isp></white>.",
                Msg.p("isp", mine.name()), Msg.p("me", other.name()));
        Msg.send(p, "<green>Peering offer sent to <white><n></white>.", Msg.p("n", other.name()));
        return true;
    }

    public boolean acceptPeer(Player p, Isp mine, Isp other) {
        if (!canManage(p, mine) || !mine.peerOffers().remove(other.id())) {
            Msg.send(p, "<red><n> hasn't offered to peer with <isp>.", Msg.p("n", other.name()), Msg.p("isp", mine.name()));
            return false;
        }
        mine.peers().add(other.id());
        other.peers().add(mine.id());
        other.peerOffers().remove(mine.id());
        store.markDirty();
        plugin.billing().notify(other.owner(), "<green><isp> accepted your peering offer: more backbone for both.", Msg.p("isp", mine.name()));
        Msg.send(p, "<green>You now peer with <white><n></white>: <white>+<pct>% backbone</white> each.", Msg.p("n", other.name()),
                Msg.p("pct", "" + Math.round(cfg().transit().peerBonus() * 100)));
        return true;
    }

    public boolean endPeer(Player p, Isp mine, Isp other) {
        if (!canManage(p, mine)) return false;
        boolean had = mine.peers().remove(other.id()) | mine.peerOffers().remove(other.id());
        other.peers().remove(mine.id());
        other.peerOffers().remove(mine.id());
        store.markDirty();
        if (had) Msg.send(p, "<yellow>Peering with <white><n></white> ended.", Msg.p("n", other.name()));
        else Msg.send(p, "<gray>You don't peer with <n>.", Msg.p("n", other.name()));
        return had;
    }

    // ---- bundles (ISP + mobile carrier)

    private boolean hasCarrierPlan(UUID player, String carrierId) {
        PlayerProfile prof = plugin.store().profile(player);
        Subscription sub = prof.sub(carrierId);
        return sub != null && sub.planId() != null && !plugin.billing().expired(prof, carrierId);
    }

    /** Percent off an ISP's home plans for this customer, from its bundles with the carriers they are on. */
    public int bundleDiscount(Isp isp, UUID customer) {
        int best = 0;
        for (Bundle b : isp.bundles().values()) {
            if (b.accepted() && b.ispDiscount() > best && hasCarrierPlan(customer, b.carrierId())) best = b.ispDiscount();
        }
        return Math.min(best, (int) cfg().bundleMaxDiscount());
    }

    private boolean hasHomeInternet(UUID player, String ispId) {
        for (Router r : store.routersOf(player)) if (r.state() == Router.State.ACTIVE && ispId.equals(r.ispId())) return true;
        return false;
    }

    /** Credits off a mobile plan's renewal because the player also has the partner ISP's home internet. */
    public long carrierDiscount(UUID player, String carrierId, long price) {
        int best = 0;
        for (Isp isp : store.isps()) {
            Bundle b = isp.bundles().get(carrierId);
            if (b != null && b.accepted() && b.carrierDiscount() > best && hasHomeInternet(player, isp.id())) best = b.carrierDiscount();
        }
        best = Math.min(best, (int) cfg().bundleMaxDiscount());
        return best <= 0 ? 0 : Math.round(price * best / 100.0);
    }

    public boolean offerBundle(Player p, Isp isp, Carrier carrier, int ispDiscount, int carrierDiscount) {
        if (!canManage(p, isp)) return false;
        int max = (int) cfg().bundleMaxDiscount();
        if (ispDiscount < 0 || carrierDiscount < 0 || ispDiscount > max || carrierDiscount > max) {
            Msg.send(p, "<red>Discounts are 0-<m> percent.", Msg.p("m", "" + max));
            return false;
        }
        isp.bundles().put(carrier.id(), new Bundle(carrier.id(), ispDiscount, carrierDiscount, false));
        store.markDirty();
        plugin.billing().notify(carrier.owner(), "<aqua><isp></aqua> offers <carrier> a bundle: <white><d1>%</white> off its home plans for your customers, <white><d2>%</white> off your plans for its customers. <white>/isp bundle accept <isp></white>",
                Msg.p("isp", isp.name()), Msg.p("carrier", carrier.name()), Msg.p("d1", "" + ispDiscount), Msg.p("d2", "" + carrierDiscount));
        Msg.send(p, "<green>Bundle offer sent to <white><carrier></white>.", Msg.p("carrier", carrier.name()));
        return true;
    }

    public boolean acceptBundle(Player p, Carrier carrier, Isp isp) {
        Bundle b = isp.bundles().get(carrier.id());
        if (b == null || b.accepted() || !(carrier.isStaff(p.getUniqueId()) || p.hasPermission("mccomm.admin"))) {
            Msg.send(p, "<red>No pending bundle offer from <n> for <carrier>.", Msg.p("n", isp.name()), Msg.p("carrier", carrier.name()));
            return false;
        }
        b.accepted(true);
        store.markDirty();
        plugin.billing().notify(isp.owner(), "<green><carrier> accepted your bundle.", Msg.p("carrier", carrier.name()));
        Msg.send(p, "<green>Bundle with <white><n></white> is on: <d1>% off their plans for your customers, <d2>% off yours for theirs.",
                Msg.p("n", isp.name()), Msg.p("d1", "" + b.ispDiscount()), Msg.p("d2", "" + b.carrierDiscount()));
        return true;
    }

    public boolean endBundle(Player p, Isp isp, Carrier carrier) {
        boolean staff = canManage(p, isp) || carrier.isStaff(p.getUniqueId()) || p.hasPermission("mccomm.admin");
        if (!staff || isp.bundles().remove(carrier.id()) == null) {
            Msg.send(p, "<red>No such bundle.");
            return false;
        }
        store.markDirty();
        Msg.send(p, "<yellow>Bundle between <white><isp></white> and <white><carrier></white> ended.", Msg.p("isp", isp.name()), Msg.p("carrier", carrier.name()));
        return true;
    }

    // ---- node choice and quotes

    /** The node a home would use: the nearest healthy node of this technology in range, preferring ones with room. */
    public Node bestNode(Isp isp, Technology t, BlockKey where) {
        TechSpec s = spec(t);
        Node best = null;
        boolean bestFull = true;
        double bestD = Double.MAX_VALUE;
        for (Node n : store.nodesOf(isp.id())) {
            if (n.tech() != t || n.faulted() || !sameWorld(n.key(), where)) continue;
            double d = dist(n.key(), where);
            if (s.range() > 0 && d > s.range()) continue;
            boolean full = users(n) >= capacity(n);
            if (best == null || (bestFull && !full) || (full == bestFull && d < bestD)) {
                best = n;
                bestFull = full;
                bestD = d;
            }
        }
        return best;
    }

    /** The price the router's owner pays for a plan: the live price less any bundle discount. */
    public long priceFor(Isp isp, InternetPlan plan, UUID customer) {
        long base = plugin.sales().price(plan); // the sale price while a sale is on
        int disc = bundleDiscount(isp, customer);
        return disc <= 0 ? base : Math.round(base * (100 - disc) / 100.0);
    }

    /** What this plan would deliver at {@code where} for the owner of {@code asking}. */
    public Quote quote(Isp isp, InternetPlan plan, BlockKey where, Router asking) {
        TechSpec s = spec(plan.tech());
        long price = priceFor(isp, plan, asking.owner());
        if (plan.business() != asking.business()) {
            return new Quote(isp, plan, null, null, 0, 0, 0, 0, price, plan.business() ? "A business plan: this router is a home" : "A home plan: this router is a business site");
        }
        if (plan.tech() == Technology.FIXED_WIRELESS) {
            if (!isp.carrierBacked()) return new Quote(isp, plan, null, null, 0, 0, 0, 0, price, "Only carrier ISPs sell 5G Home");
            FwLine f = computeFw(isp, where, asking);
            if (f == null) return new Quote(isp, plan, null, null, 0, 0, 0, 0, price, "No 5G signal from the carrier at this spot");
            return new Quote(isp, plan, null, f.tower(), f.distance(), Math.min(f.down(), plan.speedCapMbps()), f.ping(), 0, price, null);
        }
        if (isp.carrierBacked()) return new Quote(isp, plan, null, null, 0, 0, 0, 0, price, "Not offered by this provider");
        if (!isp.paidUp()) return new Quote(isp, plan, null, null, 0, 0, 0, 0, price, isp.name() + " is having an outage");
        Node node = bestNode(isp, plan.tech(), where);
        if (node == null) {
            String why = s.range() > 0 ? "No " + s.name() + " node within " + Math.round(s.range()) + " blocks" : "No " + s.name() + " ground station";
            return new Quote(isp, plan, null, null, 0, 0, 0, 0, price, why);
        }
        double d = dist(node.key(), where);
        long drop = Math.round(d * s.dropCostPerBlock());
        if (s.needsSky() && !skyClear(where)) return new Quote(isp, plan, node, null, d, 0, 0, drop, price, "Needs a clear view of the sky");
        boolean already = asking.state() == Router.State.ACTIVE && asking.nodeId() == node.id();
        int sharing = users(node) + (already ? 0 : 1);
        double raw = s.maxMbps() * distanceFactor(s, d) * share(node, sharing, plan) * weatherFactor(s, where) * transitShare(isp);
        return new Quote(isp, plan, node, null, d, Math.min(raw, plan.speedCapMbps()), ping(s, d), drop, price, null);
    }

    private static double ping(TechSpec s, double distance) {
        return s.pingMs() + distance / 100.0;
    }

    // ---- fixed wireless

    private FwLine computeFw(Isp isp, BlockKey where, Router existing) {
        if (!isp.carrierBacked()) return null;
        Carrier c = plugin.store().carrier(isp.carrierId());
        Location eye = center(where);
        if (c == null || c.mvno() || eye == null) return null;
        TechSpec s = spec(Technology.FIXED_WIRELESS);
        FwLine best = null;
        for (Tower t : plugin.store().towersOf(c.id())) {
            if (!t.online() || !t.worldId().equals(where.world())) continue;
            SignalService.Link l = plugin.signal().linkTo(t, eye, cfg().fixedWireless().indoorDb(), Band.G5_MMWAVE);
            if (l == null) continue;
            boolean already = existing != null && existing.state() == Router.State.ACTIVE && existing.towerId() == t.id();
            int sharing = towerUsers.getOrDefault(t.id(), 0) + (already ? 0 : 1);
            double share = sharing <= s.nodeCapacity() ? 1 : (double) s.nodeCapacity() / sharing;
            double down = Math.min(plugin.signal().downloadMbps(l) * share, s.maxMbps());
            if (best == null || down > best.down()) best = new FwLine(t, down, plugin.signal().pingMs(l), l.distance());
        }
        return best;
    }

    private void refreshFw() {
        fw.clear();
        for (Router r : store.routers()) {
            if (r.state() != Router.State.ACTIVE || r.ispId() == null) continue;
            Isp isp = store.isp(r.ispId());
            InternetPlan t = isp == null ? null : termsOf(r, isp);
            if (t == null || t.tech() != Technology.FIXED_WIRELESS) continue;
            FwLine f = computeFw(isp, r.key(), r);
            if (f == null) continue;
            fw.put(r.id(), f);
            r.towerId(f.tower().id());
        }
    }

    // ---- the current connection

    /** The plan terms a router is on: its own copy until the next renewal, else the live plan. */
    private InternetPlan termsOf(Router r, Isp isp) {
        InternetPlan live = isp.plans().get(r.planId());
        if (live == null) return null;
        return r.terms() != null && r.terms().tech() == live.tech() ? r.terms() : live;
    }

    /** True once a capped plan's data allowance is used up: the line drops to fair-use speed. */
    public boolean throttled(Router r) {
        Isp isp = r.ispId() == null ? null : store.isp(r.ispId());
        InternetPlan t = isp == null ? null : termsOf(r, isp);
        return t != null && !t.unlimited() && r.dataLeftMB() <= 0;
    }

    /** Why this router has no internet right now, or null if it is working. */
    public String problem(Router r) {
        return switch (r.state()) {
            case NONE -> "No internet plan";
            case INSTALLING -> "The technician hasn't finished yet";
            case SUSPENDED -> "Suspended for a missed payment";
            case ACTIVE -> {
                Isp isp = r.ispId() == null ? null : store.isp(r.ispId());
                if (isp == null) yield "The provider no longer exists";
                InternetPlan t = termsOf(r, isp);
                if (t == null) yield "The plan no longer exists";
                if (t.tech() == Technology.FIXED_WIRELESS) yield fw.containsKey(r.id()) ? null : "No 5G signal here";
                if (!isp.paidUp()) yield isp.name() + " is having an outage";
                Node node = store.node(r.nodeId());
                if (node == null) yield "No node in range";
                if (node.faulted()) yield "A fault on node " + node.name() + ": " + isp.name() + " is fixing it";
                if (spec(node.tech()).needsSky() && !skyClear(r.key())) yield "The dish has no view of the sky";
                yield null;
            }
        };
    }

    /** The router's live connection, or null while it has none. */
    public Link link(Router r) {
        if (r.state() != Router.State.ACTIVE || problem(r) != null) return null;
        Isp isp = store.isp(r.ispId());
        InternetPlan t = termsOf(r, isp);
        boolean slow = !t.unlimited() && r.dataLeftMB() <= 0;
        if (t.tech() == Technology.FIXED_WIRELESS) {
            FwLine f = fw.get(r.id());
            double down = Math.min(f.down(), t.speedCapMbps());
            if (slow) down = Math.min(down, cfg().throttleMbps());
            return new Link(r, isp, t, null, f.tower(), f.distance(), down, down * spec(t.tech()).uploadRatio(), f.ping(), null);
        }
        Node node = store.node(r.nodeId());
        TechSpec s = spec(node.tech());
        double d = dist(node.key(), r.key());
        double raw = s.maxMbps() * distanceFactor(s, d) * share(node, Math.max(1, users(node)), t) * weatherFactor(s, r.key()) * transitShare(isp);
        double down = Math.min(raw, t.speedCapMbps());
        if (slow) down = Math.min(down, cfg().throttleMbps());
        return new Link(r, isp, t, node, null, d, down, down * s.uploadRatio(), ping(s, d), null);
    }

    // ===================================================================== Wi-Fi

    private boolean usableBy(Router r, UUID user) {
        if (r.owner().equals(user)) return true;
        if (!r.open()) return false;
        Set<UUID> ok = guests.get(r.id());
        return ok == null || ok.contains(user);
    }

    private void refreshGuests() {
        guests.clear();
        for (Router r : store.routers()) {
            if (r.state() != Router.State.ACTIVE || !r.open()) continue;
            List<UUID> in = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!p.getUniqueId().equals(r.owner()) && inReach(r, p.getLocation())) in.add(p.getUniqueId());
            }
            in.sort(Comparator.comparing(UUID::toString));
            int max = cfg().tier(r.tier()).maxGuests();
            guests.put(r.id(), new HashSet<>(in.subList(0, Math.min(max, in.size()))));
        }
    }

    /** The best working router whose Wi-Fi reaches this player and that they may use (their own first). */
    public Link wifiLink(Player p) {
        if (!cfg().enabled()) return null;
        long now = System.currentTimeMillis();
        Cached c = wifiCache.get(p.getUniqueId());
        if (c != null && c.until() > now) return c.link();
        UUID me = p.getUniqueId();
        Link best = null;
        Integer choice = store.wifiChoice().get(me);
        if (choice != null && choice > 0 && store.router(choice) == null) {
            store.wifiChoice().remove(me); // the router is gone: back to automatic
            store.markDirty();
            choice = null;
        }
        if (choice != null) { // the player chose: that network, or none at all (0)
            Router r = choice > 0 ? store.router(choice) : null;
            if (r != null && r.state() == Router.State.ACTIVE && usableBy(r, me) && inReach(r, p.getLocation())) best = linkFor(r, p.getLocation());
        } else if (cfg().wifi().autoConnect()) {
            for (Router r : store.routers()) {
                if (r.state() != Router.State.ACTIVE || !usableBy(r, me) || !inReach(r, p.getLocation())) continue;
                Link l = linkFor(r, p.getLocation());
                if (l == null) continue;
                if (best == null || better(l, best, me)) best = l;
            }
        }
        wifiCache.put(me, new Cached(best, now + 1000));
        return best;
    }

    /** Working Wi-Fi networks the player can join from where they stand, own router first, then the fastest. */
    public List<Link> networksInRange(Player p) {
        List<Link> out = new ArrayList<>();
        for (Router r : store.routers()) {
            if (r.state() != Router.State.ACTIVE || !usableBy(r, p.getUniqueId()) || !inReach(r, p.getLocation())) continue;
            Link l = linkFor(r, p.getLocation());
            if (l != null) out.add(l);
        }
        out.sort((a, b) -> better(a, b, p.getUniqueId()) ? -1 : better(b, a, p.getUniqueId()) ? 1 : 0);
        return out;
    }

    /**
     * Joins the Wi-Fi called { name} (a router's name), or { null} to go back to automatic.
     * Returns an error for the player, or null on success.
     */
    public String connect(Player p, String name) {
        UUID me = p.getUniqueId();
        if (!cfg().enabled() || !cfg().phonesUseWifi()) return "Wi-Fi is turned off on this server.";
        if (name == null) {
            store.wifiChoice().remove(me);
        } else {
            Link pick = null;
            boolean seen = false;
            for (Router r : store.routers()) {
                if (!r.name().equalsIgnoreCase(name) || !inReach(r, p.getLocation())) continue;
                seen = true;
                if (r.state() != Router.State.ACTIVE || !usableBy(r, me)) continue;
                Link l = linkFor(r, p.getLocation());
                if (l != null && (pick == null || better(l, pick, me))) pick = l;
            }
            if (pick == null) {
                return seen ? "That Wi-Fi is private, full, or its internet is down."
                        : "No Wi-Fi called " + name + " reaches you. Try /broadband connect to see what's in range.";
            }
            store.wifiChoice().put(me, pick.router().id());
        }
        store.markDirty();
        wifiCache.remove(me);
        return null;
    }

    /** Leaves Wi-Fi on purpose: the phone uses mobile data until the player connects again. */
    public void disconnect(Player p) {
        store.wifiChoice().put(p.getUniqueId(), 0);
        store.markDirty();
        wifiCache.remove(p.getUniqueId());
    }

    /** True if the player has chosen to stay off Wi-Fi. */
    public boolean wifiOff(Player p) {
        Integer c = store.wifiChoice().get(p.getUniqueId());
        return c != null && c == 0;
    }

    private boolean better(Link a, Link b, UUID user) {
        boolean ao = a.router().owner().equals(user), bo = b.router().owner().equals(user);
        if (ao != bo) return ao;
        return a.downMbps() > b.downMbps();
    }

    private boolean computerMay(Router r, Computer c, UUID user) {
        return r.owner().equals(c.owner()) || usableBy(r, user);
    }

    /** The connection a computer uses for {@code user}: its chosen router, or the best one in range. */
    public Link computerLink(Computer c, UUID user) {
        Location at = center(c.key());
        if (c.routerId() != 0) {
            Router r = store.router(c.routerId());
            if (r != null && inReach(r, at) && computerMay(r, c, user)) {
                Link l = linkFor(r, at);
                if (l != null) return l;
            }
        }
        Link best = null;
        for (Router r : store.routers()) {
            if (r.state() != Router.State.ACTIVE || !computerMay(r, c, user) || !inReach(r, at)) continue;
            Link l = linkFor(r, at);
            if (l == null) continue;
            if (best == null || better(l, best, c.owner())) best = l;
        }
        return best;
    }

    /** Routers whose Wi-Fi reaches this computer (for the Wi-Fi picker). */
    public List<Router> routersInRange(Computer c, UUID user) {
        List<Router> out = new ArrayList<>();
        Location at = center(c.key());
        for (Router r : store.routers()) {
            if (computerMay(r, c, user) && inReach(r, at)) out.add(r);
        }
        return out;
    }

    // ---- data allowance

    /** Uses data on the router's allowance (no effect on unlimited plans). */
    public void useWifiData(Link l, double mb) {
        Router r = l.router();
        if (l.plan().unlimited() || mb <= 0) return;
        r.dataLeftMB(r.dataLeftMB() - mb);
        store.markDirty();
    }

    /** Phones on home Wi-Fi don't use mobile data: charges the router's allowance instead. True if the player is on Wi-Fi. */
    public boolean offload(UUID player, double mb) {
        Player p = Bukkit.getPlayer(player);
        if (p == null || !cfg().phonesUseWifi()) return false;
        Link l = wifiLink(p);
        if (l == null) return false;
        useWifiData(l, mb);
        return true;
    }

    /** True if home Wi-Fi reaches the player (and phones may use it). */
    public boolean onWifi(Player p) {
        return cfg().phonesUseWifi() && wifiLink(p) != null;
    }

    // ================================================================== ISPs

    private boolean checkNewIsp(Player p, String name) {
        if (!cfg().enabled()) {
            Msg.send(p, "<red>Home internet is turned off on this server.");
            return false;
        }
        if (!cfg().ispNamePattern().matcher(name).matches()) {
            Msg.send(p, "<red>ISP names are 2-20 letters, numbers, <white>_ & . -</white>.");
            return false;
        }
        if (store.isp(name) != null) {
            Msg.send(p, "<red>An ISP named <white><n></white> already exists.", Msg.p("n", name));
            return false;
        }
        int max = cfg().maxIspsPerPlayer();
        if (max >= 0 && !p.hasPermission("mccomm.admin")
                && plugin.groups().ownedOutsideGroups(p.getUniqueId(), dev.mccomm.cell.model.Company.Kind.ISP) >= max) {
            Msg.send(p, "<red>You already own the maximum number of ISPs (<n>). ISPs inside a parent company (/company) don't count.", Msg.p("n", "" + max));
            return false;
        }
        return true;
    }

    private InternetPlan starterPlan(Isp isp, Technology t) {
        TechSpec s = spec(t);
        String name = s.name() + " Standard";
        return new InternetPlan(newPlanId(isp, name), name, t, s.starterPrice(), 24,
                Math.max(1, Math.round(s.maxMbps() * 0.5)), s.installFee());
    }

    public Isp createIsp(Player p, String name) {
        if (!checkNewIsp(p, name)) return null;
        Isp isp = new Isp(name, p.getUniqueId(), System.currentTimeMillis());
        for (Technology t : Technology.nodeTechs()) {
            InternetPlan plan = starterPlan(isp, t);
            isp.plans().put(plan.id(), plan);
        }
        store.addIsp(isp);
        return isp;
    }

    /** A carrier's home business: an ISP whose "network" is the carrier's towers (5G Home). */
    public Isp createCarrierIsp(Player p, String name, Carrier carrier) {
        if (!checkNewIsp(p, name)) return null;
        // Only the carrier's owner: the ISP runs on the carrier's towers, so its income belongs to whoever owns them.
        if (!carrier.owner().equals(p.getUniqueId()) && !p.hasPermission("mccomm.admin")) {
            Msg.send(p, "<red>Only the owner of <white><carrier></white> can start its home internet business.", Msg.p("carrier", carrier.name()));
            return null;
        }
        if (carrier.mvno()) {
            Msg.send(p, "<red><carrier> is an MVNO and has no towers of its own.", Msg.p("carrier", carrier.name()));
            return null;
        }
        if (store.ispOfCarrier(carrier.id()) != null) {
            Msg.send(p, "<red><carrier> already has a home internet business.", Msg.p("carrier", carrier.name()));
            return null;
        }
        Isp isp = new Isp(name, carrier.owner(), System.currentTimeMillis());
        isp.carrierId(carrier.id());
        isp.colors(carrier.colors());
        InternetPlan plan = starterPlan(isp, Technology.FIXED_WIRELESS);
        isp.plans().put(plan.id(), plan);
        store.addIsp(isp);
        return isp;
    }

    public String newPlanId(Isp isp, String name) {
        String base = BroadbandStore.slug(name);
        String id = base;
        for (int i = 2; isp.plans().containsKey(id); i++) id = base + "-" + i;
        return id;
    }

    /** The technologies this ISP can sell plans for. */
    public Technology[] techsFor(Isp isp) {
        return isp.carrierBacked() ? new Technology[]{Technology.FIXED_WIRELESS} : Technology.nodeTechs();
    }

    /** Deletes the ISP: nodes come down, customers lose their plan (no refunds), and what's left in it goes to the owner. */
    public void deleteIsp(Isp isp) {
        var group = plugin.groups().groupOf(isp);
        if (group != null) {
            if (isp.balance() > 0) plugin.groups().creditTreasury(group, isp.balance(), "Closing balance of " + isp.name());
            isp.balance(0);
            plugin.groups().detach(group, isp);
        }
        plugin.workforce().companyRemoved(isp.ref());
        if (isp.balance() > 0) {
            PlayerProfile prof = plugin.store().profile(isp.owner());
            prof.balance(prof.balance() + isp.balance());
            prof.log(isp.balance(), "Closing balance of " + isp.name());
            plugin.billing().notify(isp.owner(), "<yellow><isp> closed. Its balance of <white><b></white> went to your wallet.",
                    Msg.p("isp", isp.name()), Msg.p("b", money(isp.balance())));
            isp.balance(0);
            plugin.store().markDirty();
        }
        for (Node n : new ArrayList<>(store.nodesOf(isp.id()))) removeNode(n, true);
        for (Router r : new ArrayList<>(store.routersOfIsp(isp.id()))) {
            plugin.billing().notify(r.owner(), "<red><isp> has shut down. Your internet at <white><h></white> is gone: pick a new provider on your router.",
                    Msg.p("isp", isp.name()), Msg.p("h", r.name()));
            r.clearService();
            refresh(r);
        }
        store.removeIsp(isp);
    }

    public int customers(Isp isp) {
        int n = 0;
        for (Router r : store.routersOfIsp(isp.id())) if (r.state() == Router.State.ACTIVE) n++;
        return n;
    }

    public void planDeleted(Isp isp, InternetPlan plan) {
        for (Router r : store.routersOfIsp(isp.id())) {
            if (!plan.id().equals(r.planId())) continue;
            plugin.billing().notify(r.owner(), "<red><isp> withdrew the <white><p></white> plan. Your internet at <white><h></white> is cancelled: pick a new plan on your router.",
                    Msg.p("isp", isp.name()), Msg.p("p", plan.name()), Msg.p("h", r.name()));
            r.clearService();
            refresh(r);
        }
        isp.plans().remove(plan.id());
        store.markDirty();
    }

    /** Wallet → ISP, through the company rules (owner only, the normal transfer fee). */
    public boolean deposit(Player p, Isp isp, long amount) {
        long before = isp.balance();
        plugin.companies().move(p, isp, amount, false);
        return isp.balance() != before;
    }

    /** ISP → owner's wallet, through the company rules (fee, the next upkeep stays, nothing while it owes its parent). */
    public boolean withdraw(Player p, Isp isp, long amount) {
        long before = isp.balance();
        plugin.companies().move(p, isp, amount, true);
        return isp.balance() != before;
    }

    /** 1.0-5.0 from measured speed, reliability, value for money and coverage. */
    public double rating(Isp isp) {
        double speed, value = 0, coverage;
        if (isp.carrierBacked()) {
            Carrier c = plugin.store().carrier(isp.carrierId());
            int towers = c == null ? 0 : (int) plugin.store().towersOf(c.id()).stream().filter(Tower::online).count();
            coverage = Math.min(1, towers / 8.0);
        } else {
            double sum = 0;
            for (Node n : store.nodesOf(isp.id())) {
                TechSpec s = spec(n.tech());
                sum += s.range() > 0 ? Math.min(s.range(), 1500) / 1500.0 : 0.8;
            }
            coverage = Math.min(1, sum / 4.0);
        }
        double nominal = 0;
        for (InternetPlan p : isp.plans().values()) {
            double cap = Math.min(p.speedCapMbps(), spec(p.tech()).maxMbps());
            nominal = Math.max(nominal, Math.min(1, Math.log10(1 + cap) / Math.log10(1 + 10000)));
            double ppd = plugin.sales().price(p) * 24.0 / p.periodHours();
            TechSpec s = spec(p.tech());
            double ref = (s.maxMbps() * 0.5) / (s.starterPrice() + 1);
            value += Math.clamp((cap / (ppd + 1)) / Math.max(0.001, ref) / 2, 0, 1);
        }
        if (!isp.plans().isEmpty()) value /= isp.plans().size();
        double delivered = 0;
        int n = 0;
        for (Router r : store.routersOfIsp(isp.id())) {
            if (r.state() != Router.State.ACTIVE) continue;
            InternetPlan t = termsOf(r, isp);
            Link l = link(r);
            n++;
            if (t != null && l != null) delivered += Math.min(1, l.downMbps() / Math.max(0.1, Math.min(t.speedCapMbps(), spec(t.tech()).maxMbps())));
        }
        double delivery = n == 0 ? 1 : delivered / n;
        speed = 0.5 * nominal + 0.5 * delivery;
        double care = plugin.workforce().support(isp); // customer care funding (3-day average)
        double score = 0.30 * speed + 0.20 * isp.reliability() + 0.20 * value + 0.15 * coverage + 0.15 * care;
        return 1 + 4 * Math.clamp(score, 0, 1);
    }

    /** "★★★☆☆ 3.4" */
    public String ratingPlain(Isp isp) {
        double r = rating(isp);
        int full = (int) Math.round(r);
        return "★".repeat(full) + "☆".repeat(5 - full) + String.format(Locale.ROOT, " %.1f", r);
    }

    /** The rating as a MiniMessage line for lore and chat. */
    public String ratingLore(Isp isp) {
        double r = rating(isp);
        int full = (int) Math.round(r);
        return "<gold>" + "★".repeat(full) + "<dark_gray>" + "☆".repeat(5 - full) + String.format(Locale.ROOT, " <white>%.1f", r);
    }

    // ================================================================== devices

    private static boolean freeSign(CellPlugin plugin, BroadbandStore store, Block b) {
        return store.deviceAt(BlockKey.of(b)) == null && plugin.towers().towerAt(b) == null;
    }

    /** Builds a node on the wall sign. Costs the ISP the node price (admins build free). */
    public Node installNode(Player p, Isp isp, Technology tech, String name, Block sign) {
        if (!cfg().enabled()) {
            Msg.send(p, "<red>Home internet is turned off on this server.");
            return null;
        }
        if (!canManage(p, isp)) {
            Msg.send(p, "<red>You don't work for <white><isp></white>.", Msg.p("isp", isp.name()));
            return null;
        }
        if (isp.carrierBacked() || !tech.nodeBased()) {
            Msg.send(p, "<red>5G Home runs on the carrier's towers: there are no nodes to build.");
            return null;
        }
        if (!cfg().nodeNamePattern().matcher(name).matches()) {
            Msg.send(p, "<red>Node names are 1-12 letters, numbers, _ or -, like <white>N1</white>.");
            return null;
        }
        if (store.nodeByName(isp.id(), name) != null) {
            Msg.send(p, "<red>Your ISP already has a node named <white><n></white>.", Msg.p("n", name));
            return null;
        }
        if (sign == null || !TowerManager.isWallSign(sign)) {
            Msg.send(p, "<red>Look at a wall sign (standing and hanging signs can't be devices).");
            return null;
        }
        if (!freeSign(plugin, store, sign)) {
            Msg.send(p, "<red>That sign is already in use.");
            return null;
        }
        int max = cfg().maxNodesPerIsp();
        if (max >= 0 && !p.hasPermission("mccomm.admin") && store.nodesOf(isp.id()).size() >= max) {
            Msg.send(p, "<red><isp> already has the maximum of <n> nodes.", Msg.p("isp", isp.name()), Msg.p("n", "" + max));
            return null;
        }
        BlockKey key = BlockKey.of(sign);
        for (Node n : store.nodesOf(isp.id())) {
            if (n.tech() == tech && sameWorld(n.key(), key) && dist(n.key(), key) < cfg().nodeMinSpacing()) {
                Msg.send(p, "<red>Too close to your node <white><n></white>: keep <white><d></white> blocks between nodes of the same technology.",
                        Msg.p("n", n.name()), Msg.p("d", "" + cfg().nodeMinSpacing()));
                return null;
            }
        }
        long cost = p.hasPermission("mccomm.admin") ? 0 : spec(tech).nodeCost();
        if (isp.balance() < cost) {
            Msg.send(p, "<red>A <t> node costs <white><amt></white> and <isp> has <white><bal></white>. Deposit into the ISP first.",
                    Msg.p("t", spec(tech).name()), Msg.p("amt", money(cost)), Msg.p("isp", isp.name()), Msg.p("bal", money(isp.balance())));
            return null;
        }
        isp.balance(isp.balance() - cost);
        if (cost > 0) isp.log(-cost, "Built " + spec(tech).name() + " node " + name);
        Node node = new Node(store.allocateNodeId(), isp.id(), tech, name, key, p.getUniqueId(), System.currentTimeMillis());
        store.addNode(node);
        refresh(node);
        return node;
    }

    public void removeNode(Node n, boolean clearSign) {
        BlockKey key = n.key();
        store.removeNode(n);
        for (Router r : store.routers()) if (r.nodeId() == n.id()) r.nodeId(0); // rebound on the next tick
        if (clearSign) clearSign(key);
        signText.remove(key);
        screens.remove(key);
        users.remove(n.id());
        simUsers.remove(n.id());
    }

    public boolean upgradeNode(Player p, Node n) {
        Isp isp = store.isp(n.ispId());
        if (isp == null || !canManage(p, isp)) return false;
        TechSpec s = spec(n.tech());
        if (n.tier() >= s.maxTier()) {
            Msg.send(p, "<red>That node is already at the top tier.");
            return false;
        }
        long cost = p.hasPermission("mccomm.admin") ? 0 : upgradeCost(n);
        if (isp.balance() < cost) {
            Msg.send(p, "<red>The upgrade costs <white><amt></white> and <isp> has <white><bal></white>.",
                    Msg.p("amt", money(cost)), Msg.p("isp", isp.name()), Msg.p("bal", money(isp.balance())));
            return false;
        }
        isp.balance(isp.balance() - cost);
        if (cost > 0) isp.log(-cost, "Upgraded node " + n.name() + " to tier " + (n.tier() + 1));
        n.tier(n.tier() + 1);
        store.markDirty();
        refresh(n);
        Msg.send(p, "<green>Node <white><n></white> is now tier <white><t></white>: it serves <white><amt></white> customers at full speed.",
                Msg.p("n", n.name()), Msg.p("t", "" + n.tier()), Msg.p("amt", "" + Math.round(capacity(n))));
        return true;
    }

    /** Pays a technician to fix a node's fault at once. */
    public boolean repairNode(Player p, Node n) {
        Isp isp = store.isp(n.ispId());
        if (isp == null || !canManage(p, isp) || !n.faulted()) return false;
        long cost = p.hasPermission("mccomm.admin") ? 0 : spec(n.tech()).repairCost();
        if (isp.balance() < cost) {
            Msg.send(p, "<red>A technician costs <white><amt></white> and <isp> has <white><bal></white>.",
                    Msg.p("amt", money(cost)), Msg.p("isp", isp.name()), Msg.p("bal", money(isp.balance())));
            return false;
        }
        isp.balance(isp.balance() - cost);
        if (cost > 0) isp.log(-cost, "Repaired node " + n.name());
        n.faultAt(0);
        store.markDirty();
        refresh(n);
        Msg.send(p, "<green>A technician fixed node <white><n></white>.", Msg.p("n", n.name()));
        return true;
    }

    public Router installRouter(Player p, Block sign, String name) {
        if (!cfg().enabled()) {
            Msg.send(p, "<red>Home internet is turned off on this server.");
            return null;
        }
        if (!DEVICE_NAME.matcher(name).matches()) {
            Msg.send(p, "<red>Names are 1-16 letters, numbers, spaces, _ or -.");
            return null;
        }
        if (sign == null || !TowerManager.isWallSign(sign)) {
            Msg.send(p, "<red>Look at a wall sign inside your home (standing and hanging signs can't be devices).");
            return null;
        }
        if (!freeSign(plugin, store, sign)) {
            Msg.send(p, "<red>That sign is already in use.");
            return null;
        }
        int max = cfg().maxRoutersPerPlayer();
        if (max >= 0 && !p.hasPermission("mccomm.admin") && store.routersOf(p.getUniqueId()).size() >= max) {
            Msg.send(p, "<red>You already have the maximum number of routers (<n>).", Msg.p("n", "" + max));
            return null;
        }
        Router r = new Router(store.allocateRouterId(), p.getUniqueId(), name, BlockKey.of(sign), System.currentTimeMillis());
        r.open(cfg().guestWifiDefault());
        store.addRouter(r);
        refresh(r);
        return r;
    }

    /** Buys the next Wi-Fi generation for the router: more range and room for more guests. The price is spent. */
    public boolean upgradeWifi(Player p, Router r) {
        if (!r.owner().equals(p.getUniqueId())) return false;
        if (r.tier() >= cfg().maxWifiTier()) {
            Msg.send(p, "<red>That router is already the newest generation.");
            return false;
        }
        WifiTier next = cfg().tier(r.tier() + 1);
        PlayerProfile prof = plugin.store().profile(p.getUniqueId());
        long cost = p.hasPermission("mccomm.admin") ? 0 : next.price();
        if (prof.balance() < cost) {
            Msg.send(p, "<red><n> costs <white><amt></white>; you have <white><bal></white>.", Msg.p("n", next.name()),
                    Msg.p("amt", money(cost)), Msg.p("bal", money(prof.balance())));
            return false;
        }
        prof.balance(prof.balance() - cost);
        if (cost > 0) prof.log(-cost, "Router upgrade: " + next.name());
        r.tier(r.tier() + 1);
        wifiCache.clear();
        store.markDirty();
        plugin.store().markDirty();
        Msg.send(p, "<green>Router upgraded to <white><n></white>: reach <white><reach> blocks</white>, <white><g></white> guests.", Msg.p("n", next.name()),
                Msg.p("reach", "" + Math.round(reach(r))), Msg.p("g", "" + next.maxGuests()));
        return true;
    }

    /** Sets a router up as a home or a business site. Only possible while it has no plan, because plans are for one or the other. */
    public boolean setSite(Player p, Router r, boolean business) {
        if (!r.owner().equals(p.getUniqueId()) && !p.hasPermission("mccomm.admin")) return false;
        if (r.business() == business) return true;
        if (r.subscribed()) {
            Msg.send(p, "<red>Cancel this router's plan first: home and business plans can't be swapped on a live line.");
            return false;
        }
        r.business(business);
        store.markDirty();
        refresh(r);
        Msg.send(p, "<green>This router is now a <site>.", Msg.p("site",business ? "business site" : "home"));
        return true;
    }

    public Computer installComputer(Player p, Block sign, String name) {
        if (!cfg().enabled()) {
            Msg.send(p, "<red>Home internet is turned off on this server.");
            return null;
        }
        if (!DEVICE_NAME.matcher(name).matches()) {
            Msg.send(p, "<red>Names are 1-16 letters, numbers, spaces, _ or -.");
            return null;
        }
        if (sign == null || !TowerManager.isWallSign(sign)) {
            Msg.send(p, "<red>Look at a wall sign (standing and hanging signs can't be devices).");
            return null;
        }
        if (!freeSign(plugin, store, sign)) {
            Msg.send(p, "<red>That sign is already in use.");
            return null;
        }
        int max = cfg().maxComputersPerPlayer();
        if (max >= 0 && !p.hasPermission("mccomm.admin") && store.computersOf(p.getUniqueId()).size() >= max) {
            Msg.send(p, "<red>You already have the maximum number of computers (<n>).", Msg.p("n", "" + max));
            return null;
        }
        Computer c = new Computer(store.allocateComputerId(), p.getUniqueId(), name, BlockKey.of(sign), System.currentTimeMillis());
        store.addComputer(c);
        refresh(c);
        return c;
    }

    /** Puts a Wi-Fi extender on a wall sign. The price is spent from the player's wallet. */
    public Extender installExtender(Player p, Block sign, String name) {
        if (!cfg().enabled()) {
            Msg.send(p, "<red>Home internet is turned off on this server.");
            return null;
        }
        if (!DEVICE_NAME.matcher(name).matches()) {
            Msg.send(p, "<red>Names are 1-16 letters, numbers, spaces, _ or -.");
            return null;
        }
        if (sign == null || !TowerManager.isWallSign(sign)) {
            Msg.send(p, "<red>Look at a wall sign within reach of your router's Wi-Fi (standing and hanging signs can't be devices).");
            return null;
        }
        if (!freeSign(plugin, store, sign)) {
            Msg.send(p, "<red>That sign is already in use.");
            return null;
        }
        var cf = cfg().wifi().extender();
        boolean admin = p.hasPermission("mccomm.admin");
        if (cf.maxPerPlayer() >= 0 && !admin && store.extendersOf(p.getUniqueId()).size() >= cf.maxPerPlayer()) {
            Msg.send(p, "<red>You already have the maximum number of extenders (<n>).", Msg.p("n", "" + cf.maxPerPlayer()));
            return null;
        }
        PlayerProfile prof = plugin.store().profile(p.getUniqueId());
        long cost = admin ? 0 : cf.price();
        if (prof.balance() < cost) {
            Msg.send(p, "<red>An extender costs <white><amt></white>; you have <white><bal></white>.", Msg.p("amt", money(cost)), Msg.p("bal", money(prof.balance())));
            return null;
        }
        Extender x = new Extender(store.allocateExtenderId(), p.getUniqueId(), name, BlockKey.of(sign), System.currentTimeMillis());
        if (cost > 0) {
            prof.balance(prof.balance() - cost);
            prof.log(-cost, "Wi-Fi extender: " + name);
            plugin.store().markDirty();
        }
        store.addExtender(x);
        wifiCache.clear();
        refresh(x);
        return x;
    }

    public void removeExtender(Extender x, boolean clearSign) {
        BlockKey key = x.key();
        screens.remove(key);
        store.removeExtender(x);
        wifiCache.clear();
        if (clearSign) clearSign(key);
        signText.remove(key);
    }

    public void removeRouter(Router r, boolean clearSign) {
        BlockKey key = r.key();
        screens.remove(key);
        store.removeRouter(r);
        if (clearSign) clearSign(key);
        signText.remove(key);
    }

    public void removeComputer(Computer c, boolean clearSign) {
        BlockKey key = c.key();
        screens.remove(key);
        store.removeComputer(c);
        if (clearSign) clearSign(key);
        signText.remove(key);
    }

    /** Removes whatever device sits on this key (used when its sign is broken). */
    public void removeDevice(Device d, boolean clearSign) {
        switch (d) {
            case Node n -> removeNode(n, clearSign);
            case Router r -> removeRouter(r, clearSign);
            case Computer c -> removeComputer(c, clearSign);
            case Extender x -> removeExtender(x, clearSign);
        }
    }

    private void clearSign(BlockKey key) {
        World w = Bukkit.getWorld(key.world());
        if (w == null || !w.isChunkLoaded(key.x() >> 4, key.z() >> 4)) return;
        Block b = w.getBlockAt(key.x(), key.y(), key.z());
        if (b.getState() instanceof Sign sign) {
            SignSide side = sign.getSide(Side.FRONT);
            for (int i = 0; i < 4; i++) side.line(i, Component.empty());
            sign.setWaxed(false);
            sign.update();
        }
    }

    // ============================================================= subscriptions

    /**
     * Orders a plan for this router: pays the install fee and the last-mile drop (unless switching plans on the
     * same ISP and technology) and the first period, then the technician comes. Returns false and tells the
     * player if it can't be done.
     */
    public boolean subscribe(Player p, Router r, Isp isp, InternetPlan plan) {
        if (!r.owner().equals(p.getUniqueId())) {
            Msg.send(p, "<red>That isn't your router.");
            return false;
        }
        PlayerProfile prof = plugin.store().profile(p.getUniqueId());
        if (!prof.overdue().isEmpty()) {
            Msg.send(p, "<red>You have an overdue balance of <white><o></white>. Pay it off (deposit in your wallet) first.",
                    Msg.p("o", money(prof.totalOverdue())));
            return false;
        }
        if (isp.plans().get(plan.id()) != plan) {
            Msg.send(p, "<red>That plan is no longer offered.");
            return false;
        }
        Quote q = quote(isp, plan, r.key(), r);
        if (!q.ok()) {
            Msg.send(p, "<red>Can't order that here: <why>.", Msg.p("why", q.problem()));
            return false;
        }
        boolean switching = r.subscribed() && isp.id().equals(r.ispId()) && plan.tech() == r.tech();
        long upfront = switching ? 0 : plan.installFee() + q.dropFee();
        long cost = q.price() + upfront;
        if (prof.balance() < cost) {
            Msg.send(p, "<red>That costs <white><price></white> now; you have <white><bal></white>. Deposit ores in your wallet first.",
                    Msg.p("price", money(cost)), Msg.p("bal", money(prof.balance())));
            return false;
        }
        if (cost > 0) charge(p.getUniqueId(), isp, cost, plan.name() + (switching ? "" : " (install + first period)"));
        long now = System.currentTimeMillis();
        long delay = switching ? 0 : cfg().installDelaySeconds() * 1000L;
        r.ispId(isp.id());
        r.planId(plan.id());
        r.tech(plan.tech());
        r.terms(plan.copy());
        r.dataLeftMB(plan.unlimited() ? 0 : plan.dataMB());
        r.nodeId(q.node() == null ? 0 : q.node().id());
        r.towerId(q.tower() == null ? 0 : q.tower().id());
        r.nextBill(now + plan.periodMillis());
        r.warned(false);
        r.suspendedSince(0);
        r.installAt(now + delay);
        r.state(delay > 0 ? Router.State.INSTALLING : Router.State.ACTIVE);
        store.markDirty();
        rebuildUsers();
        refreshFw();
        refresh(r);
        if (delay > 0) {
            Msg.send(p, "<green>Ordered <white><pl></white> from <white><isp></white> for <white><amt></white>. A technician is on the way (about <secs> seconds).",
                    Msg.p("pl", plan.name()), Msg.p("isp", isp.name()), Msg.p("amt", money(cost)), Msg.p("secs", "" + cfg().installDelaySeconds()));
            if (q.node() != null) showLine(p, r);
        } else {
            Msg.send(p, "<green>You're on <white><pl></white> now (<amt>).", Msg.p("pl", plan.name()), Msg.p("amt", money(cost)));
        }
        if (q.dropFee() > 0 && !switching) {
            Msg.send(p, "<gray>Includes <white><d></white> for the cable from <white><n></white>, <white><m></white> away.",
                    Msg.p("d", money(q.dropFee())), Msg.p("n", q.sourceName()), Msg.p("m", Msg.distance(q.distance())));
        }
        return true;
    }

    /** Ends the subscription. No refund; the router stays so another provider can be picked. */
    public void cancel(Router r) {
        r.clearService();
        store.markDirty();
        rebuildUsers();
        refresh(r);
    }

    /** Pays the missed renewal of a suspended line right now. */
    public boolean payNow(Player p, Router r) {
        if (r.state() != Router.State.SUSPENDED) return false;
        Isp isp = store.isp(r.ispId());
        InternetPlan plan = isp == null ? null : isp.plans().get(r.planId());
        if (plan == null) return false;
        long price = priceFor(isp, plan, r.owner());
        if (!charge(p.getUniqueId(), isp, price, plan.name())) {
            Msg.send(p, "<red>You need <white><amt></white> in your wallet; you have <white><bal></white>.",
                    Msg.p("amt", money(price)), Msg.p("bal", money(plugin.store().profile(p.getUniqueId()).balance())));
            return false;
        }
        reactivate(r, plan, System.currentTimeMillis());
        Msg.send(p, "<green>Paid. Your internet is back on.");
        return true;
    }

    private void reactivate(Router r, InternetPlan plan, long now) {
        r.state(Router.State.ACTIVE);
        r.suspendedSince(0);
        r.warned(false);
        r.terms(plan.copy());
        r.dataLeftMB(plan.unlimited() ? 0 : plan.dataMB());
        r.nextBill(now + plan.periodMillis());
        store.markDirty();
        refresh(r);
    }

    /** Moves money from the customer's wallet to the ISP, with ledger lines on both sides. */
    private boolean charge(UUID player, Isp isp, long amount, String note) {
        if (amount <= 0) return true;
        PlayerProfile prof = plugin.store().profile(player);
        if (prof.balance() < amount) return false;
        prof.balance(prof.balance() - amount);
        prof.log(-amount, isp.name() + " · " + note);
        isp.balance(isp.balance() + amount);
        isp.log(amount, note + " · " + nameOf(player));
        store.markDirty();
        plugin.store().markDirty();
        return true;
    }

    // ===================================================================== tick

    private void rebuildUsers() {
        users.clear();
        towerUsers.clear();
        for (Router r : store.routers()) {
            if (r.state() != Router.State.ACTIVE) continue;
            if (r.nodeId() != 0) users.merge(r.nodeId(), 1, Integer::sum);
            if (r.towerId() != 0) towerUsers.merge(r.towerId(), 1, Integer::sum);
        }
    }

    /** Runs every few seconds: upkeep, faults, renewals, technicians, node re-binding, the market, signs. */
    public void tick() {
        if (!cfg().enabled()) return;
        long now = System.currentTimeMillis();
        long elapsed = lastTick == 0 ? 0 : Math.max(0, now - lastTick);
        lastTick = now;

        validateLoaded();
        syncCarrierIsps();

        // ISP upkeep: node and transit upkeep every period; a missed bill darkens the whole network until it is paid.
        for (Isp isp : new ArrayList<>(store.isps())) {
            long period = cfg().upkeepPeriodHours() * HOUR;
            if (isp.nextUpkeep() == 0) {
                isp.nextUpkeep(now + period);
                store.markDirty();
            }
            if (now < isp.nextUpkeep()) continue;
            long due = upkeepDue(isp);
            if (due > isp.balance()) plugin.groups().backBill(isp, due - isp.balance(), "Upkeep");
            if (due <= 0 || isp.balance() >= due) {
                if (due > 0) {
                    isp.balance(isp.balance() - due);
                    isp.log(-due, "Node and transit upkeep");
                }
                boolean wasDark = !isp.paidUp();
                isp.unpaidSince(0);
                isp.nextUpkeep(now + period);
                if (wasDark) plugin.billing().notify(isp.owner(), "<green><isp> paid its upkeep: the network is back up.", Msg.p("isp", isp.name()));
                store.markDirty();
            } else if (isp.unpaidSince() == 0) {
                isp.unpaidSince(now);
                plugin.billing().notify(isp.owner(), "<red><isp> can't pay its upkeep (<d>). The network is down until you deposit.",
                        Msg.p("isp", isp.name()), Msg.p("d", money(due)));
                store.markDirty();
            }
        }

        faultTick(now, elapsed);
        rebuildUsers();
        marketTick(now);
        refreshTransit();
        refreshFw();
        refreshGuests();
        wifiCache.clear();

        // Customers.
        for (Router r : new ArrayList<>(store.routers())) {
            Isp isp = r.ispId() == null ? null : store.isp(r.ispId());
            InternetPlan plan = isp == null ? null : isp.plans().get(r.planId());
            if (r.state() != Router.State.NONE && (isp == null || plan == null)) {
                terminate(r, "<red>Your internet at <white><h></white> was cancelled: the plan no longer exists.");
                continue;
            }
            switch (r.state()) {
                case INSTALLING -> {
                    if (now >= r.installAt()) {
                        r.state(Router.State.ACTIVE);
                        store.markDirty();
                        plugin.billing().notify(r.owner(), "<green>Your <white><t></white> internet from <white><isp></white> is live at <white><h></white>!",
                                Msg.p("t", spec(plan.tech()).name()), Msg.p("isp", isp.name()), Msg.p("h", r.name()));
                    }
                }
                case ACTIVE -> tickActive(r, isp, plan, now, elapsed);
                case SUSPENDED -> {
                    long price = priceFor(isp, plan, r.owner());
                    if (charge(r.owner(), isp, price, plan.name())) {
                        reactivate(r, plan, now);
                        plugin.billing().notify(r.owner(), "<green>Payment received: your internet at <white><h></white> is back on.", Msg.p("h", r.name()));
                    } else if (now - r.suspendedSince() >= cfg().suspendHours() * HOUR) {
                        terminate(r, "<red>Your internet at <white><h></white> was cut for non-payment. Pick a plan again on your router.");
                    }
                }
                default -> {
                }
            }
        }
        rebuildUsers();
        refreshFw();
        refreshTransit();

        // Reliability: a running average of how much of each network was up.
        for (Isp isp : store.isps()) {
            List<Node> nodes = store.nodesOf(isp.id());
            double up = !isp.paidUp() ? 0 : nodes.isEmpty() ? 1 : nodes.stream().filter(n -> !n.faulted()).count() / (double) nodes.size();
            isp.reliability(isp.reliability() * 0.999 + up * 0.001);
        }
        refreshAll();
    }

    private void tickActive(Router r, Isp isp, InternetPlan live, long now, long elapsed) {
        InternetPlan terms = termsOf(r, isp);
        // Re-bind to a working node if ours is gone, faulted, or the ISP rebuilt.
        if (live.tech() != Technology.FIXED_WIRELESS) {
            Node node = store.node(r.nodeId());
            if (node == null || node.tech() != live.tech() || !node.ispId().equals(isp.id()) || node.faulted()) {
                Node better = bestNode(isp, live.tech(), r.key());
                if (better != null || node == null) {
                    r.nodeId(better == null ? 0 : better.id());
                    store.markDirty();
                }
            }
        }
        // No service (outage, no node, no sky): the customer isn't billed meanwhile.
        if (problem(r) != null) {
            r.nextBill(r.nextBill() + elapsed);
            return;
        }
        PlayerProfile prof = plugin.store().profile(r.owner());
        long price = priceFor(isp, live, r.owner());
        if (!r.warned() && prof.balance() < price && now >= r.nextBill() - cfg().warnHours() * HOUR) {
            r.warned(true);
            plugin.billing().notify(r.owner(), "<gold>Your <white><isp></white> internet at <white><h></white> renews soon for <white><p></white>, but your wallet has <white><bal></white>. Deposit ores or the line will be suspended.",
                    Msg.p("isp", isp.name()), Msg.p("h", r.name()), Msg.p("p", money(price)), Msg.p("bal", money(prof.balance())));
            store.markDirty();
        }
        for (int guard = 0; guard < 60 && r.state() == Router.State.ACTIVE && now >= r.nextBill(); guard++) {
            price = priceFor(isp, live, r.owner());
            if (charge(r.owner(), isp, price, live.name())) {
                if (terms == null || !live.sameTermsAs(terms)) {
                    plugin.billing().notify(r.owner(), "<gray>Your <white><isp></white> plan's updated terms start now: <white><p></white> for <white><pr></white> every <white><per></white>.",
                            Msg.p("isp", isp.name()), Msg.p("p", live.name() + " (" + Msg.speed(live.speedCapMbps()) + ")"),
                            Msg.p("pr", money(price)), Msg.p("per", dev.mccomm.cell.economy.BillingService.period(live.periodHours())));
                }
                r.terms(live.copy());
                terms = r.terms();
                r.dataLeftMB(live.unlimited() ? 0 : live.dataMB());
                r.nextBill(r.nextBill() + live.periodMillis());
                r.warned(false);
                store.markDirty();
            } else {
                r.state(Router.State.SUSPENDED);
                r.suspendedSince(now);
                store.markDirty();
                plugin.billing().notify(r.owner(), "<red>Your <white><isp></white> internet at <white><h></white> is suspended: not enough credits for <white><p></white>. Deposit within <hrs> hours or the line is cut.",
                        Msg.p("isp", isp.name()), Msg.p("h", r.name()), Msg.p("p", money(price)), Msg.p("hrs", "" + cfg().suspendHours()));
            }
        }
    }

    private void terminate(Router r, String message) {
        plugin.billing().notify(r.owner(), message, Msg.p("h", r.name()));
        r.clearService();
        store.markDirty();
    }

    // ---- faults

    /** Random node faults (more in storms), and the automatic repair after a while. */
    private void faultTick(long now, long elapsed) {
        if (!cfg().faults().enabled()) {
            for (Node n : store.nodes()) if (n.faulted()) n.faultAt(0);
            return;
        }
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (Node n : store.nodes()) {
            Isp isp = store.isp(n.ispId());
            if (isp == null) continue;
            if (n.faulted()) {
                long auto = Math.round(autoRepairHours(isp) * GAME_HOUR); // in-game hours, faster with funded field crews
                if (now - n.faultAt() >= auto) {
                    n.faultAt(0);
                    store.markDirty();
                    plugin.billing().notify(isp.owner(), "<green>A crew fixed the fault on node <white><n></white> of <isp>.", Msg.p("n", n.name()), Msg.p("isp", isp.name()));
                }
                continue;
            }
            if (!isp.paidUp() || elapsed <= 0) continue;
            TechSpec s = spec(n.tech());
            if (s.faultPerHour() <= 0) continue;
            World w = n.world();
            double p = s.faultPerHour() * (elapsed / (double) HOUR) * (w != null && w.hasStorm() ? s.stormFaultFactor() : 1);
            if (rnd.nextDouble() < p) {
                n.faultAt(now);
                store.markDirty();
                double hours = autoRepairHours(isp);
                plugin.billing().notify(isp.owner(), "<red>Node <white><n></white> of <isp> has a fault: its customers are offline. Repair it from the node screen, or your field crews fix it in <h> in-game hours (about <m> min).",
                        Msg.p("n", n.name()), Msg.p("isp", isp.name()), Msg.p("h", String.format("%.1f", hours)), Msg.p("m", "" + Math.max(1, Math.round(hours * GAME_HOUR / 60_000.0))));
            }
        }
    }

    // ---- the household market

    private void marketTick(long now) {
        int households = cfg().market().households();
        if (households <= 0 && cfg().business().simBusinesses() <= 0) {
            if (!simUsers.isEmpty() || simReady) {
                simUsers.clear();
                for (Isp i : store.isps()) {
                    i.simCustomers(0);
                    i.simBusinesses(0);
                }
                simReady = false;
            }
            return;
        }
        long step = cfg().market().stepMinutes() * 60_000L;
        boolean due = now - store.marketAt() >= step;
        if (!simReady || due) {
            allocateHouseholds(households);
            simReady = true;
        }
        if (due) {
            long elapsed = store.marketAt() == 0 ? step : Math.min(now - store.marketAt(), 24 * HOUR);
            if (cfg().market().pays()) payHouseholds(elapsed);
            store.marketAt(now);
        }
    }

    private List<Node> healthy(Isp isp) {
        List<Node> out = new ArrayList<>();
        for (Node n : store.nodesOf(isp.id())) if (!n.faulted()) out.add(n);
        return out;
    }

    /** Business plans start at this many times a home plan's price (the starter plans, and the fair price below). */
    public static final int BUSINESS_PRICE_FACTOR = 3;

    /**
     * The ISP's average daily price for one kind of customer, divided by the fair price: the average starter price
     * of the same technologies (times {@link #BUSINESS_PRICE_FACTOR} for businesses). 1 = fair, 0 = nothing sold.
     */
    public double priceRatio(Isp isp, Segment seg) {
        double price = 0, fair = 0;
        for (InternetPlan p : isp.plans().values()) {
            if (p.segment() != seg) continue;
            price += plugin.sales().price(p) * 24.0 / Math.max(1, p.periodHours());
            fair += spec(p.tech()).starterPrice() * (seg == Segment.BUSINESS ? BUSINESS_PRICE_FACTOR : 1);
        }
        return fair <= 0 ? (price > 0 ? Double.MAX_VALUE : 0) : price / fair;
    }

    /** True if the ISP sells at least one plan to this kind of customer. */
    private static boolean sells(Isp isp, Segment seg) {
        for (InternetPlan p : isp.plans().values()) if (p.segment() == seg) return true;
        return false;
    }

    /** Simulated households and businesses pick providers by rating (squared), limited by the room on healthy nodes. */
    private void allocateHouseholds(int households) {
        simUsers.clear();
        for (Isp isp : store.isps()) isp.simBusinesses(0);
        allocate(households, Segment.RESIDENTIAL);
        allocate(cfg().business().simBusinesses(), Segment.BUSINESS);
    }

    private void allocate(int count, Segment seg) {
        List<Isp> eligible = new ArrayList<>();
        Map<String, Double> score = new HashMap<>();
        double total = 0;
        for (Isp isp : store.isps()) {
            if (seg == Segment.RESIDENTIAL) isp.simCustomers(0);
            if (count <= 0 || isp.carrierBacked() || !isp.paidUp() || !sells(isp, seg) || healthy(isp).isEmpty()) continue;
            // Same price rule as the carrier market: too dear and nobody signs up, dearer than fair and fewer do.
            double r = priceRatio(isp, seg);
            if (r >= plugin.settings().market.refuseRatio()) continue;
            double s = Math.pow(rating(isp), 2) * Math.min(1, 1 / (r * r));
            eligible.add(isp);
            score.put(isp.id(), s);
            total += s;
        }
        if (eligible.isEmpty() || total <= 0) return;
        for (Isp isp : eligible) {
            List<Node> nodes = healthy(isp);
            double room = 0;
            for (Node n : nodes) room += capacity(n) - users.getOrDefault(n.id(), 0) - simUsers.getOrDefault(n.id(), 0);
            int alloc = (int) Math.min(Math.floor(count * score.get(isp.id()) / total), Math.max(0, Math.floor(room)));
            if (seg == Segment.BUSINESS) isp.simBusinesses(alloc);
            else isp.simCustomers(alloc);
            for (int i = 0; i < alloc; i++) simUsers.merge(nodes.get(i % nodes.size()).id(), 1, Integer::sum);
        }
    }

    private void payHouseholds(long elapsedMs) {
        for (Isp isp : store.isps()) {
            payFrom(isp, Segment.RESIDENTIAL, isp.simCustomers(), elapsedMs, "Simulated households");
            payFrom(isp, Segment.BUSINESS, isp.simBusinesses(), elapsedMs, "Simulated businesses");
        }
    }

    /** Pays an ISP what its simulated customers of one kind owe: the average price of its plans for them. */
    private void payFrom(Isp isp, Segment seg, int customers, long elapsedMs, String note) {
        if (customers <= 0) return;
        double perHour = 0;
        int n = 0;
        for (InternetPlan p : isp.plans().values()) {
            if (p.segment() != seg) continue;
            perHour += plugin.sales().price(p) / (double) p.periodHours();
            n++;
        }
        long rev = n == 0 ? 0 : Math.round(customers * (perHour / n) * (elapsedMs / (double) HOUR));
        if (rev > 0) {
            isp.balance(isp.balance() + rev);
            isp.log(rev, note);
            store.markDirty();
        }
    }

    /**
     * A carrier-backed ISP always belongs to its carrier's owner (so it follows a carrier sold or handed over,
     * and fixes ISPs a staff member created before this rule). If the carrier is gone, the ISP closes.
     */
    private void syncCarrierIsps() {
        for (Isp isp : new ArrayList<>(store.isps())) {
            if (!isp.carrierBacked()) continue;
            Carrier c = plugin.store().carrier(isp.carrierId());
            if (c == null) {
                deleteIsp(isp);
                store.markDirty();
            } else if (!c.owner().equals(isp.owner())) {
                isp.employees().remove(c.owner());
                isp.owner(c.owner());
                store.markDirty();
            }
        }
    }

    // ---- timewarp

    /** Admin/testing: moves every schedule back by {@code d} ms, as if that time had passed (see /celladmin timewarp). */
    public void shift(long d) {
        for (Isp i : store.isps()) {
            if (i.nextUpkeep() > 0) i.nextUpkeep(i.nextUpkeep() - d);
            if (i.unpaidSince() > 0) i.unpaidSince(i.unpaidSince() - d);
        }
        for (Router r : store.routers()) {
            if (r.nextBill() > 0) r.nextBill(r.nextBill() - d);
            if (r.installAt() > 0) r.installAt(r.installAt() - d);
            if (r.suspendedSince() > 0) r.suspendedSince(r.suspendedSince() - d);
        }
        for (Node n : store.nodes()) if (n.faulted()) n.faultAt(n.faultAt() - d);
        if (store.marketAt() > 0) store.marketAt(store.marketAt() - d);
        store.markDirty();
    }

    /** Removes devices whose sign is gone (broken by something that didn't fire a break event), and stale listings. */
    private void validateLoaded() {
        for (Device d : new ArrayList<>(allDevices())) {
            World w = Bukkit.getWorld(d.key().world());
            if (w == null || !w.isChunkLoaded(d.key().x() >> 4, d.key().z() >> 4)) continue;
            Block b = w.getBlockAt(d.key().x(), d.key().y(), d.key().z());
            if (!TowerManager.isWallSign(b)) {
                removeDevice(d, false);
                store.markDirty();
            }
        }
        long ttl = cfg().browser().listingDays() * 24 * HOUR;
        long now = System.currentTimeMillis();
        if (store.listings().removeIf(l -> now - l.time() > ttl)) store.markDirty();
    }

    public List<Device> allDevices() {
        List<Device> out = new ArrayList<>(store.nodes());
        out.addAll(store.routers());
        out.addAll(store.computers());
        out.addAll(store.extenders());
        return out;
    }

    // ==================================================================== signs

    public void refreshAll() {
        for (Device d : allDevices()) refresh(d);
    }

    public void refresh(Device d) {
        // Nobody can see a sign in an unloaded chunk: skip the work (it refreshes again once loaded).
        World w = Bukkit.getWorld(d.key().world());
        if (w == null || !w.isChunkLoaded(d.key().x() >> 4, d.key().z() >> 4)) return;
        if (cfg().screens()) {
            // The screen entities show everything; the sign itself stays blank (and waxed).
            write(d.key(), BLANK);
            screens.ensure(d);
            return;
        }
        Component[] lines = switch (d) {
            case Node n -> nodeLines(n);
            case Router r -> routerLines(r);
            case Computer c -> computerLines(c);
            case Extender x -> extenderLines(x);
        };
        write(d.key(), lines);
    }

    private static final Component[] BLANK = {Component.empty(), Component.empty(), Component.empty(), Component.empty()};

    private static String trunc(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }

    private Component[] nodeLines(Node n) {
        Isp isp = store.isp(n.ispId());
        TechSpec s = spec(n.tech());
        boolean up = isp != null && isp.paidUp() && !n.faulted();
        String state = n.faulted() ? " · FAULT" : up ? " · UP" : " · DOWN";
        return new Component[]{
                Component.text("[ISP Node]", NamedTextColor.DARK_AQUA),
                Component.text(trunc(isp == null ? "?" : isp.name(), 15)),
                Component.text(s.name() + " · Tier " + n.tier()),
                Component.text(trunc(n.name(), 8) + state, up ? NamedTextColor.DARK_GREEN : NamedTextColor.DARK_RED)};
    }

    private Component[] routerLines(Router r) {
        Component head = Component.text("[Router]", NamedTextColor.DARK_AQUA);
        Component name = Component.text(trunc(r.name(), 15));
        Isp isp = r.ispId() == null ? null : store.isp(r.ispId());
        String ispName = isp == null ? "?" : trunc(isp.name(), 15);
        return switch (r.state()) {
            case NONE -> new Component[]{head, name, Component.text("No service", NamedTextColor.GRAY), Component.text("Right-click", NamedTextColor.DARK_GRAY)};
            case INSTALLING -> new Component[]{head, name, Component.text("Installing...", NamedTextColor.GOLD), Component.text(ispName)};
            case SUSPENDED -> new Component[]{head, name, Component.text("SUSPENDED", NamedTextColor.DARK_RED), Component.text(ispName)};
            case ACTIVE -> {
                Link l = link(r);
                yield new Component[]{head, name, Component.text(ispName + (r.tech() == null ? "" : " " + spec(r.tech()).name()), NamedTextColor.DARK_GREEN),
                        l == null ? Component.text("No signal", NamedTextColor.DARK_RED) : Component.text(Msg.speed(l.downMbps()))};
            }
        };
    }

    private Component[] extenderLines(Extender x) {
        Router src = sourceOf(x);
        return new Component[]{
                Component.text("[Extender]", NamedTextColor.DARK_AQUA),
                Component.text(trunc(x.name(), 15)),
                src == null ? Component.text("No router", NamedTextColor.DARK_RED) : Component.text("Repeats " + trunc(src.name(), 8), NamedTextColor.DARK_GREEN),
                Component.text("Right-click", NamedTextColor.DARK_GRAY)};
    }

    private Component[] computerLines(Computer c) {
        return new Component[]{
                Component.text("[Computer]", NamedTextColor.DARK_AQUA),
                Component.text(trunc(c.name(), 15)),
                Component.text(trunc(nameOf(c.owner()), 15), NamedTextColor.GRAY),
                Component.text("Right-click", NamedTextColor.DARK_GRAY)};
    }

    private void write(BlockKey key, Component[] lines) {
        StringBuilder sb = new StringBuilder();
        for (Component c : lines) sb.append(PlainTextComponentSerializer.plainText().serialize(c)).append('\n');
        String text = sb.toString();
        if (text.equals(signText.get(key))) return;
        World w = Bukkit.getWorld(key.world());
        if (w == null || !w.isChunkLoaded(key.x() >> 4, key.z() >> 4)) return;
        Block b = w.getBlockAt(key.x(), key.y(), key.z());
        if (!(b.getState() instanceof Sign sign)) return;
        SignSide side = sign.getSide(Side.FRONT);
        for (int i = 0; i < 4; i++) side.line(i, lines[i]);
        sign.setWaxed(true);
        sign.update(false, false);
        signText.put(key, text);
    }

    // ============================================================ tests, lines

    /** An animated ping/download/upload test over a home connection, shown in a boss bar. Uses data from a capped plan. */
    public void speedtest(Player p, Link l) {
        UUID id = p.getUniqueId();
        if (tests.containsKey(id)) {
            Msg.send(p, "<red>A speed test is already running.");
            return;
        }
        long now = System.currentTimeMillis();
        Long until = testCooldown.get(id);
        if (until != null && until > now && !p.hasPermission("mccomm.admin")) {
            Msg.send(p, "<red>Wait <white><secs>s</white> before testing again.", Msg.p("secs", "" + ((until - now) / 1000 + 1)));
            return;
        }
        testCooldown.put(id, now + cfg().speedtestCooldown() * 1000L);
        double jitter = cfg().jitterPercent() / 100.0;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double ping = l.pingMs() * (1 + r.nextDouble(-jitter, jitter));
        double dl = l.downMbps() * (1 + r.nextDouble(-jitter, jitter));
        double ul = l.upMbps() * (1 + r.nextDouble(-jitter, jitter));
        double usedMB = (dl * TEST_DL / 20.0 + ul * TEST_UL / 20.0) / 8.0;
        useWifiData(l, usedMB);
        String isp = l.isp().name(), plan = l.plan().name(), tech = spec(l.tech()).name(), node = l.sourceName();
        String dataLine = l.plan().unlimited() ? "Unlimited data" : dev.mccomm.cell.Settings.data(usedMB) + " used · "
                + dev.mccomm.cell.Settings.data(l.router().dataLeftMB()) + " left";

        BossBar bar = BossBar.bossBar(Msg.mm("<aqua>Speedtest <gray>· connecting…"), 0f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
        p.showBossBar(bar);
        final int[] tick = {0};
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int t = ++tick[0];
            if (!p.isOnline()) {
                stopTest(id, bar, p);
                return;
            }
            if (t <= TEST_PING) {
                float f = t / (float) TEST_PING;
                bar.progress(f);
                bar.name(Msg.mm("<aqua>Ping <white><v> ms", Msg.p("v", "" + Math.round(ping * (0.5 + f / 2)))));
            } else if (t <= TEST_PING + TEST_DL) {
                float f = (t - TEST_PING) / (float) TEST_DL;
                bar.color(BossBar.Color.GREEN);
                bar.progress(f);
                bar.name(Msg.mm("<green>↓ Download <white><v>", Msg.p("v", Msg.speed(dl * Math.min(1, f * 1.3) * (0.92 + 0.08 * Math.sin(t))))));
            } else if (t <= TEST_PING + TEST_DL + TEST_UL) {
                float f = (t - TEST_PING - TEST_DL) / (float) TEST_UL;
                bar.color(BossBar.Color.PURPLE);
                bar.progress(f);
                bar.name(Msg.mm("<light_purple>↑ Upload <white><v>", Msg.p("v", Msg.speed(ul * Math.min(1, f * 1.3) * (0.92 + 0.08 * Math.sin(t))))));
            } else {
                stopTest(id, bar, p);
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.8f, 1.6f);
                Msg.raw(p, "<dark_gray>━━━ <aqua>Speedtest</aqua> ━━━");
                Msg.raw(p, "<gray>Provider: <white><isp> <gray>· <white><pl> <gray>· <white><t> <gray>· via <white><n>",
                        Msg.p("isp", isp), Msg.p("pl", plan), Msg.p("t", tech), Msg.p("n", node));
                Msg.raw(p, "<gray>Ping: <white><v> ms", Msg.p("v", "" + Math.round(ping)));
                Msg.raw(p, "<green>↓ <gray>Download: <white><v>", Msg.p("v", Msg.speed(dl)));
                Msg.raw(p, "<light_purple>↑ <gray>Upload: <white><v>", Msg.p("v", Msg.speed(ul)));
                Msg.raw(p, "<dark_gray>Home internet · <d>", Msg.p("d", dataLine));
            }
        }, 1L, 1L);
        tests.put(id, task);
    }

    private void stopTest(UUID id, BossBar bar, Player p) {
        BukkitTask t = tests.remove(id);
        if (t != null) t.cancel();
        p.hideBossBar(bar);
    }

    /** Draws the cable from the serving node (or tower) to this router in sparks, near the player, for a few seconds. */
    public void showLine(Player p, Router r) {
        if (lines.containsKey(p.getUniqueId())) return;
        Location from;
        Node node = store.node(r.nodeId());
        if (node != null) from = node.location();
        else {
            Tower t = plugin.store().tower(r.towerId());
            from = t == null ? null : t.antenna(0);
        }
        Location to = center(r.key());
        if (from == null || to == null || from.getWorld() != to.getWorld() || p.getWorld() != to.getWorld()) {
            Msg.send(p, "<gray>There's no line to show yet.");
            return;
        }
        final Location a = from.clone(), b = to.clone();
        final int[] runs = {0};
        UUID id = p.getUniqueId();
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!p.isOnline() || ++runs[0] > 20 || p.getWorld() != a.getWorld()) {
                BukkitTask me = lines.remove(id);
                if (me != null) me.cancel();
                return;
            }
            double len = a.distance(b);
            int points = (int) Math.min(300, Math.max(2, len / 1.2));
            for (int i = 0; i <= points; i++) {
                double f = i / (double) points;
                Location l = a.clone().add(b.clone().subtract(a).toVector().multiply(f));
                if (l.distanceSquared(p.getLocation()) <= 48 * 48) p.spawnParticle(Particle.ELECTRIC_SPARK, l, 1, 0, 0, 0, 0);
            }
        }, 0L, 8L);
        lines.put(id, task);
        Msg.send(p, "<gray>The line from <white><n></white> to your router is shown for a few seconds (<d> away).",
                Msg.p("n", node != null ? node.name() : "the tower"), Msg.p("d", Msg.distance(a.distance(b))));
    }

    public void shutdown() {
        for (BukkitTask t : tests.values()) t.cancel();
        tests.clear();
        for (BukkitTask t : lines.values()) t.cancel();
        lines.clear();
    }
}
