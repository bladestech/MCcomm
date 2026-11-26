package dev.mccomm.cell.gui;

import dev.mccomm.cell.CellPlugin;
import dev.mccomm.cell.Settings;
import dev.mccomm.cell.band.Band;
import dev.mccomm.cell.band.BandSpec;
import dev.mccomm.cell.economy.BillingService;
import dev.mccomm.cell.economy.ManufacturerService;
import dev.mccomm.cell.model.Carrier;
import dev.mccomm.cell.model.LedgerEntry;
import dev.mccomm.cell.model.Manufacturer;
import dev.mccomm.cell.model.TowerModel;
import dev.mccomm.cell.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Manufacturer management in four tabs (Overview, Research, Models, Money) plus the model editor,
 * and the carrier side: the tower shop, tower stock and the stock picker used by /tower install.
 */
public final class ManufacturerMenu {
    private static final long CONFIRM_MS = 5000;
    private static final String OVERVIEW = "overview", RESEARCH = "research", MODELS = "models", MONEY = "money";
    private static final List<Frame.Tab> TABS = List.of(
            new Frame.Tab(OVERVIEW, Material.SMITHING_TABLE, "Overview"),
            new Frame.Tab(RESEARCH, Material.ENCHANTING_TABLE, "Research"),
            new Frame.Tab(MODELS, Material.LIGHTNING_ROD, "Models"),
            new Frame.Tab(MONEY, Material.GOLD_INGOT, "Money"));

    private final CellPlugin plugin;
    private final Map<String, Long> confirms = new HashMap<>();

    public ManufacturerMenu(CellPlugin plugin) {
        this.plugin = plugin;
    }

    private Settings s() {
        return plugin.settings();
    }

    private ManufacturerService mf() {
        return plugin.manufacturers();
    }

    private void click(Player p) {
        Frame.click(p);
    }

    private boolean confirmed(Player p, String action) {
        String key = p.getUniqueId() + ":" + action;
        long now = System.currentTimeMillis();
        Long until = confirms.get(key);
        if (until != null && until > now) {
            confirms.remove(key);
            return true;
        }
        confirms.put(key, now + CONFIRM_MS);
        return false;
    }

    private boolean isOwner(Player p, Manufacturer m) {
        return p.hasPermission("mccomm.admin") || (m.owner() != null && m.owner().equals(p.getUniqueId()));
    }

    private boolean canView(Player p, Manufacturer m) {
        return p.hasPermission("mccomm.admin") || m.isStaff(p.getUniqueId());
    }

    private String bandList(java.util.Collection<Band> bands) {
        if (bands.isEmpty()) return "none";
        List<String> n = new ArrayList<>();
        for (Band b : bands) n.add(s().spec(b).name());
        return String.join(", ", n);
    }

    private Menu page(Player p, Manufacturer m, String tab) {
        Menu menu = Frame.create(p, Items.esc(m.name()), "manufacturer");
        Frame.tabs(menu, p, TABS, tab, key -> tab(p, m, key));
        return menu;
    }

    private Menu sub(Player p, String title, String backLabel, Runnable back) {
        Menu menu = Frame.create(p, title, "manufacturer");
        Frame.back(menu, p, backLabel, back);
        return menu;
    }

    private void tab(Player p, Manufacturer m, String tab) {
        switch (tab) {
            case RESEARCH -> research(p, m);
            case MODELS -> models(p, m, 0);
            case MONEY -> money(p, m);
            default -> open(p, m);
        }
    }

    // =============================================================== overview

