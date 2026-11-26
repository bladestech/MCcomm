package dev.mccomm.cell.economy;

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

/** A parent company: one owner, a treasury, and member carriers, manufacturers and ISPs (by company ref key). */
public final class Group {
    private final String id;
    private String name;
    private UUID owner;
    private List<TextColor> colors = new ArrayList<>(List.of(TextColor.color(0xE0C068)));
    private long balance;
    private final Set<String> members = new LinkedHashSet<>();       // company ref keys
    private final Map<String, Long> loans = new LinkedHashMap<>();   // member ref key -> owed to the treasury
    private final List<LedgerEntry> ledger = new ArrayList<>();
    private final long createdAt;

    public Group(String name, UUID owner, long createdAt) {
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
    public List<TextColor> colors() { return colors; }
    public void colors(List<TextColor> c) { this.colors = c.isEmpty() ? new ArrayList<>(List.of(TextColor.color(0xE0C068))) : new ArrayList<>(c); }
    public long balance() { return balance; }
    public void balance(long b) { this.balance = b; }
    public Set<String> members() { return members; }
    public Map<String, Long> loans() { return loans; }
    public List<LedgerEntry> ledger() { return ledger; }
    public long createdAt() { return createdAt; }

    public long loan(String memberKey) {
        return loans.getOrDefault(memberKey, 0L);
    }

    public long totalLoans() {
        long s = 0;
        for (long v : loans.values()) s += v;
        return s;
    }

    public String styled() {
        return dev.mccomm.cell.util.Style.mm(colors, name);
    }

    /** Adds a ledger line (newest first, last 30 kept). */
    public void log(long amount, String note) {
        ledger.addFirst(new LedgerEntry(System.currentTimeMillis(), amount, note));
        while (ledger.size() > 30) ledger.removeLast();
    }
}
