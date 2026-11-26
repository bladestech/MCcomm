package dev.mccomm.cell.broadband;

import dev.mccomm.cell.model.BlockKey;
import dev.mccomm.cell.model.LedgerEntry;
import dev.mccomm.cell.util.Style;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/** In-memory registry of ISPs, nodes, routers, computers, mail and listings, saved to {@code broadband.yml}. */
public final class BroadbandStore {
    private final JavaPlugin plugin;
    private final Map<String, Isp> isps = new LinkedHashMap<>();
    private final Map<Integer, Node> nodes = new LinkedHashMap<>();
    private final Map<Integer, Router> routers = new LinkedHashMap<>();
    private final Map<Integer, Computer> computers = new LinkedHashMap<>();
    private final Map<Integer, Extender> extenders = new LinkedHashMap<>();
    private final Map<BlockKey, Device> byBlock = new HashMap<>();
    private final Map<UUID, List<Mail>> mail = new HashMap<>();
    private final List<Listing> listings = new ArrayList<>();
    private int nextNode = 1, nextRouter = 1, nextComputer = 1, nextExtender = 1, nextListing = 1;
    /** Each player's chosen Wi-Fi: a router id, or 0 = deliberately disconnected. No entry = automatic. */
    private final Map<UUID, Integer> wifiChoice = new HashMap<>();
    private long marketAt; // last market step (epoch ms)
    private boolean dirty;