    public void open(Player p, Manufacturer m) {
        if (m.system() || !canView(p, m)) {
            Msg.send(p, "<red>You don't work for that manufacturer.");
            return;
        }
        boolean owner = isOwner(p, m);
        Menu menu = page(p, m, OVERVIEW);
        menu.set(13, Items.of(Material.SMITHING_TABLE, "<b>" + m.styled(),
                "<gray>Owner: <white>" + name(m.owner()),
                "<gray>Models: <white>" + m.models().size() + " <gray>· Installed towers: <white>" + mf().installed(m)));
        menu.set(20, Items.glow(Items.of(Material.GOLD_BLOCK, "<gold><b>" + s().money(m.balance()),
                "<gray>Sales profit and upkeep share land here.", "<yellow>Click · Money tab")), e -> {
            click(p);
            money(p, m);
        });
        menu.set(22, Items.of(Material.EMERALD, "<green>+" + s().money(mf().upkeepIncome(m)) + " <gray>per " + BillingService.period(s().upkeepPeriodHours),
                "<gray>Upkeep share: " + Math.round(s().upkeepSharePercent) + "% of the upkeep",
                "<gray>on towers you built."));
        menu.set(24, Items.of(Material.EXPERIENCE_BOTTLE, "<light_purple>Gen " + m.researchedGen() + " · " + m.researchPoints() + " points",
                "<gray>Bands: <white>" + bandList(m.researchedBands()), "<yellow>Click · Research tab"), e -> {
            click(p);
            research(p, m);
        });
        menu.set(38, Items.of(Material.PAINTING, "<white>Branding: " + m.styled(), "<gray>" + dev.mccomm.cell.util.Style.describe(m.colors()),
                owner ? "<yellow>Click · change" : "<dark_gray>Only the owner can change this"), e -> {
            if (!owner) return;
            click(p);
            plugin.carrierMenu().branding(p, "Branding · " + Items.esc(m.name()), m.name(), m.colors(), false, st -> {
                m.colors(st);
                plugin.store().markDirty();
            }, () -> open(p, m));
        });
        menu.set(40, Items.of(Material.WRITABLE_BOOK, "<green>New model"), e -> {
            click(p);
            models(p, m, 0);
        });
        List<String> staff = new ArrayList<>();
        staff.add("<gray>Owner: <white>" + name(m.owner()));
        for (UUID id : m.employees()) staff.add("<gray>• <white>" + name(id));
        staff.add("");
        staff.add("<dark_gray>/manufacturer employee add|remove " + Items.esc(m.name()) + " <player>");
        menu.set(42, Items.of(Material.PLAYER_HEAD, "<white>Players with access", staff));
        Frame.help(menu, "Running a manufacturer", "Research generations and bands, design", "tower models, set a margin and publish",
                "them. Carriers buy them in the Tower", "shop; you also earn " + Math.round(s().upkeepSharePercent) + "% of the upkeep", "on every tower you built.");
        menu.open(p);
    }

    // =============================================================== research

