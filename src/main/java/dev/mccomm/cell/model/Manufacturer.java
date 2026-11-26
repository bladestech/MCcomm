package dev.mccomm.cell.model;

import dev.mccomm.cell.band.Band;
import net.kyori.adventure.text.format.TextColor;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A tower manufacturer: researches generations and bands, designs tower models and sells them to
 * carriers. The built-in "Generic" manufacturer ({@code system}) is run by the server.
 */
public final class Manufacturer implements Company {
    public static final String GENERIC = "generic";

    private final String id;
    private String name;
    private UUID owner; // null for the system manufacturer
    private final Set<UUID> employees = new LinkedHashSet<>();
    private List<TextColor> colors = new ArrayList<>(List.of(TextColor.color(0xFFC371)));
    private long balance;
    private long researchPoints;
    private int researchedGen = 1;
    private boolean smallCells; // small cell form factor researched
    private final EnumSet<Band> researchedBands = EnumSet.of(Band.G2, Band.G3);
    private final Map<String, TowerModel> models = new LinkedHashMap<>();
    private final List<LedgerEntry> ledger = new ArrayList<>();
    private final boolean system;

    public Manufacturer(String name, UUID owner, boolean system) {
        this.id = idOf(name);
        this.name = name;
        this.owner = owner;
        this.system = system;
    }

    public static String idOf(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    @Override public Kind kind() { return Kind.MANUFACTURER; }
    public String id() { return id; }
    public String name() { return name; }
    public UUID owner() { return owner; }
    public void owner(UUID o) { this.owner = o; }
    public Set<UUID> employees() { return employees; }
    public List<TextColor> colors() { return colors; }
    public void colors(List<TextColor> c) { this.colors = new ArrayList<>(c); }
    public long balance() { return balance; }
    public void balance(long b) { this.balance = b; }
    public long researchPoints() { return researchPoints; }
    public void researchPoints(long rp) { this.researchPoints = rp; }
    public int researchedGen() { return researchedGen; }
    public void researchedGen(int g) { this.researchedGen = g; }
    public boolean smallCells() { return smallCells; }
    public void smallCells(boolean v) { this.smallCells = v; }
    public EnumSet<Band> researchedBands() { return researchedBands; }
    public Map<String, TowerModel> models() { return models; }
    public List<LedgerEntry> ledger() { return ledger; }
    public boolean system() { return system; }

    public String styled() { return dev.mccomm.cell.util.Style.mm(colors, name); }

    public boolean isStaff(UUID player) {
        return owner != null && (owner.equals(player) || employees.contains(player));
    }

    public void log(long amount, String note) {
        ledger.addFirst(new LedgerEntry(System.currentTimeMillis(), amount, note));
        while (ledger.size() > 30) ledger.removeLast();
    }

    public String newModelId() {
        int i = 1;
        while (models.containsKey("m" + i)) i++;
        return "m" + i;
    }

    private String group; // parent company id, null = on its own

    @Override public String group() { return group; }
    @Override public void group(String g) { this.group = g; }
}
