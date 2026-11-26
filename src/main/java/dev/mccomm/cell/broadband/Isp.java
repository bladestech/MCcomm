package dev.mccomm.cell.broadband;

import dev.mccomm.cell.model.LedgerEntry;
import net.kyori.adventure.text.format.TextColor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** An internet service provider: a company that builds nodes and sells home internet plans. */
public final class Isp {
    private final String id;
    private String name;
    private UUID owner;
    private final Set<UUID> employees = new LinkedHashSet<>();
    private List<TextColor> colors = new ArrayList<>(List.of(TextColor.color(0x55FFFF)));
    private long balance;
    private final Map<String, InternetPlan> plans = new LinkedHashMap<>();
    private final List<LedgerEntry> ledger = new ArrayList<>();
    private long nextUpkeep;  // when node upkeep is next charged (epoch ms), 0 = not scheduled yet
    private long unpaidSince; // when upkeep first failed (epoch ms), 0 = paid up; nodes are dark meanwhile
    private final long createdAt;
    private String carrierId;                 // set for carrier-backed ISPs (fixed wireless over that carrier's towers)
    private int transitTier = 1;              // backbone capacity the ISP buys, shared by every customer
    private final Set<String> peers = new LinkedHashSet<>();       // ISPs it peers with
    private final Set<String> peerOffers = new LinkedHashSet<>();  // peering offers received, not yet answered
    private final Map<String, Bundle> bundles = new LinkedHashMap<>(); // carrier id -> deal
    private double reliability = 1;           // 0..1, a running average of how much of the network was up
    private transient int simCustomers;       // simulated households (market), recomputed
    private transient int simBusinesses;      // simulated businesses (market), recomputed

    public Isp(String name, UUID owner, long createdAt) {
        this.id = idOf(name);
        this.name = name;
        this.owner = owner;
        this.createdAt = createdAt;
    }

    public static String idOf(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public String id() { return id; }
    public String name() { return name; }
    public UUID owner() { return owner; }
    public void owner(UUID o) { this.owner = o; }
    public Set<UUID> employees() { return employees; }
    public List<TextColor> colors() { return colors; }
    public void colors(List<TextColor> c) { this.colors = c.isEmpty() ? new ArrayList<>(List.of(TextColor.color(0x55FFFF))) : new ArrayList<>(c); }
    public long createdAt() { return createdAt; }
    public long balance() { return balance; }
    public void balance(long b) { this.balance = b; }
    public Map<String, InternetPlan> plans() { return plans; }
    public List<LedgerEntry> ledger() { return ledger; }
    public long nextUpkeep() { return nextUpkeep; }
    public void nextUpkeep(long t) { this.nextUpkeep = t; }
    public long unpaidSince() { return unpaidSince; }
    public void unpaidSince(long t) { this.unpaidSince = t; }

    public String carrierId() { return carrierId; }
    public void carrierId(String c) { this.carrierId = c; }
    public boolean carrierBacked() { return carrierId != null; }
    public int transitTier() { return transitTier; }
    public void transitTier(int t) { this.transitTier = Math.max(1, t); }
    public Set<String> peers() { return peers; }
    public Set<String> peerOffers() { return peerOffers; }
    public Map<String, Bundle> bundles() { return bundles; }
    public double reliability() { return reliability; }
    public void reliability(double r) { this.reliability = Math.clamp(r, 0, 1); }
    public int simCustomers() { return simCustomers; }
    public void simCustomers(int n) { this.simCustomers = Math.max(0, n); }
    public int simBusinesses() { return simBusinesses; }
    public void simBusinesses(int n) { this.simBusinesses = Math.max(0, n); }

    /** The ISP's name as MiniMessage, in its solid colour or gradient. */
    public String styled() {
        return dev.mccomm.cell.util.Style.mm(colors, name);
    }

    /** True while upkeep is paid up. The nodes of an ISP that is not paid up are dark. */
    public boolean paidUp() {
        return unpaidSince == 0;
    }

    public boolean isStaff(UUID player) {
        return owner.equals(player) || employees.contains(player);
    }

    /** Adds a ledger line (newest first, last 30 kept). */
    public void log(long amount, String note) {
        ledger.addFirst(new LedgerEntry(System.currentTimeMillis(), amount, note));
        while (ledger.size() > 30) ledger.removeLast();
    }
}