    public void research(Player p, Manufacturer m) {
        boolean owner = isOwner(p, m);
        Menu menu = page(p, m, RESEARCH);
        menu.set(13, Items.glow(Items.of(Material.EXPERIENCE_BOTTLE, "<light_purple><b>" + m.researchPoints() + " research points",
                "<gray>1 credit = " + s().rpPerCredit + " point(s).")));
        for (int g = 1; g <= s().maxGeneration() && g <= 7; g++) {
            int gen = g;
            boolean done = gen <= m.researchedGen();
            boolean next = gen == m.researchedGen() + 1;
            long cost = s().researchGenCost.getOrDefault(gen, 0L);
            List<String> lore = new ArrayList<>();
            List<String> unlocks = new ArrayList<>();
            for (Band b : Band.values()) if (s().spec(b).minGeneration() == gen) unlocks.add(s().spec(b).name());
            if (!unlocks.isEmpty()) lore.add("<gray>Allows: <white>" + String.join(", ", unlocks));
            lore.add(done ? "<green>Researched" : next ? "<yellow>" + cost + " points · click to research" : "<dark_gray>Research Gen " + (gen - 1) + " first");
            menu.set(19 + g - 1, Items.of(done ? Material.LIME_STAINED_GLASS : next ? Material.YELLOW_STAINED_GLASS : Material.GRAY_STAINED_GLASS,
                    (done ? "<green>" : "<white>") + "Gen " + gen, lore), e -> {
                if (!owner || !next) return;
                click(p);
                mf().researchGeneration(p, m);
                research(p, m);
            });
        }
        int slot = 28;
        for (Band b : Band.values()) {
            BandSpec spec = s().spec(b);
            boolean done = m.researchedBands().contains(b);
            boolean genOk = spec.minGeneration() <= m.researchedGen();
            long cost = s().researchBandCost.getOrDefault(b, 0L);
            List<String> lore = new ArrayList<>();
            lore.add("<gray>Range " + Msg.distance(spec.range()) + " · " + Msg.speed(spec.maxMbps()));
            lore.add("<gray>Adds " + s().money(s().mfgBandCost.getOrDefault(b, 0L)) + " to a tower's cost");
            lore.add(done ? "<green>Researched" : !genOk ? "<dark_gray>Needs Gen " + spec.minGeneration() : "<yellow>" + cost + " points · click to research");
            menu.set(slot++, Items.of(done ? spec.icon() : genOk ? Material.PAPER : Material.GRAY_DYE,
                    (done ? "<green>" : genOk ? "<white>" : "<dark_gray>") + Items.esc(spec.name()), lore), e -> {
                if (!owner || done || !genOk) return;
                click(p);
                mf().researchBand(p, m, b);
                research(p, m);
            });
        }
        var sc = s().smallCells;
        if (sc.enabled()) {
            boolean done = m.smallCells();
            boolean genOk = m.researchedGen() >= sc.minGeneration();
            menu.set(37, Items.of(done ? Material.END_ROD : genOk ? Material.PAPER : Material.GRAY_DYE,
                    (done ? "<green>" : genOk ? "<white>" : "<dark_gray>") + "Small cell form factor",
                    "<gray>Short-range, high-capacity cells for", "<gray>busy spots and indoors.",
                    done ? "<green>Researched" : !genOk ? "<dark_gray>Needs Gen " + sc.minGeneration() : "<yellow>" + sc.researchPoints() + " points · click to research"), e -> {
                if (!owner || done || !genOk) return;
                click(p);
                mf().researchSmallCells(p, m);
                research(p, m);
            });
        }
        if (owner) {
            menu.set(39, Items.of(Material.HOPPER, "<green>Fund with ores", "<gray>Ores go straight into research points."), e -> {
                click(p);
                plugin.deposit().open(p, "Research · " + Items.esc(m.name()), v -> mf().fundResearch(p, m, v), () -> research(p, m));
            });
            menu.set(41, Items.of(Material.GOLD_INGOT, "<green>Fund from wallet", "<gray>Wallet: " + s().money(m.balance()),
                    "<yellow>Click · choose an amount"), e -> {
                click(p);
                plugin.prompt().ask(p, "<aqua>How many credits to spend on research?", in -> {
                    try {
                        mf().fundResearchFromWallet(p, m, Long.parseLong(in.replace(",", "").trim()));
                    } catch (NumberFormatException ex) {
                        Msg.send(p, "<red>Enter a whole number.");
                    }
                    research(p, m);
                });
            });
        }
        Frame.help(menu, "Research", "Row 2: generations, in order.", "Row 3: bands; each needs its generation.",
                "Models can only use what you've researched.");
        menu.open(p);
    }

    // ================================================================= models

    public void models(Player p, Manufacturer m) {
        models(p, m, 0);
    }

    public void models(Player p, Manufacturer m, int pg) {
        boolean owner = isOwner(p, m);
        Menu menu = page(p, m, MODELS);
        List<TowerModel> list = new ArrayList<>(m.models().values());
        if (list.isEmpty()) menu.set(22, Frame.empty("No models yet", "Create one below."));
        Frame.page(menu, p, list, pg, Frame.rows(1, 3), tm -> {
            List<String> lore = new ArrayList<>(modelLore(m, tm, true));
            if (owner) lore.add("<yellow>Click · edit · <yellow>Shift-right · delete");
            return Items.of(tm.published() ? Material.LIGHTNING_ROD : Material.IRON_BARS, (tm.published() ? "<gold>" : "<gray>") + Items.esc(tm.name()), lore);
        }, (tm, e) -> {
            if (!owner) return;
            click(p);
            if (e.isShiftClick() && e.isRightClick()) {
                if (mf().inStock(m, tm)) {
                    Msg.send(p, "<red>A carrier still has this model in stock. Unpublish it instead.");
                } else if (confirmed(p, "del:" + m.id() + ":" + tm.id())) {
                    m.models().remove(tm.id());
                    plugin.store().markDirty();
                    Msg.send(p, "<yellow>Deleted <white><n></white>. Installed towers keep working.", Msg.p("n", tm.name()));
                } else {
                    Msg.send(p, "<yellow>Shift-right-click again within 5 seconds to delete <white><n></white>.", Msg.p("n", tm.name()));
                }
                models(p, m, pg);
            } else {
                editor(p, m, tm);
            }
        }, n -> models(p, m, n));
        if (owner) {
            menu.set(40, Items.of(Material.WRITABLE_BOOK, "<green>New model", "<gray>Name it however you like."), e -> {
                click(p);
                plugin.prompt().ask(p, "<aqua>Name for the new tower model:", in -> {
                    TowerModel tm = new TowerModel(m.newModelId(), in.length() > 24 ? in.substring(0, 24) : in);
                    tm.generation(1);
                    tm.bands().addAll(mf().allowedBands(m, 1));
                    m.models().put(tm.id(), tm);
                    plugin.store().markDirty();
                    editor(p, m, tm);
                });
            });
        }
        Frame.help(menu, "Models", "Pick a generation and bands, set your", "margin, then publish. Price = cost +",
                "margin; the Generic manufacturer sells", "at cost + " + Math.round(s().genericMargin) + "%, so beat that.");
        menu.open(p);
    }

