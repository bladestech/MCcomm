package dev.mccomm.cell.economy;

import dev.mccomm.cell.CellPlugin;
import dev.mccomm.cell.Settings;
import dev.mccomm.cell.band.Band;
import dev.mccomm.cell.band.Generation;
import dev.mccomm.cell.model.Carrier;
import dev.mccomm.cell.model.Manufacturer;
import dev.mccomm.cell.model.Tower;
import dev.mccomm.cell.model.TowerModel;
import dev.mccomm.cell.util.Msg;
import org.bukkit.entity.Player;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

/**
 * Tower manufacturers: research, model costs and prices, selling towers to carriers, and the
 * upkeep share. Also runs the built-in Generic manufacturer.
 */
public final class ManufacturerService {
    private final CellPlugin plugin;

    public ManufacturerService(CellPlugin plugin) {
        this.plugin = plugin;
    }

    private Settings s() {
        return plugin.settings();
    }

    // ================================================================ generic

    /** Creates/refreshes the server-run Generic manufacturer and assigns pre-manufacturer towers to it. */
    public void setup() {
        Manufacturer g = plugin.store().manufacturer(Manufacturer.GENERIC);
        if (g == null) {
            g = new Manufacturer("Generic", null, true);
            g.colors(java.util.List.of(net.kyori.adventure.text.format.TextColor.color(0xB0B8C4)));
            plugin.store().addManufacturer(g);
        }
        g.researchedGen(s().maxGeneration());
        g.researchedBands().addAll(EnumSet.allOf(Band.class));
        g.models().clear();
        for (Generation gen : s().generations.values()) {
            TowerModel tm = new TowerModel("g" + gen.level(), gen.model());
            tm.generation(gen.level());
            for (Band b : Band.values()) if (s().supports(gen.level(), b)) tm.bands().add(b);
            tm.marginPercent(s().genericMargin);
            tm.published(s().genericEnabled);
            g.models().put(tm.id(), tm);
        }
        for (Tower t : plugin.store().towers()) {
            if (t.manufacturerId() != null) continue;
            t.manufacturerId(Manufacturer.GENERIC);
            t.modelName(s().generation(t.generation()).model());
            for (Band b : Band.values()) if (s().supports(t.generation(), b)) t.modelBands().add(b);
        }
        plugin.store().markDirty();
    }

    // ================================================================ pricing

    public long cost(TowerModel tm) {
        long c = s().mfgBase + s().mfgPerGen * Math.max(0, tm.generation() - 1);
        for (Band b : tm.bands()) c += s().mfgBandCost.getOrDefault(b, 0L);
        return c;
    }

    public long price(TowerModel tm) {
        return Math.round(cost(tm) * (1 + tm.marginPercent() / 100.0));
    }

    public long profit(TowerModel tm) {
        return price(tm) - cost(tm);
    }

    /** Bands a manufacturer may put in a model of this generation. */
    public EnumSet<Band> allowedBands(Manufacturer m, int generation) {
        EnumSet<Band> out = EnumSet.noneOf(Band.class);
        for (Band b : m.researchedBands()) if (s().supports(generation, b)) out.add(b);
        return out;
    }

    /** Why the model can't be sold as it is, or null if it's fine. */
    public String problem(Manufacturer m, TowerModel tm) {
        if (tm.generation() > m.researchedGen()) return "Gen " + tm.generation() + " isn't researched yet.";
        if (tm.bands().isEmpty()) return "Add at least one band.";
        for (Band b : tm.bands()) {
            if (!m.researchedBands().contains(b)) return s().spec(b).name() + " isn't researched yet.";
            if (!s().supports(tm.generation(), b)) return s().spec(b).name() + " needs Gen " + s().spec(b).minGeneration() + ".";
        }
        return null;
    }

    public static String ref(Manufacturer m, TowerModel tm) {
        return m.id() + ":" + tm.id();
    }

