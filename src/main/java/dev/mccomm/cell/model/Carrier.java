package dev.mccomm.cell.model;

import net.kyori.adventure.text.format.TextColor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public final class Carrier {
    private final String id;
    private String name;
    private UUID owner;
    private final Set<UUID> employees = new LinkedHashSet<>();
    private TextColor color;
    private String prefix; // 3-digit number prefix, unique per carrier
    private long balance;
    private final Map<String, Plan> plans = new LinkedHashMap<>();
    private long nextUpkeep;   // when upkeep is next charged (epoch ms), 0 = not scheduled yet
    private long unpaidSince;  // when upkeep first failed (epoch ms), 0 = paid up
    private final List<LedgerEntry> ledger = new ArrayList<>();
    private final long createdAt;

    public Carrier(String name, UUID owner, TextColor color, long createdAt) {
        this.id = idOf(name);
        this.name = name;
        this.owner = owner;
        this.color = color;
        this.createdAt = createdAt;
    }

    public static String idOf(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public String id() { return id; }
    public String name() { return name; }
    public UUID owner() { return owner; }
    public void owner(UUID owner) { this.owner = owner; }
    public Set<UUID> employees() { return employees; }
    public TextColor color() { return color; }
    public void color(TextColor color) { this.color = color; }
    public long createdAt() { return createdAt; }
    public String prefix() { return prefix; }
    public void prefix(String prefix) { this.prefix = prefix; }
    public Map<String, Plan> plans() { return plans; }
    public long nextUpkeep() { return nextUpkeep; }
    public void nextUpkeep(long t) { this.nextUpkeep = t; }
    public long unpaidSince() { return unpaidSince; }
    public void unpaidSince(long t) { this.unpaidSince = t; }

    public long balance() { return balance; }
    public void balance(long b) { this.balance = b; }
    public List<LedgerEntry> ledger() { return ledger; }

    /** Adds a ledger line (newest first, last 30 kept). */
    public void log(long amount, String note) {
        ledger.addFirst(new LedgerEntry(System.currentTimeMillis(), amount, note));
        while (ledger.size() > 30) ledger.removeLast();
    }


    public boolean isStaff(UUID player) {
        return owner.equals(player) || employees.contains(player);
    }
}