    /** Specs and economics of a model. {@code makerView} adds cost/profit details. */
    public List<String> modelLore(Manufacturer m, TowerModel tm, boolean makerView) {
        List<String> lore = new ArrayList<>();
        lore.add((tm.small() ? "<aqua>Small cell <gray>· " : "<gray>Macro cell · ") + "Gen <white>" + tm.generation() + " <gray>· " + bandList(tm.bands()));
        lore.add("<gray>Price: <gold>" + s().money(mf().price(tm)));
        if (makerView) {
            lore.add("<gray>Cost " + s().money(mf().cost(tm)) + " · profit <green>" + s().money(mf().profit(tm)) + " <dark_gray>(" + Math.round(tm.marginPercent()) + "%)");
            lore.add("<gray>Sold: <white>" + tm.sold());
            String problem = mf().problem(m, tm);
            lore.add(problem != null ? "<red>" + Items.esc(problem) : tm.published() ? "<green>Published" : "<gray>Not published");
        }
        return lore;
    }

    private long upkeepFor(TowerModel tm) {
        return s().upkeepBase + s().upkeepPerGen * Math.max(0, tm.generation() - 1);
    }

    public void editor(Player p, Manufacturer m, TowerModel tm) {
        Menu menu = sub(p, "Model · " + Items.esc(tm.name()), "Models", () -> models(p, m, 0));
        List<String> preview = new ArrayList<>(modelLore(m, tm, true));
        preview.add("<gray>Upkeep share: <white>" + s().money(Math.round(upkeepFor(tm) * s().upkeepSharePercent / 100.0))
                + " <gray>per tower per " + BillingService.period(s().upkeepPeriodHours));
        menu.set(4, Items.glow(Items.of(Material.LIGHTNING_ROD, "<gold><b>" + Items.esc(tm.name()), preview)));

        menu.set(19, Items.of(Material.NAME_TAG, "<white>Name: <aqua>" + Items.esc(tm.name()), "<yellow>Click · rename"), e -> {
            click(p);
            plugin.prompt().ask(p, "<aqua>New model name:", in -> {
                tm.name(in.length() > 24 ? in.substring(0, 24) : in);
                plugin.store().markDirty();
                editor(p, m, tm);
            });
        });
        menu.set(21, Items.of(Material.COMPARATOR, "<white>Generation: <aqua>" + tm.generation(),
                "<gray>Researched up to Gen " + m.researchedGen() + ".", "<yellow>Click · next · <yellow>Right-click · previous"), e -> {
            click(p);
            int g = tm.generation() + (e.isRightClick() ? -1 : 1);
            if (g < 1) g = m.researchedGen();
            if (g > m.researchedGen()) g = 1;
            tm.generation(g);
            tm.bands().removeIf(b -> !s().supports(tm.generation(), b));
            plugin.store().markDirty();
            editor(p, m, tm);
        });
        menu.set(23, Items.of(Material.GOLD_NUGGET, "<white>Margin: <green>" + Math.round(tm.marginPercent()) + "%",
                "<gray>Cost " + s().money(mf().cost(tm)) + " → price " + s().money(mf().price(tm)),
                "<yellow>Click · change"), e -> {
            click(p);
            plugin.prompt().ask(p, "<aqua>Profit margin in percent (0-500). Cost is " + s().money(mf().cost(tm)) + ":", in -> {
                try {
                    double v = Double.parseDouble(in.replace("%", "").trim());
                    if (v < 0 || v > 500) throw new NumberFormatException();
                    tm.marginPercent(v);
                    plugin.store().markDirty();
                } catch (NumberFormatException ex) {
                    Msg.send(p, "<red>Enter a number from 0 to 500.");
                }
                editor(p, m, tm);
            });
        });
        String problem = mf().problem(m, tm);
        menu.set(25, Items.of(tm.published() ? Material.LIME_DYE : Material.GRAY_DYE,
                "<white>" + (tm.published() ? "<green>Published" : "<gray>Not published"),
                tm.published() ? "<gray>Carriers can buy it." : "<gray>Only you can see it.",
                problem != null ? "<red>" + Items.esc(problem) : "<yellow>Click · " + (tm.published() ? "unpublish" : "publish")), e -> {
            click(p);
            if (!tm.published() && problem != null) {
                Msg.send(p, "<red>" + Msg.escape(problem));
                return;
            }
            tm.published(!tm.published());
            plugin.store().markDirty();
            editor(p, m, tm);
        });

        int slot = 28;
        for (Band b : Band.values()) {
            BandSpec spec = s().spec(b);
            boolean researched = m.researchedBands().contains(b);
            boolean genOk = s().supports(tm.generation(), b);
            boolean in = tm.bands().contains(b);
            List<String> lore = new ArrayList<>();
            lore.add("<gray>Adds " + s().money(s().mfgBandCost.getOrDefault(b, 0L)) + " to the cost");
            if (!researched) lore.add("<red>Not researched");
            else if (!genOk) lore.add("<red>Needs a Gen " + spec.minGeneration() + " model");
            else lore.add(in ? "<green>Included <dark_gray>· click to remove" : "<gray>Left out <dark_gray>· click to include");
            ItemStack it = Items.of(researched && genOk ? spec.icon() : Material.BARRIER,
                    (in ? "<green><b>" : researched && genOk ? "<gray>" : "<dark_gray>") + Items.esc(spec.name()), lore);
            menu.set(slot++, in ? Items.glow(it) : it, e -> {
                if (!researched || !genOk) return;
                click(p);
                if (!tm.bands().remove(b)) tm.bands().add(b);
                plugin.store().markDirty();
                editor(p, m, tm);
            });
        }
        var sc = s().smallCells;
        if (sc.enabled()) {
            menu.set(40, Items.of(tm.small() ? Material.END_ROD : Material.LIGHTNING_ROD,
                    "<white>Form factor: <aqua>" + (tm.small() ? "Small cell" : "Macro cell"),
                    tm.small() ? "<gray>" + Math.round(sc.rangePercent() * 100) + "% range (full range on " + (sc.fullRangeBands().isEmpty() ? "no bands" : sc.fullRangeBands().stream().map(b -> s().spec(b).name()).collect(java.util.stream.Collectors.joining(", "))) + "), ×" + sc.capacityMultiplier() + " capacity, "
                            + Math.round(sc.costPercent() * 100) + "% of the cost." : "<gray>A full tower with the height bonus.",
                    !m.smallCells() ? "<red>Research small cells first" : "<yellow>Click · switch"), e -> {
                if (!m.smallCells()) return;
                click(p);
                tm.small(!tm.small());
                if (tm.small()) {
                    if (tm.generation() < sc.minGeneration()) tm.generation(Math.min(m.researchedGen(), sc.minGeneration()));
                    tm.bands().removeIf(b -> s().spec(b).minGeneration() < sc.minGeneration());
                }
                plugin.store().markDirty();
                editor(p, m, tm);
            });
        }
        Frame.help(menu, "Model editor", "Row 2: name, generation, margin, publish.", "Row 3: bands it includes (glowing).",
                "Each band adds to the cost to make it.", "", "Small cells: short range, more capacity,",
                "cheaper; 4G and 5G bands only.");
        menu.open(p);
    }