    /** Looks up "manufacturer:model". */
    public TowerModel model(String ref) {
        int i = ref.indexOf(':');
        if (i < 0) return null;
        Manufacturer m = plugin.store().manufacturer(ref.substring(0, i));
        return m == null ? null : m.models().get(ref.substring(i + 1));
    }

    public Manufacturer maker(String ref) {
        int i = ref.indexOf(':');
        return i < 0 ? null : plugin.store().manufacturer(ref.substring(0, i));
    }

    /** True if any carrier still has this model in stock (it can't be deleted then). */
    public boolean inStock(Manufacturer m, TowerModel tm) {
        String ref = ref(m, tm);
        for (Carrier c : plugin.store().carriers()) if (c.stock().getOrDefault(ref, 0) > 0) return true;
        return false;
    }

    // ================================================================ research

    public long nextGenCost(Manufacturer m) {
        return s().researchGenCost.getOrDefault(m.researchedGen() + 1, -1L);
    }

    public void researchGeneration(Player p, Manufacturer m) {
        int next = m.researchedGen() + 1;
        if (next > s().maxGeneration()) {
            Msg.send(p, "<gray>Every generation is already researched.");
            return;
        }
        long cost = nextGenCost(m);
        if (cost < 0) cost = 0;
        if (m.researchPoints() < cost) {
            Msg.send(p, "<red>Gen <g> needs <white><c></white> research points; you have <white><rp></white>.",
                    Msg.p("g", "" + next), Msg.p("c", "" + cost), Msg.p("rp", "" + m.researchPoints()));
            return;
        }
        m.researchPoints(m.researchPoints() - cost);
        m.researchedGen(next);
        m.log(0, "Researched Gen " + next + " (" + cost + " RP)");
        plugin.store().markDirty();
        Msg.send(p, "<green>Researched <white>Gen <g></white>. Its bands can now be researched.", Msg.p("g", "" + next));
    }

    public void researchBand(Player p, Manufacturer m, Band b) {
        if (m.researchedBands().contains(b)) return;
        int need = s().spec(b).minGeneration();
        if (need > m.researchedGen()) {
            Msg.send(p, "<red><b> needs Gen <g> researched first.", Msg.p("b", s().spec(b).name()), Msg.p("g", "" + need));
            return;
        }
        long cost = s().researchBandCost.getOrDefault(b, 0L);
        if (m.researchPoints() < cost) {
            Msg.send(p, "<red><b> needs <white><c></white> research points; you have <white><rp></white>.",
                    Msg.p("b", s().spec(b).name()), Msg.p("c", "" + cost), Msg.p("rp", "" + m.researchPoints()));
            return;
        }
        m.researchPoints(m.researchPoints() - cost);
        m.researchedBands().add(b);
        m.log(0, "Researched " + s().spec(b).name() + " (" + cost + " RP)");
        plugin.store().markDirty();
        Msg.send(p, "<green>Researched <white><b></white>. You can put it in your models now.", Msg.p("b", s().spec(b).name()));
    }

    /** Ores deposited straight into research. */
    public void fundResearch(Player p, Manufacturer m, long credits) {
        long rp = Math.round(credits * s().rpPerCredit);
        m.researchPoints(m.researchPoints() + rp);
        m.log(0, "Research funded by " + p.getName() + " (+" + rp + " RP)");
        plugin.store().markDirty();
        Msg.send(p, "<green>+<white><rp></white> research points for <white><m></white>. Total: <white><t></white>.",
                Msg.p("rp", "" + rp), Msg.p("m", m.name()), Msg.p("t", "" + m.researchPoints()));
    }

    /** Converts wallet credits into research points. */
    public void fundResearchFromWallet(Player p, Manufacturer m, long credits) {
        if (credits <= 0 || m.balance() < credits) {
            Msg.send(p, "<red><m>'s wallet has <white><b></white>.", Msg.p("m", m.name()), Msg.p("b", s().money(m.balance())));
            return;
        }
        m.balance(m.balance() - credits);
        m.log(-credits, "Spent on research");
        fundResearch(p, m, credits);
    }

