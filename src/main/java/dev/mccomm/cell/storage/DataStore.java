package dev.mccomm.cell.storage;

import dev.mccomm.cell.band.Band;
import dev.mccomm.cell.model.BlockKey;
import dev.mccomm.cell.model.Carrier;
import dev.mccomm.cell.model.PlayerProfile;
import dev.mccomm.cell.model.Tower;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Material;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/** In-memory registry of carriers, towers and player profiles, persisted to YAML files. */
public final class DataStore {
    private final JavaPlugin plugin;
    private final Map<String, Carrier> carriers = new LinkedHashMap<>();
    private final Map<Integer, Tower> towers = new LinkedHashMap<>();
    private final Map<BlockKey, Tower> towersByBlock = new HashMap<>();
    private final Map<UUID, PlayerProfile> profiles = new ConcurrentHashMap<>();
    private final Map<String, dev.mccomm.cell.model.Manufacturer> manufacturers = new LinkedHashMap<>();
    private final Map<String, NumberOwner> numberIndex = new ConcurrentHashMap<>();
    private int nextTowerId = 1;
    private boolean simulatedRevenue; // simulated users pay their plans (server-wide, toggled in the simulation menu)
    private boolean dirty;

    public DataStore(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    // ---------------------------------------------------------------- carriers

    public Collection<Carrier> carriers() { return carriers.values(); }

    public Carrier carrier(String nameOrId) {
        return nameOrId == null ? null : carriers.get(Carrier.idOf(nameOrId));
    }

    public void addCarrier(Carrier c) {
        if (c.prefix() == null) c.prefix(freePrefix());
        carriers.put(c.id(), c);
        markDirty();
    }

    public void removeCarrier(Carrier c) {
        carriers.remove(c.id());
        if (plugin instanceof dev.mccomm.cell.CellPlugin cp && cp.workforce() != null) cp.workforce().companyRemoved(c.id());
        for (PlayerProfile p : profiles.values()) unsubscribe(p, c.id());
        markDirty();
    }

    private String freePrefix() {
        java.util.Set<String> used = new java.util.HashSet<>();
        for (Carrier c : carriers.values()) if (c.prefix() != null) used.add(c.prefix());
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        for (int tries = 0; tries < 2000; tries++) {
            String p = String.valueOf(r.nextInt(200, 1000));
            if (!used.contains(p)) return p;
        }
        throw new IllegalStateException("No free carrier prefixes left");
    }

    // ----------------------------------------------------------------- numbers

    /** Who owns a phone number, and on which carrier. */
    public record NumberOwner(UUID player, String carrierId) {
    }

    public NumberOwner numberOwner(String digits) {
        return digits == null ? null : numberIndex.get(digits);
    }

    /** Gives the player a number on this carrier (keeps the existing one if they have it). */
    public String assignNumber(PlayerProfile p, Carrier c) {
        String existing = p.number(c.id());
        if (existing != null) return existing;
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        for (int tries = 0; tries < 20000; tries++) {
            String n = c.prefix() + String.format("%04d", r.nextInt(10000));
            if (!numberIndex.containsKey(n)) {
                p.numbers().put(c.id(), n);
                numberIndex.put(n, new NumberOwner(p.player(), c.id()));
                markDirty();
                return n;
            }
        }
        throw new IllegalStateException("Carrier " + c.name() + " has no free numbers left");
    }

    /** Removes the subscription and releases its number. */
    public void unsubscribe(PlayerProfile p, String carrierId) {
        String n = p.number(carrierId);
        if (n != null) numberIndex.remove(n);
        p.remove(carrierId);
        markDirty();
    }

    public List<Carrier> carriersOwnedBy(UUID player) {
        List<Carrier> out = new ArrayList<>();
        for (Carrier c : carriers.values()) if (c.owner().equals(player)) out.add(c);
        return out;
    }

    // ------------------------------------------------------------------ towers

    public Collection<Tower> towers() { return towers.values(); }

    public Tower tower(int id) { return towers.get(id); }

    public Tower towerAt(BlockKey key) { return towersByBlock.get(key); }

    public int allocateTowerId() { return nextTowerId++; }

    public void addTower(Tower t) {
        towers.put(t.id(), t);
        towersByBlock.put(t.key(), t);
        markDirty();
    }

    public void removeTower(Tower t) {
        towers.remove(t.id());
        if (plugin instanceof dev.mccomm.cell.CellPlugin cp && cp.workforce() != null) cp.workforce().towerRemoved(t);
        towersByBlock.remove(t.key());
        markDirty();
    }

    /** Tower by its name (case-insensitive), or null. */
    public Tower towerByName(String name) {
        if (name == null) return null;
        for (Tower t : towers.values()) if (t.name().equalsIgnoreCase(name)) return t;
        return null;
    }

    public List<Tower> towersOf(String carrierId) {
        List<Tower> out = new ArrayList<>();
        for (Tower t : towers.values()) if (t.carrierId().equals(carrierId)) out.add(t);
        return out;
    }

    // ---------------------------------------------------------------- profiles

    public Collection<PlayerProfile> profiles() { return profiles.values(); }

    public boolean simulatedRevenue() { return simulatedRevenue; }

    public void simulatedRevenue(boolean v) {
        simulatedRevenue = v;
        markDirty();
    }

    // ------------------------------------------------------------ manufacturers

    public Collection<dev.mccomm.cell.model.Manufacturer> manufacturers() { return manufacturers.values(); }

    public dev.mccomm.cell.model.Manufacturer manufacturer(String nameOrId) {
        return nameOrId == null ? null : manufacturers.get(dev.mccomm.cell.model.Manufacturer.idOf(nameOrId));
    }

    public void addManufacturer(dev.mccomm.cell.model.Manufacturer m) {
        manufacturers.put(m.id(), m);
        markDirty();
    }

    public void removeManufacturer(dev.mccomm.cell.model.Manufacturer m) {
        manufacturers.remove(m.id());
        markDirty();
    }

    public PlayerProfile profile(UUID player) {
        return profiles.computeIfAbsent(player, PlayerProfile::new);
    }

    // ---------------------------------------------------------------- persistence

    public void markDirty() { dirty = true; }

    public void saveIfDirty() {
        if (dirty) save();
    }

    public void load() {
        File dir = plugin.getDataFolder();
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();

        YamlConfiguration cy = YamlConfiguration.loadConfiguration(new File(dir, "carriers.yml"));
        ConfigurationSection cs = cy.getConfigurationSection("carriers");
        if (cs != null) {
            for (String id : cs.getKeys(false)) {
                ConfigurationSection s = cs.getConfigurationSection(id);
                if (s == null) continue;
                try {
                    TextColor color = TextColor.fromHexString(s.getString("color", "#55FFFF"));
                    Carrier c = new Carrier(s.getString("name", id), UUID.fromString(s.getString("owner")),
                            color == null ? NamedTextColor.AQUA : color, s.getLong("created-at"));
                    for (String e : s.getStringList("employees")) c.employees().add(UUID.fromString(e));
                    c.prefix(s.getString("prefix"));
                    List<TextColor> cols = dev.mccomm.cell.util.Style.fromHex(s.getStringList("colors"));
                    if (!cols.isEmpty()) c.colors(cols);
                    c.balance(s.getLong("balance", 0));
                    c.nextUpkeep(s.getLong("next-upkeep", 0));
                    c.unpaidSince(s.getLong("unpaid-since", 0));
                    c.tradeIn(s.getBoolean("trade-in", false));
                    ConfigurationSection st0 = s.getConfigurationSection("stock");
                    if (st0 != null) for (String k : st0.getKeys(false)) c.stock().put(k.replace("~", ":"), st0.getInt(k));
                    c.mvno(s.getBoolean("mvno", false));
                    c.simulatedUsers(s.getInt("simulated-users", 0));
                    c.mvnoOwed(s.getLong("mvno-owed", 0));
                    readAgreements(s.getConfigurationSection("agreements"), c.agreements());
                    readAgreements(s.getConfigurationSection("offers"), c.offers());
                    c.tradeInPercent(s.getDouble("trade-in-percent", 60));
                    ConfigurationSection pls = s.getConfigurationSection("plans");
                    if (pls != null) {
                        for (String pid : pls.getKeys(false)) {
                            ConfigurationSection ps0 = pls.getConfigurationSection(pid);
                            if (ps0 == null) continue;
                            dev.mccomm.cell.model.Plan pl = readPlan(ps0, pid);
                            c.plans().put(pid, pl);
                        }
                    }
                    readLedger(s, c.ledger());
                    carriers.put(c.id(), c);
                } catch (Exception ex) {
                    plugin.getLogger().log(Level.WARNING, "Skipping broken carrier entry " + id, ex);
                }
            }
        }

        YamlConfiguration sy = YamlConfiguration.loadConfiguration(new File(dir, "server.yml"));
        simulatedRevenue = sy.getBoolean("simulated-revenue", false);

        YamlConfiguration my = YamlConfiguration.loadConfiguration(new File(dir, "manufacturers.yml"));
        ConfigurationSection ms = my.getConfigurationSection("manufacturers");
        if (ms != null) {
            for (String id : ms.getKeys(false)) {
                ConfigurationSection s = ms.getConfigurationSection(id);
                if (s == null) continue;
                try {
                    String own = s.getString("owner");
                    dev.mccomm.cell.model.Manufacturer m = new dev.mccomm.cell.model.Manufacturer(s.getString("name", id),
                            own == null ? null : UUID.fromString(own), s.getBoolean("system", false));
                    for (String e : s.getStringList("employees")) m.employees().add(UUID.fromString(e));
                    List<TextColor> cols = dev.mccomm.cell.util.Style.fromHex(s.getStringList("colors"));
                    if (!cols.isEmpty()) m.colors(cols);
                    m.balance(s.getLong("balance", 0));
                    m.researchPoints(s.getLong("research-points", 0));
                    m.researchedGen(s.getInt("researched-gen", 1));
                    for (String bk : s.getStringList("researched-bands")) {
                        Band rb = Band.fromKey(bk);
                        if (rb != null) m.researchedBands().add(rb);
                    }
                    ConfigurationSection mods = s.getConfigurationSection("models");
                    if (mods != null) {
                        for (String mid : mods.getKeys(false)) {
                            ConfigurationSection md = mods.getConfigurationSection(mid);
                            if (md == null) continue;
                            dev.mccomm.cell.model.TowerModel tm = new dev.mccomm.cell.model.TowerModel(mid, md.getString("name", mid));
                            tm.generation(md.getInt("generation", 1));
                            for (String bk : md.getStringList("bands")) {
                                Band mb = Band.fromKey(bk);
                                if (mb != null) tm.bands().add(mb);
                            }
                            tm.marginPercent(md.getDouble("margin-percent", 20));
                            tm.published(md.getBoolean("published", false));
                            tm.sold(md.getInt("sold", 0));
                            m.models().put(mid, tm);
                        }
                    }
                    readLedger(s, m.ledger());
                    manufacturers.put(m.id(), m);
                } catch (Exception ex) {
                    plugin.getLogger().log(Level.WARNING, "Skipping broken manufacturer entry " + id, ex);
                }
            }
        }

        YamlConfiguration ty = YamlConfiguration.loadConfiguration(new File(dir, "towers.yml"));
        nextTowerId = Math.max(1, ty.getInt("next-id", 1));
        ConfigurationSection ts = ty.getConfigurationSection("towers");
        if (ts != null) {
            for (String key : ts.getKeys(false)) {
                ConfigurationSection s = ts.getConfigurationSection(key);
                if (s == null) continue;
                try {
                    int id = Integer.parseInt(key);
                    String createdBy = s.getString("created-by");
                    Tower t = new Tower(id, s.getString("carrier"), UUID.fromString(s.getString("world")),
                            s.getInt("x"), s.getInt("y"), s.getInt("z"), s.getInt("generation", 1),
                            createdBy == null ? null : UUID.fromString(createdBy), s.getLong("created-at"));
                    for (String b : s.getStringList("bands")) {
                        Band band = Band.fromKey(b);
                        if (band != null) t.enabledBands().add(band);
                    }
                    t.name(s.getString("name"));
                    ConfigurationSection sc = s.getConfigurationSection("screen");
                    if (sc != null) {
                        for (String role : sc.getKeys(false)) {
                            try {
                                t.screen().put(role, UUID.fromString(sc.getString(role)));
                            } catch (Exception ignored) {
                            }
                        }
                    }
                    t.heightAboveGround(s.getDouble("height", 0));
                    t.wear(s.getDouble("wear", 0));
                    t.wearAt(s.getLong("wear-at", 0));
                    t.manufacturerId(s.getString("manufacturer"));
                    t.modelName(s.getString("model"));
                    for (String bk : s.getStringList("model-bands")) {
                        Band mb = Band.fromKey(bk);
                        if (mb != null) t.modelBands().add(mb);
                    }
                    String bh = s.getString("backhaul");
                    if (bh != null) t.backhaul(Material.matchMaterial(bh));
                    String d = s.getString("display");
                    if (d != null) t.displayId(UUID.fromString(d));
                    String i = s.getString("panel");
                    if (i != null) t.panelId(UUID.fromString(i));
                    if (!carriers.containsKey(t.carrierId())) {
                        plugin.getLogger().warning("Tower #" + id + " belongs to missing carrier " + t.carrierId() + ", skipping.");
                        continue;
                    }
                    towers.put(id, t);
                    towersByBlock.put(t.key(), t);
                    nextTowerId = Math.max(nextTowerId, id + 1);
                } catch (Exception ex) {
                    plugin.getLogger().log(Level.WARNING, "Skipping broken tower entry " + key, ex);
                }
            }
        }

        YamlConfiguration py = YamlConfiguration.loadConfiguration(new File(dir, "players.yml"));
        ConfigurationSection ps = py.getConfigurationSection("players");
        if (ps != null) {
            for (String key : ps.getKeys(false)) {
                ConfigurationSection s = ps.getConfigurationSection(key);
                if (s == null) continue;
                try {
                    PlayerProfile p = new PlayerProfile(UUID.fromString(key));
                    for (String c : s.getStringList("active")) if (carriers.containsKey(c)) p.active().add(c);
                    for (String c : s.getStringList("inactive")) if (carriers.containsKey(c)) p.inactive().add(c);
                    if (s.contains("hud")) p.hud(s.getBoolean("hud"));
                    p.dnd(s.getBoolean("dnd", false));
                    p.phone(s.getString("phone"));
                    p.ownedPhones().addAll(s.getStringList("owned-phones"));
                    p.balance(s.getLong("balance", 0));
                    ConfigurationSection od = s.getConfigurationSection("overdue");
                    if (od != null) for (String cid : od.getKeys(false)) p.overdue().put(cid, od.getLong(cid));
                    ConfigurationSection sb = s.getConfigurationSection("subs");
                    if (sb != null) {
                        for (String cid : sb.getKeys(false)) {
                            if (!p.has(cid)) continue;
                            dev.mccomm.cell.model.Subscription su = new dev.mccomm.cell.model.Subscription(
                                    sb.getString(cid + ".plan"), sb.getLong(cid + ".next-bill"));
                            su.warned(sb.getBoolean(cid + ".warned"));
                            su.dataLeftMB(sb.getDouble(cid + ".data-left", 0));
                            su.financePhone(sb.getString(cid + ".finance-phone"));
                            su.financeRemaining(sb.getLong(cid + ".finance-remaining", 0));
                            su.financeInstallment(sb.getLong(cid + ".finance-installment", 0));
                            ConfigurationSection gf = sb.getConfigurationSection(cid + ".grandfathered");
                            if (gf != null) su.grandfathered(readPlan(gf, su.planId() == null ? "old" : su.planId()));
                            p.subs().put(cid, su);
                        }
                    }
                    p.notices().addAll(s.getStringList("notices"));
                    p.giftedPhones().addAll(s.getStringList("gifted-phones"));
                    p.starterGiven(s.getBoolean("starter-given", false));
                    ConfigurationSection lk = s.getConfigurationSection("phone-locks");
                    if (lk != null) for (String k : lk.getKeys(false)) p.phoneLocks().put(k, lk.getString(k));
                    ConfigurationSection bl = s.getConfigurationSection("blacklist");
                    if (bl != null) for (String k : bl.getKeys(false)) p.blacklist().put(k, bl.getString(k));
                    readLedger(s, p.ledger());
                    String ds = s.getString("data-sim");
                    if (ds != null && p.has(ds)) p.dataSim(ds);
                    ConfigurationSection nums = s.getConfigurationSection("numbers");
                    if (nums != null) {
                        for (String cid : nums.getKeys(false)) {
                            String n = nums.getString(cid);
                            if (n != null && p.has(cid) && !numberIndex.containsKey(n)) {
                                p.numbers().put(cid, n);
                                numberIndex.put(n, new NumberOwner(p.player(), cid));
                            }
                        }
                    }
                    ConfigurationSection con = s.getConfigurationSection("contacts");
                    if (con != null) for (String n : con.getKeys(false)) p.contacts().put(n, con.getString(n, n));
                    for (Map<?, ?> m : s.getMapList("inbox")) {
                        Object read = m.get("read");
                        Object time = m.get("time");
                        p.inbox().add(new dev.mccomm.cell.model.SmsMessage(
                                String.valueOf(m.get("from")), String.valueOf(m.get("from-name")),
                                String.valueOf(m.get("to")), String.valueOf(m.get("text")),
                                time instanceof Number num ? num.longValue() : 0L,
                                read instanceof Boolean b && b));
                    }
                    profiles.put(p.player(), p);
                } catch (Exception ex) {
                    plugin.getLogger().log(Level.WARNING, "Skipping broken player entry " + key, ex);
                }
            }
        }
        dirty = false;
        // Fill in prefixes/numbers for data created before phone numbers existed (marks dirty so they get saved).
        for (Carrier c : carriers.values()) {
            if (c.prefix() == null) {
                c.prefix(freePrefix());
                markDirty();
            }
        }
        for (PlayerProfile p : profiles.values()) {
            for (String cid : new ArrayList<>(p.active())) assignNumber(p, carriers.get(cid));
            for (String cid : new ArrayList<>(p.inactive())) assignNumber(p, carriers.get(cid));
        }
    }

    private static dev.mccomm.cell.model.Plan readPlan(ConfigurationSection ps0, String pid) {
        dev.mccomm.cell.model.Plan pl = new dev.mccomm.cell.model.Plan(pid, ps0.getString("name", pid),
                ps0.getLong("price", 0), ps0.getInt("period-hours", 24));
        pl.maxBand(Band.fromKey(ps0.getString("max-band")));
        pl.speedCapMbps(ps0.getDouble("speed-cap", 0));
        pl.texts(ps0.getBoolean("texts", true));
        pl.calls(ps0.getBoolean("calls", true));
        try {
            pl.type(dev.mccomm.cell.model.Plan.Type.valueOf(ps0.getString("type", "POSTPAID")));
            pl.outOfData(dev.mccomm.cell.model.Plan.OutOfData.valueOf(ps0.getString("out-of-data", "THROTTLE")));
        } catch (IllegalArgumentException ignored) {
        }
        pl.dataMB(ps0.getLong("data-mb", -1));
        pl.overagePerGb(ps0.getLong("overage-per-gb", 10));
        pl.packMB(ps0.getLong("pack-mb", 0));
        pl.packPrice(ps0.getLong("pack-price", 0));
        pl.financePhone(ps0.getString("finance-phone"));
        pl.installments(ps0.getInt("installments", 6));
        pl.interestPercent(ps0.getDouble("interest-percent", 10));
        pl.downPayment(ps0.getLong("down-payment", 0));
        pl.colors(dev.mccomm.cell.util.Style.fromHex(ps0.getStringList("colors")));
        pl.roaming(ps0.getBoolean("roaming", true));
        pl.roamingPerGb(ps0.getLong("roaming-per-gb", 0));
        return pl;
    }

    private static void writePlan(YamlConfiguration y, String pp, dev.mccomm.cell.model.Plan pl) {
        y.set(pp + "name", pl.name());
        y.set(pp + "price", pl.price());
        y.set(pp + "period-hours", pl.periodHours());
        y.set(pp + "max-band", pl.maxBand() == null ? null : pl.maxBand().key());
        y.set(pp + "speed-cap", pl.speedCapMbps());
        y.set(pp + "texts", pl.texts());
        y.set(pp + "calls", pl.calls());
        y.set(pp + "type", pl.type().name());
        y.set(pp + "data-mb", pl.dataMB());
        y.set(pp + "out-of-data", pl.outOfData().name());
        y.set(pp + "overage-per-gb", pl.overagePerGb());
        y.set(pp + "pack-mb", pl.packMB());
        y.set(pp + "pack-price", pl.packPrice());
        y.set(pp + "finance-phone", pl.financePhone());
        y.set(pp + "installments", pl.installments());
        y.set(pp + "interest-percent", pl.interestPercent());
        y.set(pp + "down-payment", pl.downPayment());
        y.set(pp + "colors", dev.mccomm.cell.util.Style.toHex(pl.colors()));
        y.set(pp + "roaming", pl.roaming());
        y.set(pp + "roaming-per-gb", pl.roamingPerGb());
    }

    private static void readAgreements(ConfigurationSection s, Map<String, dev.mccomm.cell.model.Agreement> out) {
        if (s == null) return;
        for (String key : s.getKeys(false)) {
            ConfigurationSection a = s.getConfigurationSection(key);
            if (a == null) continue;
            try {
                dev.mccomm.cell.model.Agreement ag = new dev.mccomm.cell.model.Agreement(
                        dev.mccomm.cell.model.Agreement.Kind.valueOf(a.getString("kind", "ROAMING")), a.getString("partner"));
                ag.perGb(a.getLong("per-gb", 5));
                ag.sharePercent(a.getDouble("share-percent", 30));
                ag.maxBand(Band.fromKey(a.getString("max-band")));
                ag.deprioritize(a.getBoolean("deprioritize", true));
                ag.endsAt(a.getLong("ends-at", 0));
                if (ag.partnerId() != null) out.put(ag.key(), ag);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private static void writeAgreements(YamlConfiguration y, String path, Map<String, dev.mccomm.cell.model.Agreement> in) {
        int i = 0;
        for (dev.mccomm.cell.model.Agreement a : in.values()) {
            String p = path + "." + (i++) + ".";
            y.set(p + "kind", a.kind().name());
            y.set(p + "partner", a.partnerId());
            y.set(p + "per-gb", a.perGb());
            y.set(p + "share-percent", a.sharePercent());
            y.set(p + "max-band", a.maxBand() == null ? null : a.maxBand().key());
            y.set(p + "deprioritize", a.deprioritize());
            y.set(p + "ends-at", a.endsAt());
        }
    }

    private static void readLedger(ConfigurationSection s, List<dev.mccomm.cell.model.LedgerEntry> out) {
        for (Map<?, ?> m : s.getMapList("ledger")) {
            Object t = m.get("time"), a = m.get("amount");
            out.add(new dev.mccomm.cell.model.LedgerEntry(t instanceof Number n ? n.longValue() : 0,
                    a instanceof Number n2 ? n2.longValue() : 0, String.valueOf(m.get("note"))));
        }
    }

    private static void writeLedger(YamlConfiguration y, String path, List<dev.mccomm.cell.model.LedgerEntry> ledger) {
        if (ledger.isEmpty()) return;
        List<Map<String, Object>> out = new ArrayList<>();
        for (dev.mccomm.cell.model.LedgerEntry e : ledger) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("time", e.time());
            m.put("amount", e.amount());
            m.put("note", e.note());
            out.add(m);
        }
        y.set(path, out);
    }

    public void save() {
        File dir = plugin.getDataFolder();
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();

        YamlConfiguration cy = new YamlConfiguration();
        for (Carrier c : carriers.values()) {
            String p = "carriers." + c.id() + ".";
            cy.set(p + "name", c.name());
            cy.set(p + "owner", c.owner().toString());
            cy.set(p + "color", c.color().asHexString());
            cy.set(p + "colors", dev.mccomm.cell.util.Style.toHex(c.colors()));
            cy.set(p + "created-at", c.createdAt());
            cy.set(p + "prefix", c.prefix());
            cy.set(p + "balance", c.balance());
            cy.set(p + "next-upkeep", c.nextUpkeep());
            cy.set(p + "unpaid-since", c.unpaidSince());
            cy.set(p + "trade-in", c.tradeIn());
            for (var e : c.stock().entrySet()) if (e.getValue() > 0) cy.set(p + "stock." + e.getKey().replace(":", "~"), e.getValue());
            cy.set(p + "mvno", c.mvno());
            if (c.simulatedUsers() > 0) cy.set(p + "simulated-users", c.simulatedUsers());
            cy.set(p + "mvno-owed", c.mvnoOwed());
            writeAgreements(cy, p + "agreements", c.agreements());
            writeAgreements(cy, p + "offers", c.offers());
            cy.set(p + "trade-in-percent", c.tradeInPercent());
            for (dev.mccomm.cell.model.Plan pl : c.plans().values()) {
                writePlan(cy, p + "plans." + pl.id() + ".", pl);
            }
            writeLedger(cy, p + "ledger", c.ledger());
            cy.set(p + "employees", c.employees().stream().map(UUID::toString).toList());
        }

        YamlConfiguration ty = new YamlConfiguration();
        ty.set("next-id", nextTowerId);
        for (Tower t : towers.values()) {
            String p = "towers." + t.id() + ".";
            ty.set(p + "carrier", t.carrierId());
            ty.set(p + "name", t.name());
            ty.set(p + "manufacturer", t.manufacturerId());
            ty.set(p + "model", t.modelName());
            ty.set(p + "model-bands", t.modelBands().stream().map(Band::key).toList());
            for (var e : t.screen().entrySet()) ty.set(p + "screen." + e.getKey(), e.getValue().toString());
            ty.set(p + "height", Math.round(t.heightAboveGround() * 10) / 10.0);
            ty.set(p + "wear", Math.round(t.wear() * 100) / 100.0);
            ty.set(p + "wear-at", t.wearAt());
            ty.set(p + "world", t.worldId().toString());
            ty.set(p + "x", t.x());
            ty.set(p + "y", t.y());
            ty.set(p + "z", t.z());
            ty.set(p + "generation", t.generation());
            ty.set(p + "bands", t.enabledBands().stream().map(Band::key).toList());
            ty.set(p + "backhaul", t.backhaul() == null ? null : t.backhaul().name());
            ty.set(p + "display", t.displayId() == null ? null : t.displayId().toString());
            ty.set(p + "panel", t.panelId() == null ? null : t.panelId().toString());
            ty.set(p + "created-by", t.createdBy() == null ? null : t.createdBy().toString());
            ty.set(p + "created-at", t.createdAt());
        }

        YamlConfiguration py = new YamlConfiguration();
        for (PlayerProfile pr : profiles.values()) {
            if (pr.isEmpty()) continue;
            String p = "players." + pr.player() + ".";
            py.set(p + "active", new ArrayList<>(pr.active()));
            py.set(p + "inactive", new ArrayList<>(pr.inactive()));
            if (pr.hud() != null) py.set(p + "hud", pr.hud());
            if (pr.dnd()) py.set(p + "dnd", true);
            if (pr.dataSim() != null) py.set(p + "data-sim", pr.dataSim());
            if (pr.phone() != null) py.set(p + "phone", pr.phone());
            if (!pr.ownedPhones().isEmpty()) py.set(p + "owned-phones", new ArrayList<>(pr.ownedPhones()));
            if (pr.balance() != 0) py.set(p + "balance", pr.balance());
            for (Map.Entry<String, Long> e : pr.overdue().entrySet()) py.set(p + "overdue." + e.getKey(), e.getValue());
            for (Map.Entry<String, dev.mccomm.cell.model.Subscription> e : pr.subs().entrySet()) {
                py.set(p + "subs." + e.getKey() + ".plan", e.getValue().planId());
                py.set(p + "subs." + e.getKey() + ".next-bill", e.getValue().nextBill());
                py.set(p + "subs." + e.getKey() + ".warned", e.getValue().warned());
                py.set(p + "subs." + e.getKey() + ".data-left", Math.round(e.getValue().dataLeftMB() * 100) / 100.0);
                py.set(p + "subs." + e.getKey() + ".finance-phone", e.getValue().financePhone());
                py.set(p + "subs." + e.getKey() + ".finance-remaining", e.getValue().financeRemaining());
                py.set(p + "subs." + e.getKey() + ".finance-installment", e.getValue().financeInstallment());
                if (e.getValue().grandfathered() != null) writePlan(py, p + "subs." + e.getKey() + ".grandfathered.", e.getValue().grandfathered());
            }
            if (!pr.notices().isEmpty()) py.set(p + "notices", new ArrayList<>(pr.notices()));
            if (!pr.giftedPhones().isEmpty()) py.set(p + "gifted-phones", new ArrayList<>(pr.giftedPhones()));
            if (pr.starterGiven()) py.set(p + "starter-given", true);
            for (Map.Entry<String, String> e : pr.phoneLocks().entrySet()) py.set(p + "phone-locks." + e.getKey(), e.getValue());
            for (Map.Entry<String, String> e : pr.blacklist().entrySet()) py.set(p + "blacklist." + e.getKey(), e.getValue());
            writeLedger(py, p + "ledger", pr.ledger());
            for (Map.Entry<String, String> e : pr.numbers().entrySet()) py.set(p + "numbers." + e.getKey(), e.getValue());
            for (Map.Entry<String, String> e : pr.contacts().entrySet()) py.set(p + "contacts." + e.getKey(), e.getValue());
            List<Map<String, Object>> inbox = new ArrayList<>();
            for (dev.mccomm.cell.model.SmsMessage m : pr.inbox()) {
                Map<String, Object> mm = new LinkedHashMap<>();
                mm.put("from", m.fromNumber());
                mm.put("from-name", m.fromName());
                mm.put("to", m.toNumber());
                mm.put("text", m.text());
                mm.put("time", m.time());
                mm.put("read", m.read());
                inbox.add(mm);
            }
            if (!inbox.isEmpty()) py.set(p + "inbox", inbox);
        }

        YamlConfiguration my = new YamlConfiguration();
        for (dev.mccomm.cell.model.Manufacturer m : manufacturers.values()) {
            String p = "manufacturers." + m.id() + ".";
            my.set(p + "name", m.name());
            my.set(p + "owner", m.owner() == null ? null : m.owner().toString());
            my.set(p + "system", m.system());
            my.set(p + "employees", m.employees().stream().map(UUID::toString).toList());
            my.set(p + "colors", dev.mccomm.cell.util.Style.toHex(m.colors()));
            my.set(p + "balance", m.balance());
            my.set(p + "research-points", m.researchPoints());
            my.set(p + "researched-gen", m.researchedGen());
            my.set(p + "researched-bands", m.researchedBands().stream().map(Band::key).toList());
            for (dev.mccomm.cell.model.TowerModel tm : m.models().values()) {
                String q = p + "models." + tm.id() + ".";
                my.set(q + "name", tm.name());
                my.set(q + "generation", tm.generation());
                my.set(q + "bands", tm.bands().stream().map(Band::key).toList());
                my.set(q + "margin-percent", tm.marginPercent());
                my.set(q + "published", tm.published());
                my.set(q + "sold", tm.sold());
            }
            writeLedger(my, p + "ledger", m.ledger());
        }

        YamlConfiguration sy = new YamlConfiguration();
        sy.set("simulated-revenue", simulatedRevenue);

        try {
            sy.save(new File(dir, "server.yml"));
            my.save(new File(dir, "manufacturers.yml"));
            cy.save(new File(dir, "carriers.yml"));
            ty.save(new File(dir, "towers.yml"));
            py.save(new File(dir, "players.yml"));
            if (plugin instanceof dev.mccomm.cell.CellPlugin cp && cp.workforce() != null) cp.workforce().save();
            dirty = false;
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save MCcomm data", e);
        }
    }
}