    // ================================================================== money

    public void money(Player p, Manufacturer m) {
        if (m.system() || !canView(p, m)) return;
        Menu menu = page(p, m, MONEY);
        long now = System.currentTimeMillis();
        menu.set(20, Items.of(Material.HOPPER, "<green>Deposit ores", "<gray>Balance: <white>" + s().money(m.balance())), e -> {
            click(p);
            plugin.deposit().open(p, "Deposit · " + Items.esc(m.name()), v -> mf().depositWallet(p, m, v), () -> money(p, m));
        });
        List<String> hist = new ArrayList<>();
        for (LedgerEntry le : m.ledger()) {
            if (hist.size() >= 15) break;
            hist.add(plugin.phone().ledgerLine(le, now));
        }
        if (hist.isEmpty()) hist.add("<dark_gray>Nothing yet");
        menu.set(24, Items.of(Material.BOOK, "<white>Ledger", hist));
        plugin.companyMenu().items(menu, p, m, () -> money(p, m));
        Frame.help(menu, "Money", "Deposit ores into the company wallet.", "Moving money between you and the",
                "company costs " + s().transferFeePercent + "%. Valuation is a guide for", "selling; liquidating closes it for good.");
        menu.open(p);
    }

    // ============================================================ carrier side

    /** Tower shop for a carrier: manufacturers, then their published models. */
    public void shop(Player p, Carrier c) {
        Menu menu = sub(p, "Tower shop · " + Items.esc(c.name()), "Network", () -> plugin.carrierMenu().tab(p, c, CarrierMenu.NETWORK));
        Frame.title(menu, Items.of(Material.GOLD_INGOT, "<gold>Wallet: " + s().money(c.balance())));
        List<Manufacturer> makers = new ArrayList<>();
        for (Manufacturer m : plugin.store().manufacturers()) {
            if (m.models().values().stream().anyMatch(tm -> tm.published() && mf().problem(m, tm) == null)) makers.add(m);
        }
        if (makers.isEmpty()) menu.set(22, Frame.empty("No towers for sale right now"));
        Frame.page(menu, p, makers, 0, Frame.CONTENT, m -> {
            long count = m.models().values().stream().filter(tm -> tm.published() && mf().problem(m, tm) == null).count();
            long from = m.models().values().stream().filter(tm -> tm.published() && mf().problem(m, tm) == null)
                    .mapToLong(tm -> mf().price(tm)).min().orElse(0);
            return Items.of(m.system() ? Material.CRAFTING_TABLE : Material.SMITHING_TABLE, "<b>" + m.styled(),
                    m.system() ? "<gray>Server-run, full-band standard towers" : "<gray>Up to Gen " + m.researchedGen(),
                    "<gray>" + count + " model(s) from " + s().money(from), "<yellow>Click · browse");
        }, (m, e) -> {
            click(p);
            shopModels(p, c, m);
        }, n -> shop(p, c));
        Frame.help(menu, "Tower shop", "Pick a manufacturer, then buy models.", "They go into your stock; install one",
                "on a wall sign with /tower install <name>.");
        menu.open(p);
    }