    public BroadbandStore(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void markDirty() { dirty = true; }

    public void saveIfDirty() {
        if (dirty) save();
    }

    public long marketAt() { return marketAt; }
    public void marketAt(long t) { this.marketAt = t; dirty = true; }

    // ------------------------------------------------------------------- ISPs

    public Collection<Isp> isps() { return isps.values(); }

    public Isp isp(String nameOrId) {
        return nameOrId == null ? null : isps.get(Isp.idOf(nameOrId));
    }

    public void addIsp(Isp isp) {
        isps.put(isp.id(), isp);
        markDirty();
    }

    /** Removes the ISP; the caller removes its nodes and clears its customers first. */
    public void removeIsp(Isp isp) {
        isps.remove(isp.id());
        for (Isp other : isps.values()) {
            other.peers().remove(isp.id());
            other.peerOffers().remove(isp.id());
        }
        markDirty();
    }

    public List<Isp> ispsOwnedBy(UUID player) {
        List<Isp> out = new ArrayList<>();
        for (Isp i : isps.values()) if (i.owner().equals(player)) out.add(i);
        return out;
    }

    public List<Isp> ispsStaffedBy(UUID player) {
        List<Isp> out = new ArrayList<>();
        for (Isp i : isps.values()) if (i.isStaff(player)) out.add(i);
        return out;
    }

    /** The ISP a carrier runs its home plans through, or null. */
    public Isp ispOfCarrier(String carrierId) {
        for (Isp i : isps.values()) if (carrierId.equals(i.carrierId())) return i;
        return null;
    }

    // ------------------------------------------------------------------ nodes

    public Collection<Node> nodes() { return nodes.values(); }

    public Node node(int id) { return nodes.get(id); }

    public int allocateNodeId() { return nextNode++; }

    public void addNode(Node n) {
        nodes.put(n.id(), n);
        byBlock.put(n.key(), n);
        markDirty();
    }

    public void removeNode(Node n) {
        nodes.remove(n.id());
        byBlock.remove(n.key());
        markDirty();
    }

    public List<Node> nodesOf(String ispId) {
        List<Node> out = new ArrayList<>();
        for (Node n : nodes.values()) if (n.ispId().equals(ispId)) out.add(n);
        return out;
    }

    public Node nodeByName(String ispId, String name) {
        for (Node n : nodes.values()) if (n.ispId().equals(ispId) && n.name().equalsIgnoreCase(name)) return n;
        return null;
    }

    // ---------------------------------------------------------------- routers

    public Collection<Router> routers() { return routers.values(); }

    public Router router(int id) { return routers.get(id); }

    public int allocateRouterId() { return nextRouter++; }

    public void addRouter(Router r) {
        routers.put(r.id(), r);
        byBlock.put(r.key(), r);
        markDirty();
    }

    public void removeRouter(Router r) {
        routers.remove(r.id());
        byBlock.remove(r.key());
        for (Computer c : computers.values()) if (c.routerId() == r.id()) c.routerId(0);
        for (Extender x : extenders.values()) if (x.routerId() == r.id()) x.routerId(0);
        markDirty();
    }

    public List<Router> routersOf(UUID player) {
        List<Router> out = new ArrayList<>();
        for (Router r : routers.values()) if (r.owner().equals(player)) out.add(r);
        return out;
    }

    public List<Router> routersOfIsp(String ispId) {
        List<Router> out = new ArrayList<>();
        for (Router r : routers.values()) if (ispId.equals(r.ispId())) out.add(r);
        return out;
    }

    // -------------------------------------------------------------- computers

    public Collection<Computer> computers() { return computers.values(); }

    public Computer computer(int id) { return computers.get(id); }

    public int allocateComputerId() { return nextComputer++; }

    public void addComputer(Computer c) {
        computers.put(c.id(), c);
        byBlock.put(c.key(), c);
        markDirty();
    }

    public void removeComputer(Computer c) {
        computers.remove(c.id());
        byBlock.remove(c.key());
        markDirty();
    }

    public List<Computer> computersOf(UUID player) {
        List<Computer> out = new ArrayList<>();
        for (Computer c : computers.values()) if (c.owner().equals(player)) out.add(c);
        return out;
    }

    // ------------------------------------------------------------- extenders

    public Collection<Extender> extenders() { return extenders.values(); }

    public Extender extender(int id) { return extenders.get(id); }

    public int allocateExtenderId() { return nextExtender++; }

    public void addExtender(Extender x) {
        extenders.put(x.id(), x);
        byBlock.put(x.key(), x);
        markDirty();
    }

    public void removeExtender(Extender x) {
        extenders.remove(x.id());
        byBlock.remove(x.key());
        markDirty();
    }

    public List<Extender> extendersOf(UUID player) {
        List<Extender> out = new ArrayList<>();
        for (Extender x : extenders.values()) if (x.owner().equals(player)) out.add(x);
        return out;
    }

    // ---------------------------------------------------------------- devices

    public Device deviceAt(BlockKey key) { return byBlock.get(key); }

    public Map<UUID, Integer> wifiChoice() { return wifiChoice; }

    // ------------------------------------------------------------ mail, listings

    /** A player's inbox, newest first. */
    public List<Mail> inbox(UUID player) {
        return mail.computeIfAbsent(player, k -> new ArrayList<>());
    }

    public List<Listing> listings() { return listings; }

    public int allocateListingId() { return nextListing++; }

    public Listing listing(int id) {
        for (Listing l : listings) if (l.id() == id) return l;
        return null;
    }

    // ------------------------------------------------------------ persistence

    public void load() {
        File dir = plugin.getDataFolder();
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new File(dir, "broadband.yml"));
        nextNode = Math.max(1, y.getInt("next-node-id", 1));
        nextRouter = Math.max(1, y.getInt("next-router-id", 1));
        nextComputer = Math.max(1, y.getInt("next-computer-id", 1));
        nextExtender = Math.max(1, y.getInt("next-extender-id", 1));
        nextListing = Math.max(1, y.getInt("next-listing-id", 1));
        marketAt = y.getLong("market-at", 0);

        ConfigurationSection is = y.getConfigurationSection("isps");
        if (is != null) {
            for (String id : is.getKeys(false)) {
                ConfigurationSection s = is.getConfigurationSection(id);
                if (s == null) continue;
                try {
                    Isp isp = new Isp(s.getString("name", id), UUID.fromString(s.getString("owner")), s.getLong("created-at"));
                    for (String e : s.getStringList("employees")) isp.employees().add(UUID.fromString(e));
                    isp.colors(Style.fromHex(s.getStringList("colors")));
                    isp.balance(s.getLong("balance", 0));
                    isp.group(s.getString("group"));
                    isp.nextUpkeep(s.getLong("next-upkeep", 0));
                    isp.unpaidSince(s.getLong("unpaid-since", 0));
                    isp.carrierId(s.getString("carrier"));
                    isp.transitTier(s.getInt("transit-tier", 1));
                    isp.reliability(s.getDouble("reliability", 1));
                    isp.peers().addAll(s.getStringList("peers"));
                    isp.peerOffers().addAll(s.getStringList("peer-offers"));
                    ConfigurationSection bs = s.getConfigurationSection("bundles");
                    if (bs != null) {
                        for (String cid : bs.getKeys(false)) {
                            String carrier = cid.replace("~", ":");
                            isp.bundles().put(carrier, new Bundle(carrier, bs.getInt(cid + ".isp-discount"),
                                    bs.getInt(cid + ".carrier-discount"), bs.getBoolean(cid + ".accepted")));
                        }
                    }
                    ConfigurationSection ps = s.getConfigurationSection("plans");
                    if (ps != null) {
                        for (String pid : ps.getKeys(false)) {
                            InternetPlan plan = readPlan(ps.getConfigurationSection(pid), pid);
                            if (plan != null) {
                                plan.sale().read(ps.getConfigurationSection(pid));
                                isp.plans().put(pid, plan);
                            }
                        }
                    }
                    for (Map<?, ?> m : s.getMapList("ledger")) {
                        Object t = m.get("time"), a = m.get("amount");
                        isp.ledger().add(new LedgerEntry(t instanceof Number n ? n.longValue() : 0,
                                a instanceof Number n2 ? n2.longValue() : 0, String.valueOf(m.get("note"))));
                    }
                    isps.put(isp.id(), isp);
                } catch (Exception ex) {
                    plugin.getLogger().log(Level.WARNING, "Skipping broken ISP entry " + id, ex);
                }
            }
        }

        ConfigurationSection ns = y.getConfigurationSection("nodes");
        if (ns != null) {
            for (String key : ns.getKeys(false)) {
                ConfigurationSection s = ns.getConfigurationSection(key);
                if (s == null) continue;
                try {
                    int id = Integer.parseInt(key);
                    Technology t = Technology.fromKey(s.getString("tech"));
                    String ispId = s.getString("isp");
                    if (t == null || !isps.containsKey(ispId)) {
                        plugin.getLogger().warning("Node #" + id + " has no valid ISP or technology, skipping.");
                        continue;
                    }
                    String by = s.getString("created-by");
                    Node n = new Node(id, ispId, t, s.getString("name", "N" + id), blockKey(s),
                            by == null ? null : UUID.fromString(by), s.getLong("created-at"));
                    n.tier(s.getInt("tier", 1));
                    n.faultAt(s.getLong("fault-at", 0));
                    nodes.put(id, n);
                    byBlock.put(n.key(), n);
                    nextNode = Math.max(nextNode, id + 1);
                } catch (Exception ex) {
                    plugin.getLogger().log(Level.WARNING, "Skipping broken node entry " + key, ex);
                }
            }
        }

        ConfigurationSection rs = y.getConfigurationSection("routers");
        if (rs != null) {
            for (String key : rs.getKeys(false)) {
                ConfigurationSection s = rs.getConfigurationSection(key);
                if (s == null) continue;
                try {
                    int id = Integer.parseInt(key);
                    Router r = new Router(id, UUID.fromString(s.getString("owner")), s.getString("name", "Home"), blockKey(s), s.getLong("created-at"));
                    try {
                        r.state(Router.State.valueOf(s.getString("state", "NONE")));
                    } catch (IllegalArgumentException ignored) {
                    }
                    r.ispId(s.getString("isp"));
                    r.planId(s.getString("plan"));
                    r.tech(Technology.fromKey(s.getString("tech")));
                    r.nodeId(s.getInt("node", 0));
                    r.towerId(s.getInt("tower", 0));
                    r.installAt(s.getLong("install-at", 0));
                    r.nextBill(s.getLong("next-bill", 0));
                    r.suspendedSince(s.getLong("suspended-since", 0));
                    r.warned(s.getBoolean("warned", false));
                    r.open(s.getBoolean("open", false));
                    r.business(s.getBoolean("business", false));
                    r.tier(s.getInt("wifi-tier", 1));
                    r.dataLeftMB(s.getDouble("data-left", 0));
                    r.terms(readPlan(s.getConfigurationSection("terms"), s.getString("plan", "plan")));
                    if (r.ispId() != null && !isps.containsKey(r.ispId())) r.clearService();
                    routers.put(id, r);
                    byBlock.put(r.key(), r);
                    nextRouter = Math.max(nextRouter, id + 1);
                } catch (Exception ex) {
                    plugin.getLogger().log(Level.WARNING, "Skipping broken router entry " + key, ex);
                }
            }
        }

        ConfigurationSection cs = y.getConfigurationSection("computers");
        if (cs != null) {
            for (String key : cs.getKeys(false)) {
                ConfigurationSection s = cs.getConfigurationSection(key);
                if (s == null) continue;
                try {
                    int id = Integer.parseInt(key);
                    Computer c = new Computer(id, UUID.fromString(s.getString("owner")), s.getString("name", "Computer"), blockKey(s), s.getLong("created-at"));
                    c.routerId(s.getInt("router", 0));
                    computers.put(id, c);
                    byBlock.put(c.key(), c);
                    nextComputer = Math.max(nextComputer, id + 1);
                } catch (Exception ex) {
                    plugin.getLogger().log(Level.WARNING, "Skipping broken computer entry " + key, ex);
                }
            }
        }

        ConfigurationSection xs = y.getConfigurationSection("extenders");
        if (xs != null) {
            for (String key : xs.getKeys(false)) {
                ConfigurationSection s = xs.getConfigurationSection(key);
                if (s == null) continue;
                try {
                    int id = Integer.parseInt(key);
                    Extender x = new Extender(id, UUID.fromString(s.getString("owner")), s.getString("name", "Extender"), blockKey(s), s.getLong("created-at"));
                    x.routerId(s.getInt("router", 0));
                    extenders.put(id, x);
                    byBlock.put(x.key(), x);
                    nextExtender = Math.max(nextExtender, id + 1);
                } catch (Exception ex) {
                    plugin.getLogger().log(Level.WARNING, "Skipping broken extender entry " + key, ex);
                }
            }
        }

        ConfigurationSection ws = y.getConfigurationSection("wifi-choice");
        if (ws != null) {
            for (String key : ws.getKeys(false)) {
                try {
                    wifiChoice.put(UUID.fromString(key), Math.max(0, ws.getInt(key)));
                } catch (IllegalArgumentException ignored) {
                    // a bad UUID: skip it
                }
            }
        }

        ConfigurationSection ms = y.getConfigurationSection("mail");
        if (ms != null) {
            for (String key : ms.getKeys(false)) {
                try {
                    UUID to = UUID.fromString(key);
                    List<Mail> box = new ArrayList<>();
                    for (Map<?, ?> m : ms.getMapList(key)) {
                        Object t = m.get("time"), rd = m.get("read");
                        box.add(new Mail(UUID.fromString(String.valueOf(m.get("from"))), String.valueOf(m.get("text")),
                                t instanceof Number n ? n.longValue() : 0, rd instanceof Boolean b && b));
                    }
                    mail.put(to, box);
                } catch (Exception ex) {
                    plugin.getLogger().log(Level.WARNING, "Skipping broken mailbox " + key, ex);
                }
            }
        }
        for (Map<?, ?> m : y.getMapList("listings")) {
            try {
                Object id = m.get("id"), price = m.get("price"), time = m.get("time");
                listings.add(new Listing(id instanceof Number n ? n.intValue() : nextListing++, UUID.fromString(String.valueOf(m.get("seller"))),
                        String.valueOf(m.get("title")), price instanceof Number p ? p.longValue() : 0, time instanceof Number t ? t.longValue() : 0));
            } catch (Exception ex) {
                plugin.getLogger().log(Level.WARNING, "Skipping broken listing", ex);
            }
        }
        for (Listing l : listings) nextListing = Math.max(nextListing, l.id() + 1);
        dirty = false;
    }

