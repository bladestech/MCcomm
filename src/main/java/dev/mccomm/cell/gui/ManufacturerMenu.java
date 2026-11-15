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
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Manufacturer management (research, models with a live margin preview, wallet) and the carrier side:
 * the tower shop, tower stock and the stock picker used by /tower install.
 */
public final class ManufacturerMenu {
    private static final long CONFIRM_MS = 5000;

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

    private Menu menu(int rows, String title) {
        return new Menu(rows, Msg.mm("<dark_gray>" + title), "manufacturer").fill(Items.of(Material.BLACK_STAINED_GLASS_PANE, "<gray> "));
    }

    private void click(Player p) {
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.6f);
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

    // =============================================================== overview

    public void open(Player p, Manufacturer m) {
        if (m.system() || !canView(p, m)) {
            Msg.send(p, "<red>You don't work for that manufacturer.");
            return;
        }
        boolean owner = isOwner(p, m);
        Menu menu = menu(5, Items.esc(m.name()) + " · Manufacturer");
        menu.set(4, Items.of(Material.SMITHING_TABLE, "<b>" + m.styled(),
                "<gray>Owner: <white>" + name(m.owner()),
                "<gray>Models: <white>" + m.models().size(),
                "<gray>Towers installed: <white>" + mf().installed(m)));
        menu.set(10, Items.glow(Items.of(Material.GOLD_BLOCK, "<gold><b>" + s().money(m.balance()),
                "<gray>Sales profit and upkeep share land here.",
                "<gray>Upkeep share: <white>" + s().money(mf().upkeepIncome(m)) + " <gray>per " + BillingService.period(s().upkeepPeriodHours),
                "<gray>(" + Math.round(s().upkeepSharePercent) + "% of upkeep on your installed towers)")));
        menu.set(12, Items.of(Material.HOPPER, "<green>Deposit ores", "<gray>Into the wallet."), e -> {
            click(p);
            plugin.deposit().open(p, "Deposit · " + Items.esc(m.name()), v -> mf().depositWallet(p, m, v), () -> open(p, m));
        });
        menu.set(14, Items.of(Material.ENCHANTING_TABLE, "<light_purple>Research",
                "<gray>Points: <white>" + m.researchPoints(),
                "<gray>Generation: <white>" + m.researchedGen() + " / " + s().maxGeneration(),
                "<gray>Bands: <white>" + bandList(m.researchedBands()),
                "<yellow>Click to open"), e -> {
            click(p);
            research(p, m);
        });
        menu.set(16, Items.of(Material.LIGHTNING_ROD, "<gold>Tower models", "<gray>" + m.models().size() + " model(s)",
                owner ? "<yellow>Design, price and publish" : "<gray>View your models"), e -> {
            click(p);
            models(p, m);
        });
        List<String> hist = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (LedgerEntry le : m.ledger()) {
            if (hist.size() >= 15) break;
            hist.add(plugin.phone().ledgerLine(le, now));
        }
        if (hist.isEmpty()) hist.add("<dark_gray>Nothing yet");
        menu.set(29, Items.of(Material.BOOK, "<white>Ledger", hist));
        menu.set(31, Items.of(Material.PAINTING, "<white>Branding: " + m.styled(), "<gray>" + dev.mccomm.cell.util.Style.describe(m.colors()),
                owner ? "<yellow>Click to change" : "<dark_gray>Only the owner can change this"), e -> {
            if (!owner) return;
            click(p);
            plugin.carrierMenu().branding(p, "Branding · " + Items.esc(m.name()), m.name(), m.colors(), false, st -> {
                m.colors(st);
                plugin.store().markDirty();
            }, () -> open(p, m));
        });
        List<String> staff = new ArrayList<>();
        staff.add("<gray>Owner: <white>" + name(m.owner()));
        for (UUID id : m.employees()) staff.add("<gray>• <white>" + name(id));
        staff.add("");
        staff.add("<dark_gray>/manufacturer employee add|remove " + Items.esc(m.name()) + " <player>");
        menu.set(33, Items.of(Material.PLAYER_HEAD, "<white>Staff", staff));
        menu.set(40, Items.of(Material.BARRIER, "<red>Close"), e -> p.closeInventory());
        menu.open(p);
    }

    // =============================================================== research