    public void shopModels(Player p, Carrier c, Manufacturer m) {
        Menu menu = sub(p, Items.esc(m.name()) + " · Towers", "Shop", () -> shop(p, c));
        Frame.title(menu, Items.of(Material.GOLD_INGOT, "<gold>Wallet: " + s().money(c.balance())));
        List<TowerModel> list = new ArrayList<>();
        for (TowerModel tm : m.models().values()) if (tm.published() && mf().problem(m, tm) == null) list.add(tm);
        Frame.page(menu, p, list, 0, Frame.CONTENT, tm -> {
            List<String> lore = new ArrayList<>(modelLore(m, tm, false));
            lore.add("<gray>Upkeep: <white>" + s().money(upkeepFor(tm)) + " <gray>per " + BillingService.period(s().upkeepPeriodHours) + " + backhaul");
            lore.add("<gray>In your stock: <white>" + c.stock().getOrDefault(ManufacturerService.ref(m, tm), 0));
            lore.add("<yellow>Click · buy 1 · <yellow>Right-click · 5 · <yellow>Shift-click · any");
            return Items.of(tm.small() ? Material.END_ROD : Material.LIGHTNING_ROD, "<gold><b>" + Items.esc(tm.name()), lore);
        }, (tm, e) -> {
            click(p);
            if (e.isShiftClick()) {
                plugin.prompt().ask(p, "<aqua>How many <white>" + Items.esc(tm.name()) + "</white> towers? (" + s().money(mf().price(tm)) + " each)", in -> {
                    try {
                        int qty = Integer.parseInt(in.trim());
                        if (qty < 1 || qty > 1000) throw new NumberFormatException();
                        mf().buy(p, c, m, tm, qty);
                    } catch (NumberFormatException ex) {
                        Msg.send(p, "<red>Enter a number from 1 to 1000.");
                    }
                    shopModels(p, c, m);
                });
                return;
            }
            mf().buy(p, c, m, tm, e.isRightClick() ? 5 : 1);
            shopModels(p, c, m);
        }, n -> shopModels(p, c, m));
        menu.open(p);
    }