    private static InternetPlan readPlan(ConfigurationSection p, String pid) {
        Technology t = p == null ? null : Technology.fromKey(p.getString("tech"));
        if (t == null) return null;
        InternetPlan plan = new InternetPlan(pid, p.getString("name", pid), t, p.getLong("price", 0),
                Math.max(1, p.getInt("period-hours", 24)), p.getDouble("speed-cap", 10), p.getLong("install-fee", 0));
        plan.dataMB(p.getLong("data-mb", -1));
        Segment seg = Segment.fromKey(p.getString("segment"));
        if (seg != null) plan.segment(seg);
        return plan;
    }

    private static void writePlan(YamlConfiguration y, String q, InternetPlan pl) {
        y.set(q + "name", pl.name());
        y.set(q + "tech", pl.tech().key());
        y.set(q + "price", pl.price());
        y.set(q + "period-hours", pl.periodHours());
        y.set(q + "speed-cap", pl.speedCapMbps());
        y.set(q + "install-fee", pl.installFee());
        y.set(q + "data-mb", pl.dataMB());
        if (pl.business()) y.set(q + "segment", pl.segment().key());
    }

    private static BlockKey blockKey(ConfigurationSection s) {
        return new BlockKey(UUID.fromString(s.getString("world")), s.getInt("x"), s.getInt("y"), s.getInt("z"));
    }