    public void research(Player p, Manufacturer m) {
        boolean owner = isOwner(p, m);
        Menu menu = menu(5, Items.esc(m.name()) + " · Research");
        menu.set(4, Items.glow(Items.of(Material.EXPERIENCE_BOTTLE, "<light_purple><b>" + m.researchPoints() + " research points",
                "<gray>1 credit = " + s().rpPerCredit + " point(s).")));
        // Generations
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
            menu.set(10 + g - 1, Items.of(done ? Material.LIME_STAINED_GLASS : next ? Material.YELLOW_STAINED_GLASS : Material.GRAY_STAINED_GLASS,
                    (done ? "<green>" : "<white>") + "Gen " + gen, lore), e -> {
                if (!owner || !next) return;
                click(p);
                mf().researchGeneration(p, m);
                research(p, m);
            });
        }
        // Bands
        int slot = 19;
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
        menu.set(36, Items.of(Material.ARROW, "<gray>« Back"), e -> {
            click(p);
            open(p, m);
        });
        if (owner) {
            menu.set(39, Items.of(Material.HOPPER, "<green>Fund with ores", "<gray>Ores go straight into research points."), e -> {
                click(p);
                plugin.deposit().open(p, "Research · " + Items.esc(m.name()), v -> mf().fundResearch(p, m, v), () -> research(p, m));
            });
            menu.set(41, Items.of(Material.GOLD_INGOT, "<green>Fund from wallet", "<gray>Wallet: " + s().money(m.balance()),
                    "<yellow>Click to choose an amount"), e -> {
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
        menu.open(p);
    }

    // ================================================================= models

    public void models(Player p, Manufacturer m) {
        boolean owner = isOwner(p, m);
        Menu menu = menu(5, Items.esc(m.name()) + " · Models");
        int slot = 0;
        for (TowerModel tm : new ArrayList<>(m.models().values())) {
            if (slot >= 36) break;
            List<String> lore = new ArrayList<>(modelLore(m, tm, true));
            if (owner) {
                lore.add("");
                lore.add("<yellow>Click <gray>edit · <yellow>Shift-right <gray>delete");
            }
            menu.set(slot++, Items.of(tm.published() ? Material.LIGHTNING_ROD : Material.IRON_BARS,
                    (tm.published() ? "<gold>" : "<gray>") + Items.esc(tm.name()), lore), e -> {
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
                    models(p, m);
                } else {
                    editor(p, m, tm);
                }
            });
        }
        if (slot == 0) menu.set(13, Items.of(Material.LIGHT_GRAY_DYE, "<gray>No models yet"));
        menu.set(36, Items.of(Material.ARROW, "<gray>« Back"), e -> {
            click(p);
            open(p, m);
        });
        if (owner) {
            menu.set(40, Items.of(Material.WRITABLE_BOOK, "<green>New model", "<gray>Name it however you like,", "<gray>e.g. \"Skyline S1\"."), e -> {
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
        menu.open(p);
    }

    /** Specs and economics of a model. {@code makerView} adds cost/profit details. */
    public List<String> modelLore(Manufacturer m, TowerModel tm, boolean makerView) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Generation: <white>" + tm.generation());
        lore.add("<gray>Bands: <white>" + bandList(tm.bands()));
        lore.add("<gray>Price: <gold>" + s().money(mf().price(tm)));
        if (makerView) {
            lore.add("<gray>Cost to make: <white>" + s().money(mf().cost(tm)));
            lore.add("<gray>Profit per tower: <green>" + s().money(mf().profit(tm)) + " <dark_gray>(" + Math.round(tm.marginPercent()) + "% margin)");
            lore.add("<gray>Upkeep share: <white>" + s().money(Math.round(upkeepFor(tm) * s().upkeepSharePercent / 100.0))
                    + " <gray>per tower per " + BillingService.period(s().upkeepPeriodHours));
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
        Menu menu = menu(5, "Model · " + Items.esc(tm.name()));
        List<String> preview = new ArrayList<>(modelLore(m, tm, true));
        menu.set(4, Items.glow(Items.of(Material.LIGHTNING_ROD, "<gold><b>" + Items.esc(tm.name()), preview)));

        menu.set(10, Items.of(Material.NAME_TAG, "<white>Name: <aqua>" + Items.esc(tm.name()),
                "<gray>Shown on the tower screen and in shops.", "<yellow>Click to rename"), e -> {
            click(p);
            plugin.prompt().ask(p, "<aqua>New model name:", in -> {
                tm.name(in.length() > 24 ? in.substring(0, 24) : in);
                plugin.store().markDirty();
                editor(p, m, tm);
            });
        });
        menu.set(11, Items.of(Material.COMPARATOR, "<white>Generation: <aqua>" + tm.generation(),
                "<gray>Researched up to Gen " + m.researchedGen() + ".", "<yellow>Click <gray>next · <yellow>Right-click <gray>previous"), e -> {
            click(p);
            int g = tm.generation() + (e.isRightClick() ? -1 : 1);
            if (g < 1) g = m.researchedGen();
            if (g > m.researchedGen()) g = 1;
            tm.generation(g);
            tm.bands().removeIf(b -> !s().supports(tm.generation(), b));
            plugin.store().markDirty();
            editor(p, m, tm);
        });
        menu.set(12, Items.of(Material.GOLD_NUGGET, "<white>Margin: <green>" + Math.round(tm.marginPercent()) + "%",
                "<gray>Cost " + s().money(mf().cost(tm)) + " → price " + s().money(mf().price(tm)),
                "<gray>Profit per tower: <green>" + s().money(mf().profit(tm)),
                "<yellow>Click to change"), e -> {
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
        menu.set(14, Items.of(tm.published() ? Material.LIME_DYE : Material.GRAY_DYE,
                "<white>" + (tm.published() ? "<green>Published" : "<gray>Not published"),
                tm.published() ? "<gray>Carriers can buy it in the tower shop." : "<gray>Only you can see it.",
                problem != null ? "<red>" + Items.esc(problem) : "<yellow>Click to " + (tm.published() ? "unpublish" : "publish")), e -> {
            click(p);
            if (!tm.published() && problem != null) {
                Msg.send(p, "<red>" + Msg.escape(problem));
                return;
            }
            tm.published(!tm.published());
            plugin.store().markDirty();
            editor(p, m, tm);
        });

        // Band toggles: row 3
        int slot = 19;
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
        menu.set(36, Items.of(Material.ARROW, "<gray>« Models"), e -> {
            click(p);
            models(p, m);
        });
        menu.open(p);
    }

    // ============================================================ carrier side

    /** Tower shop for a carrier: manufacturers, then their published models. */
    public void shop(Player p, Carrier c) {
        Menu menu = menu(4, "Tower shop · " + Items.esc(c.name()));
        int slot = 0;
        for (Manufacturer m : plugin.store().manufacturers()) {
            long count = m.models().values().stream().filter(tm -> tm.published() && mf().problem(m, tm) == null).count();
            if (count == 0 || slot >= 27) continue;
            long from = m.models().values().stream().filter(tm -> tm.published() && mf().problem(m, tm) == null)
                    .mapToLong(tm -> mf().price(tm)).min().orElse(0);
            menu.set(slot++, Items.of(m.system() ? Material.CRAFTING_TABLE : Material.SMITHING_TABLE, "<b>" + m.styled(),
                    m.system() ? "<gray>Server-run, full-band standard towers" : "<gray>Up to Gen " + m.researchedGen(),
                    "<gray>" + count + " model(s) from " + s().money(from), "<yellow>Click to browse"), e -> {
                click(p);
                shopModels(p, c, m);
            });
        }
        if (slot == 0) menu.set(13, Items.of(Material.LIGHT_GRAY_DYE, "<gray>No towers for sale right now"));
        menu.set(27, Items.of(Material.ARROW, "<gray>« Back"), e -> {
            click(p);
            plugin.carrierMenu().open(p, c);
        });
        menu.set(31, Items.of(Material.GOLD_INGOT, "<gold>" + Items.esc(c.name()) + " wallet: " + s().money(c.balance())));
        menu.set(35, Items.of(Material.CHEST, "<white>Your stock", "<gray>" + stockCount(c) + " tower(s) waiting to be installed"), e -> {
            click(p);
            stock(p, c);
        });
        menu.open(p);
    }

    public void shopModels(Player p, Carrier c, Manufacturer m) {
        Menu menu = menu(4, Items.esc(m.name()) + " · Towers");
        int slot = 0;
        for (TowerModel tm : m.models().values()) {
            if (!tm.published() || mf().problem(m, tm) != null || slot >= 27) continue;
            List<String> lore = new ArrayList<>(modelLore(m, tm, false));
            lore.add("<gray>Upkeep: <white>" + s().money(upkeepFor(tm)) + " <gray>per " + BillingService.period(s().upkeepPeriodHours) + " + backhaul");
            lore.add("<gray>In your stock: <white>" + c.stock().getOrDefault(ManufacturerService.ref(m, tm), 0));
            lore.add("");
            lore.add("<yellow>Click <gray>buy 1 · <yellow>Right-click <gray>buy 5 · <yellow>Shift-click <gray>any amount");
            menu.set(slot++, Items.of(Material.LIGHTNING_ROD, "<gold><b>" + Items.esc(tm.name()), lore), e -> {
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
            });
        }
        menu.set(27, Items.of(Material.ARROW, "<gray>« Shop"), e -> {
            click(p);
            shop(p, c);
        });
        menu.set(31, Items.of(Material.GOLD_INGOT, "<gold>Wallet: " + s().money(c.balance())));
        menu.open(p);
    }

    public int stockCount(Carrier c) {
        int n = 0;
        for (int v : c.stock().values()) n += v;
        return n;
    }

    /** The carrier's owned, not-yet-installed tower models. */
    public void stock(Player p, Carrier c) {
        Menu menu = menu(4, "Tower stock · " + Items.esc(c.name()));
        int slot = 0;
        for (var e : c.stock().entrySet()) {
            if (e.getValue() <= 0 || slot >= 27) continue;
            Manufacturer m = mf().maker(e.getKey());
            TowerModel tm = mf().model(e.getKey());
            if (m == null || tm == null) continue;
            List<String> lore = new ArrayList<>();
            lore.add("<gray>By " + m.styled());
            lore.add("<gray>Generation: <white>" + tm.generation());
            lore.add("<gray>Bands: <white>" + bandList(tm.bands()));
            lore.add("");
            lore.add("<gray>Look at a wall sign and run");
            lore.add("<white>/tower install <name>");
            menu.set(slot++, Items.amount(Items.of(Material.LIGHTNING_ROD, "<gold>" + Items.esc(tm.name()) + " <gray>× " + e.getValue(), lore), e.getValue()));
        }
        if (slot == 0) menu.set(13, Items.of(Material.LIGHT_GRAY_DYE, "<gray>No towers in stock", "<dark_gray>Buy some in the tower shop."));
        menu.set(27, Items.of(Material.ARROW, "<gray>« Back"), ev -> {
            click(p);
            plugin.carrierMenu().open(p, c);
        });
        menu.set(31, Items.of(Material.EMERALD, "<green>Tower shop"), ev -> {
            click(p);
            shop(p, c);
        });
        menu.open(p);
    }

    /** Lets the player pick a stocked model to install; {@code onPick} gets the stock ref. */
    public void pickStock(Player p, Carrier c, String towerName, Consumer<String> onPick) {
        Menu menu = menu(4, "Install " + Items.esc(towerName) + " · pick a model");
        int slot = 0;
        for (var e : c.stock().entrySet()) {
            if (e.getValue() <= 0 || slot >= 27) continue;
            Manufacturer m = mf().maker(e.getKey());
            TowerModel tm = mf().model(e.getKey());
            if (m == null || tm == null) continue;
            String ref = e.getKey();
            menu.set(slot++, Items.amount(Items.of(Material.LIGHTNING_ROD, "<gold>" + Items.esc(tm.name()) + " <gray>× " + e.getValue(),
                    "<gray>By " + m.styled(), "<gray>Gen " + tm.generation() + " · " + bandList(tm.bands()), "", "<yellow>Click to install"), e.getValue()), ev -> {
                click(p);
                p.closeInventory();
                onPick.accept(ref);
            });
        }
        if (slot == 0) {
            menu.set(13, Items.of(Material.LIGHT_GRAY_DYE, "<gray>No towers in stock", "<yellow>Click to open the tower shop"), ev -> {
                click(p);
                shop(p, c);
            });
        }
        menu.open(p);
    }

    private static String name(UUID id) {
        if (id == null) return "Server";
        String n = Bukkit.getOfflinePlayer(id).getName();
        return n == null ? id.toString().substring(0, 8) : n;
    }
}