    public int stockCount(Carrier c) {
        int n = 0;
        for (int v : c.stock().values()) n += v;
        return n;
    }

    private List<String> stockRefs(Carrier c) {
        List<String> refs = new ArrayList<>();
        for (var e : c.stock().entrySet()) {
            if (e.getValue() > 0 && mf().maker(e.getKey()) != null && mf().model(e.getKey()) != null) refs.add(e.getKey());
        }
        return refs;
    }

    /** The carrier's owned, not-yet-installed tower models. */
    public void stock(Player p, Carrier c) {
        Menu menu = sub(p, "Tower stock · " + Items.esc(c.name()), "Network", () -> plugin.carrierMenu().tab(p, c, CarrierMenu.NETWORK));
        List<String> refs = stockRefs(c);
        if (refs.isEmpty()) menu.set(22, Frame.empty("No towers in stock", "Buy some in the Tower shop."));
        Frame.page(menu, p, refs, 0, Frame.CONTENT, ref -> {
            Manufacturer m = mf().maker(ref);
            TowerModel tm = mf().model(ref);
            int n = c.stock().get(ref);
            return Items.amount(Items.of(tm.small() ? Material.END_ROD : Material.LIGHTNING_ROD, "<gold>" + Items.esc(tm.name()) + " <gray>× " + n,
                    "<gray>By " + m.styled() + " <gray>· " + (tm.small() ? "Small cell · " : "") + "Gen " + tm.generation(), "<gray>" + bandList(tm.bands()),
                    "<gray>Install: <white>/tower install <name>"), n);
        }, null, n -> stock(p, c));
        menu.set(40, Items.of(Material.EMERALD, "<green>Tower shop"), ev -> {
            click(p);
            shop(p, c);
        });
        menu.open(p);
    }

    /** Lets the player pick a stocked model to install; {@code onPick} gets the stock ref. */
    public void pickStock(Player p, Carrier c, String towerName, Consumer<String> onPick) {
        pickStock(p, c, towerName, "Install", onPick);
    }

    /** Same, with the menu title's verb ("Install", "Replace"). */
    public void pickStock(Player p, Carrier c, String towerName, String verb, Consumer<String> onPick) {
        Menu menu = Frame.create(p, verb + " " + Items.esc(towerName) + " · pick a model", "manufacturer");
        List<String> refs = stockRefs(c);
        if (refs.isEmpty()) {
            menu.set(22, Items.of(Material.LIGHT_GRAY_DYE, "<gray>No towers in stock", "<yellow>Click · open the Tower shop"), ev -> {
                click(p);
                shop(p, c);
            });
        }
        Frame.page(menu, p, refs, 0, Frame.CONTENT, ref -> {
            Manufacturer m = mf().maker(ref);
            TowerModel tm = mf().model(ref);
            int n = c.stock().get(ref);
            return Items.amount(Items.of(tm.small() ? Material.END_ROD : Material.LIGHTNING_ROD, "<gold>" + Items.esc(tm.name()) + " <gray>× " + n,
                    "<gray>By " + m.styled(), "<gray>" + (tm.small() ? "Small cell · " : "") + "Gen " + tm.generation() + " · " + bandList(tm.bands()), "<yellow>Click · install"), n);
        }, (ref, ev) -> {
            click(p);
            p.closeInventory();
            onPick.accept(ref);
        }, n -> pickStock(p, c, towerName, verb, onPick));
        menu.open(p);
    }

    private static String name(UUID id) {
        if (id == null) return "Server";
        String n = Bukkit.getOfflinePlayer(id).getName();
        return n == null ? id.toString().substring(0, 8) : n;
    }
}