    public void depositWallet(Player p, Manufacturer m, long credits) {
        m.balance(m.balance() + credits);
        m.log(credits, "Deposit by " + p.getName());
        plugin.store().markDirty();
        Msg.send(p, "<green>Deposited <white><a></white> into <white><m></white>. Balance: <white><b></white>.",
                Msg.p("a", s().money(credits)), Msg.p("m", m.name()), Msg.p("b", s().money(m.balance())));
    }

    // ================================================================== sales

    /** A carrier buys {@code qty} towers of a model into its stock. */
    public boolean buy(Player p, Carrier c, Manufacturer m, TowerModel tm, int qty) {
        if (qty <= 0) return false;
        if (!tm.published() || problem(m, tm) != null) {
            Msg.send(p, "<red>That model isn't for sale.");
            return false;
        }
        long unit = price(tm);
        long total = unit * qty;
        if (c.balance() < total) {
            Msg.send(p, "<red><n>× <model> costs <white><t></white>; <c>'s wallet has <white><b></white>.",
                    Msg.p("n", "" + qty), Msg.p("model", tm.name()), Msg.p("t", s().money(total)), Msg.p("c", c.name()),
                    Msg.p("b", s().money(c.balance())));
            return false;
        }
        c.balance(c.balance() - total);
        c.log(-total, "Bought " + qty + "× " + tm.name() + " from " + m.name());
        c.stock().merge(ref(m, tm), qty, Integer::sum);
        tm.sold(tm.sold() + qty);
        if (!m.system()) {
            long cost = cost(tm) * qty;
            m.balance(m.balance() + total - cost);
            m.log(total, "Sold " + qty + "× " + tm.name() + " to " + c.name());
            m.log(-cost, "Manufacturing " + qty + "× " + tm.name());
            plugin.billing().notify(m.owner(), "<green><c> bought <white><n>× <model></white> (+<white><profit></white> profit).",
                    Msg.p("c", c.name()), Msg.p("n", "" + qty), Msg.p("model", tm.name()), Msg.p("profit", s().money(total - cost)));
        }
        plugin.store().markDirty();
        Msg.send(p, "<green>Bought <white><n>× <model></white> for <white><t></white>. In stock: <white><s></white>. Install with <white>/tower install <name></white>.",
                Msg.p("n", "" + qty), Msg.p("model", tm.name()), Msg.p("t", s().money(total)),
                Msg.p("s", "" + c.stock().get(ref(m, tm))));
        return true;
    }

    // ================================================================= upkeep

    /** Pays manufacturers their share of a carrier's tower upkeep (called when upkeep is paid). */
    public void payUpkeepShares(Carrier c) {
        if (s().upkeepSharePercent <= 0) return;
        Map<String, Long> shares = new HashMap<>();
        for (Tower t : plugin.store().towersOf(c.id())) {
            if (t.manufacturerId() == null) continue;
            long share = (long) Math.floor(s().upkeepCost(t) * s().upkeepSharePercent / 100.0);
            if (share > 0) shares.merge(t.manufacturerId(), share, Long::sum);
        }
        for (var e : shares.entrySet()) {
            Manufacturer m = plugin.store().manufacturer(e.getKey());
            if (m == null || m.system()) continue;
            m.balance(m.balance() + e.getValue());
            m.log(e.getValue(), "Upkeep share · " + c.name());
        }
        plugin.store().markDirty();
    }

    /** Upkeep share this manufacturer earns per upkeep period from all installed towers. */
    public long upkeepIncome(Manufacturer m) {
        long sum = 0;
        for (Tower t : plugin.store().towers()) {
            if (m.id().equals(t.manufacturerId())) sum += (long) Math.floor(s().upkeepCost(t) * s().upkeepSharePercent / 100.0);
        }
        return sum;
    }

    public int installed(Manufacturer m) {
        int n = 0;
        for (Tower t : plugin.store().towers()) if (m.id().equals(t.manufacturerId())) n++;
        return n;
    }
}