    private static void putKey(YamlConfiguration y, String p, BlockKey k) {
        y.set(p + "world", k.world().toString());
        y.set(p + "x", k.x());
        y.set(p + "y", k.y());
        y.set(p + "z", k.z());
    }

    public void save() {
        File dir = plugin.getDataFolder();
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        YamlConfiguration y = new YamlConfiguration();
        y.set("next-node-id", nextNode);
        y.set("next-router-id", nextRouter);
        y.set("next-computer-id", nextComputer);
        y.set("next-extender-id", nextExtender);
        y.set("next-listing-id", nextListing);
        y.set("market-at", marketAt);

        for (Isp isp : isps.values()) {
            String p = "isps." + isp.id() + ".";
            y.set(p + "name", isp.name());
            y.set(p + "owner", isp.owner().toString());
            y.set(p + "employees", isp.employees().stream().map(UUID::toString).toList());
            y.set(p + "colors", Style.toHex(isp.colors()));
            y.set(p + "balance", isp.balance());
            y.set(p + "group", isp.group());
            y.set(p + "next-upkeep", isp.nextUpkeep());
            y.set(p + "unpaid-since", isp.unpaidSince());
            y.set(p + "created-at", isp.createdAt());
            y.set(p + "carrier", isp.carrierId());
            y.set(p + "transit-tier", isp.transitTier());
            y.set(p + "reliability", Math.round(isp.reliability() * 10000) / 10000.0);
            if (!isp.peers().isEmpty()) y.set(p + "peers", new ArrayList<>(isp.peers()));
            if (!isp.peerOffers().isEmpty()) y.set(p + "peer-offers", new ArrayList<>(isp.peerOffers()));
            for (Bundle b : isp.bundles().values()) {
                String q = p + "bundles." + b.carrierId().replace(":", "~") + ".";
                y.set(q + "isp-discount", b.ispDiscount());
                y.set(q + "carrier-discount", b.carrierDiscount());
                y.set(q + "accepted", b.accepted());
            }
            for (InternetPlan pl : isp.plans().values()) {
                writePlan(y, p + "plans." + pl.id() + ".", pl);
                pl.sale().write(y, p + "plans." + pl.id() + ".");
            }
            if (!isp.ledger().isEmpty()) {
                List<Map<String, Object>> out = new ArrayList<>();
                for (LedgerEntry e : isp.ledger()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("time", e.time());
                    m.put("amount", e.amount());
                    m.put("note", e.note());
                    out.add(m);
                }
                y.set(p + "ledger", out);
            }
        }
        for (Node n : nodes.values()) {
            String p = "nodes." + n.id() + ".";
            y.set(p + "isp", n.ispId());
            y.set(p + "tech", n.tech().key());
            y.set(p + "name", n.name());
            putKey(y, p, n.key());
            y.set(p + "tier", n.tier());
            if (n.faulted()) y.set(p + "fault-at", n.faultAt());
            y.set(p + "created-by", n.createdBy() == null ? null : n.createdBy().toString());
            y.set(p + "created-at", n.createdAt());
        }
        for (Router r : routers.values()) {
            String p = "routers." + r.id() + ".";
            y.set(p + "owner", r.owner().toString());
            y.set(p + "name", r.name());
            putKey(y, p, r.key());
            y.set(p + "state", r.state().name());
            y.set(p + "isp", r.ispId());
            y.set(p + "plan", r.planId());
            y.set(p + "tech", r.tech() == null ? null : r.tech().key());
            y.set(p + "node", r.nodeId());
            y.set(p + "tower", r.towerId());
            y.set(p + "install-at", r.installAt());
            y.set(p + "next-bill", r.nextBill());
            y.set(p + "suspended-since", r.suspendedSince());
            y.set(p + "warned", r.warned());
            y.set(p + "open", r.open());
            if (r.business()) y.set(p + "business", true);
            y.set(p + "wifi-tier", r.tier());
            y.set(p + "data-left", Math.round(r.dataLeftMB() * 100) / 100.0);
            if (r.terms() != null) writePlan(y, p + "terms.", r.terms());
            y.set(p + "created-at", r.createdAt());
        }
        for (Computer c : computers.values()) {
            String p = "computers." + c.id() + ".";
            y.set(p + "owner", c.owner().toString());
            y.set(p + "name", c.name());
            putKey(y, p, c.key());
            y.set(p + "router", c.routerId());
            y.set(p + "created-at", c.createdAt());
        }
        for (Extender x : extenders.values()) {
            String p = "extenders." + x.id() + ".";
            y.set(p + "owner", x.owner().toString());
            y.set(p + "name", x.name());
            putKey(y, p, x.key());
            y.set(p + "router", x.routerId());
            y.set(p + "created-at", x.createdAt());
        }
        for (Map.Entry<UUID, Integer> e : wifiChoice.entrySet()) y.set("wifi-choice." + e.getKey(), e.getValue());
        for (Map.Entry<UUID, List<Mail>> e : mail.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            List<Map<String, Object>> out = new ArrayList<>();
            for (Mail m : e.getValue()) {
                Map<String, Object> mm = new LinkedHashMap<>();
                mm.put("from", m.from().toString());
                mm.put("text", m.text());
                mm.put("time", m.time());
                mm.put("read", m.read());
                out.add(mm);
            }
            y.set("mail." + e.getKey(), out);
        }
        if (!listings.isEmpty()) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Listing l : listings) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", l.id());
                m.put("seller", l.seller().toString());
                m.put("title", l.title());
                m.put("price", l.price());
                m.put("time", l.time());
                out.add(m);
            }
            y.set("listings", out);
        }
        dev.mccomm.cell.util.YamlFiles.save(y, new File(dir, "broadband.yml"), plugin.getLogger());
        dirty = false;
    }

    /** Lowercase helper kept here so the service and commands share one slug rule for plan ids. */
    public static String slug(String name) {
        String s = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return s.isEmpty() ? "plan" : s;
    }
}
