package dev.mccomm.cell.gui;

import dev.mccomm.cell.CellPlugin;
import dev.mccomm.cell.band.Band;
import dev.mccomm.cell.economy.BillingService;
import dev.mccomm.cell.economy.PartnerService;
import dev.mccomm.cell.model.Agreement;
import dev.mccomm.cell.model.Carrier;
import dev.mccomm.cell.model.LedgerEntry;
import dev.mccomm.cell.model.Plan;
import dev.mccomm.cell.model.PlayerProfile;
import dev.mccomm.cell.model.Subscription;
import dev.mccomm.cell.model.Tower;
import dev.mccomm.cell.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * /carrier manage: six tabs (Overview, Network, Customers, Operations, Money, Partners) plus their
 * sub-pages (plan editor, branding, agreement terms). Every page uses the shared {@link Frame}.
 */
public final class CarrierMenu {
    private static final long CONFIRM_MS = 5000;
    public static final String OVERVIEW = "overview", NETWORK = "network", CUSTOMERS = "customers", OPERATIONS = "operations",
            MONEY = "money", PARTNERS = "partners";
    private static final List<Frame.Tab> TABS = List.of(
            new Frame.Tab(OVERVIEW, Material.BEACON, "Overview"),
            new Frame.Tab(NETWORK, Material.LIGHTNING_ROD, "Network"),
            new Frame.Tab(CUSTOMERS, Material.PLAYER_HEAD, "Customers"),
            new Frame.Tab(OPERATIONS, Material.IRON_PICKAXE, "Operations"),
            new Frame.Tab(MONEY, Material.GOLD_INGOT, "Money"),
            new Frame.Tab(PARTNERS, Material.FILLED_MAP, "Partners"));

    private final CellPlugin plugin;
    private final Map<UUID, Long> confirm = new HashMap<>();

    public CarrierMenu(CellPlugin plugin) {
        this.plugin = plugin;
    }

    private void click(Player p) {
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.6f);
    }

    private String money(long v) {
        return plugin.settings().money(v);
    }

    private boolean isOwner(Player p, Carrier c) {
        return p.hasPermission("mccomm.admin") || c.owner().equals(p.getUniqueId());
    }

    public boolean canView(Player p, Carrier c) {
        return p.hasPermission("mccomm.admin") || c.isStaff(p.getUniqueId());
    }

    /** A tab page: frame, tab row, and the name in the title. */
    private Menu page(Player p, Carrier c, String tab) {
        Menu m = Frame.create(p, Items.esc(c.name()), "carrier");
        Frame.tabs(m, p, TABS, tab, key -> tab(p, c, key));
        return m;
    }

    /** A sub-page: frame, title, Back. */
    private Menu sub(Player p, String title, Runnable back, String backLabel) {
        Menu m = Frame.create(p, title, "carrier");
        Frame.back(m, p, backLabel, back);
        return m;
    }

    public void open(Player p, Carrier c) {
        tab(p, c, OVERVIEW);
    }

    public void tab(Player p, Carrier c, String tab) {
        if (!canView(p, c)) {
            Msg.send(p, "<red>You don't work for <white><c></white>.", Msg.p("c", c.name()));
            return;
        }
        switch (tab) {
            case NETWORK -> network(p, c, 0);
            case CUSTOMERS -> customers(p, c, 0);
            case OPERATIONS, "staff" -> operations(p, c);
            case MONEY -> money(p, c);
            case PARTNERS -> partners(p, c);
            default -> overview(p, c);
        }
    }

    // ================================================================ overview

    private double incomePerDay(Carrier c) {
        double perDay = 0;
        for (PlayerProfile pr : plugin.store().profiles()) {
            Subscription sub = pr.sub(c.id());
            if (sub == null || sub.planId() == null) continue;
            Plan plan = c.plans().get(sub.planId());
            if (plan != null) perDay += plugin.sales().price(plan) * 24.0 / plan.periodHours();
        }
        return perDay + plugin.simulation().revenuePerDay(c);
    }

    private int realSubscribers(Carrier c) {
        int n = 0;
        for (PlayerProfile pr : plugin.store().profiles()) {
            Subscription sub = pr.sub(c.id());
            if (sub != null && sub.planId() != null && c.plans().containsKey(sub.planId())) n++;
        }
        return n;
    }

    private void overview(Player p, Carrier c) {
        Menu m = page(p, c, OVERVIEW);
        long now = System.currentTimeMillis();
        List<Tower> towers = plugin.store().towersOf(c.id());
        long upkeep = plugin.billing().upkeepCost(c);
        int stage = plugin.billing().decayStage(c);

        m.set(13, Items.of(Material.BEACON, "<b>" + c.styled(),
                "<gray>Owner: <white>" + name(c.owner()),
                "<gray>Numbers: <white>" + c.prefix() + "-xxxx",
                c.mvno() ? "<gray>MVNO on <white>" + mvnoNetworkName(c) : "<gray>Towers: <white>" + towers.size()));

        // Headline numbers
        List<String> wallet = new ArrayList<>();
        wallet.add("<gray>Upkeep: <white>" + money(upkeep) + " <gray>in " + BillingService.until(c.nextUpkeep() - now));
        if (c.balance() < upkeep) wallet.add("<gold>Not enough for the next upkeep");
        long bankrupt = plugin.companies().bankruptAt(c);
        if (bankrupt > 0) wallet.add("<dark_red><b>Bankrupt in " + BillingService.until(bankrupt - now));
        wallet.add("");
        wallet.add("<yellow>Click · deposit ores");
        m.set(19, Items.glow(Items.of(Material.GOLD_BLOCK, "<gold><b>" + money(c.balance()), wallet)), e -> {
            click(p);
            plugin.deposit().open(p, c, () -> overview(p, c));
        });

        double income = incomePerDay(c);
        double upkeepDay = upkeep * 24.0 / plugin.settings().upkeepPeriodHours;
        long staffDay = plugin.workforce().dailyCost(c);
        double net = income - upkeepDay - staffDay;
        m.set(21, Items.of(net >= 0 ? Material.EMERALD : Material.REDSTONE, (net >= 0 ? "<green>+" : "<red>-") + money(Math.round(Math.abs(net))) + " <gray>a day",
                "<gray>Income: <green>+" + money(Math.round(income)),
                "<gray>Upkeep: <red>-" + money(Math.round(upkeepDay)),
                "<gray>Operations and transit: <red>-" + money(staffDay)));

        int real = realSubscribers(c);
        if (plugin.market().active()) {
            var sc = plugin.market().score(c);
            int size = Math.max(1, plugin.store().marketSize());
            m.set(23, Items.of(Material.PLAYER_HEAD, "<white>" + (real + c.simulatedUsers()) + " <gray>subscribers",
                    "<gray>Players: <white>" + real + " <gray>· Customers: <white>" + c.simulatedUsers(),
                    "<gray>Market share: <white>" + Math.round(100.0 * c.simulatedUsers() / size) + "%",
                    sc == null ? "" : "<gray>Satisfaction: " + satColor(sc.satisfaction()) + Math.round(sc.satisfaction()) + "%",
                    "", "<yellow>Click · Customers tab"), e -> {
                click(p);
                customers(p, c, 0);
            });
        } else {
            m.set(23, Items.of(Material.PLAYER_HEAD, "<white>" + (real + c.simulatedUsers()) + " <gray>subscribers",
                    "<gray>Players: <white>" + real + (c.simulatedUsers() > 0 ? " <gray>· Simulated: <white>" + c.simulatedUsers() : "")));
        }

        if (stage == 0) {
            m.set(25, Items.of(Material.LIME_DYE, "<green>Upkeep paid", "<gray>Your network runs normally."));
        } else {
            boolean silent = stage >= plugin.settings().silentStage();
            m.set(25, Items.glow(Items.of(silent ? Material.REDSTONE_BLOCK : Material.REDSTONE, silent ? "<dark_red>Towers silent" : "<red>Towers decaying",
                    "<gray>Upkeep unpaid for <white>" + BillingService.until(now - c.unpaidSince()),
                    silent ? "<dark_red>No service at all." : "<gray>Speed <white>" + Math.round(plugin.settings().decaySpeed[stage - 1] * 100) + "%",
                    "", "<yellow>Deposit " + money(upkeep) + "+ to repair")));
        }

        // Alerts
        List<ItemStack> alerts = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        int worn = 0, silentWorn = 0;
        for (Tower t : towers) {
            int ws = plugin.workforce().wearStage(t);
            if (ws >= 2) worn++;
            if (ws >= 4) silentWorn++;
        }
        if (worn > 0) {
            alerts.add(Items.of(silentWorn > 0 ? Material.IRON_BARS : Material.REDSTONE_TORCH,
                    (silentWorn > 0 ? "<red>" : "<gold>") + worn + " tower(s) badly worn", "<gray>They're slow or have lost bands.", "<yellow>Click · Network tab"));
            actions.add(() -> network(p, c, 0));
        }
        if (plugin.workforce().enabled()) {
            for (var part : dev.mccomm.cell.workforce.OpsPart.values()) {
                if (plugin.workforce().fullCost(c, part) <= 0) continue;
                double f = plugin.workforce().funding(c, part);
                if (f >= 0.75) continue;
                boolean short_ = plugin.workforce().state(c).lastPaid(part) + 0.01 < plugin.workforce().state(c).target(part);
                alerts.add(Items.of(part.icon, "<gold>" + part.display + " at " + dev.mccomm.cell.workforce.WorkforceService.pct(f),
                        short_ ? "<red>The balance couldn't pay the full bill." : "<gray>Funded below what the business needs.",
                        "<yellow>Click · Operations tab"));
                actions.add(() -> operations(p, c));
            }
        }
        long owesGroup = plugin.groups().owes(c);
        if (owesGroup > 0) {
            var gr = plugin.groups().groupOf(c);
            alerts.add(Items.of(Material.GOLD_NUGGET, "<gold>Owes " + Items.esc(gr == null ? "its parent" : gr.name()) + " " + money(owesGroup),
                    "<gray>Repaid from income automatically.", "<gray>Nothing can go to a wallet meanwhile."));
            actions.add(() -> money(p, c));
        }
        int offers = plugin.partners().offersTo(c).size();
        if (offers > 0) {
            alerts.add(Items.of(Material.WRITABLE_BOOK, "<yellow>" + offers + " partner offer(s)", "<yellow>Click · Partners tab"));
            actions.add(() -> partners(p, c));
        }
        if (c.plans().isEmpty()) {
            alerts.add(Items.of(Material.PAPER, "<gold>No plans", "<gray>Nobody can subscribe.", "<yellow>Click · Customers tab"));
            actions.add(() -> customers(p, c, 0));
        }
        int stock = plugin.manufacturerMenu().stockCount(c);
        if (stock > 0 && !c.mvno()) {
            alerts.add(Items.of(Material.CHEST, "<white>" + stock + " tower(s) in stock", "<gray>Install with <white>/tower install <name>"));
            actions.add(() -> plugin.manufacturerMenu().stock(p, c));
        }
        if (alerts.isEmpty()) {
            m.set(31, Items.of(Material.LIME_DYE, "<green>Nothing needs attention"));
        } else {
            int[] slots = {28, 29, 30, 31, 32, 33, 34};
            for (int i = 0; i < alerts.size() && i < slots.length; i++) {
                Runnable act = actions.get(i);
                m.set(slots[i], alerts.get(i), e -> {
                    click(p);
                    act.run();
                });
            }
        }

        // Quick actions
        m.set(38, Items.of(Material.HOPPER, "<green>Deposit ores"), e -> {
            click(p);
            plugin.deposit().open(p, c, () -> overview(p, c));
        });
        m.set(40, Items.of(Material.PAPER, "<white>Plans"), e -> {
            click(p);
            customers(p, c, 0);
        });
        m.set(42, Items.of(Material.IRON_PICKAXE, "<white>Operations"), e -> {
            click(p);
            operations(p, c);
        });
        Frame.help(m, "Running a carrier", "Build towers (Network), set plans", "(Customers), fund operations, and keep",
                "the wallet above upkeep.", "", "Alerts in the middle row show what", "needs attention; click one to go there.");
        m.open(p);
    }

    private static String satColor(double s) {
        return s >= 75 ? "<green>" : s >= 50 ? "<yellow>" : "<red>";
    }

    // ================================================================= network

    public void network(Player p, Carrier c, int pg) {
        Menu m = page(p, c, NETWORK);
        if (c.mvno()) {
            var deal = plugin.partners().mvnoHost(c);
            m.set(22, Items.of(Material.ENDER_EYE, "<white>Host network: " + mvnoNetworkName(c),
                    deal == null ? "<red>You have no host: no service." : "<gray>Your subscribers use its towers.",
                    "", "<yellow>Click · Partners tab"), e -> {
                click(p);
                partners(p, c);
            });
            Frame.help(m, "Network (MVNO)", "MVNOs have no towers of their own.", "Your host's towers are your network.");
            m.open(p);
            return;
        }
        List<Tower> towers = new ArrayList<>(plugin.store().towersOf(c.id()));
        towers.sort(Comparator.comparingDouble((Tower x) -> x.wear()).reversed());
        if (towers.isEmpty()) {
            m.set(22, Frame.empty("No towers yet", "Buy one in the Tower shop, then look", "at a wall sign: /tower install <name>"));
        } else {
            Frame.page(m, p, towers, pg, Frame.rows(1, 3), t -> towerItem(c, t), null, n -> network(p, c, n));
        }
        m.set(38, Items.of(Material.EMERALD, "<green>Tower shop", "<gray>Buy towers from manufacturers."), e -> {
            click(p);
            plugin.manufacturerMenu().shop(p, c);
        });
        int stock = plugin.manufacturerMenu().stockCount(c);
        m.set(42, Items.amount(Items.of(Material.CHEST, "<white>Stock: " + stock,
                "<gray>Bought, not installed yet.", "<gray>Install: <white>/tower install <name>"), Math.max(1, stock)), e -> {
            click(p);
            plugin.manufacturerMenu().stock(p, c);
        });
        if (plugin.workforce().enabled()) {
            m.set(40, Items.of(Material.IRON_PICKAXE, "<white>Field crews: "
                            + dev.mccomm.cell.workforce.WorkforceService.pct(plugin.workforce().funding(c, dev.mccomm.cell.workforce.OpsPart.FIELD)),
                    "<gray>They repair worn towers.", "<yellow>Click · Operations tab"), e -> {
                click(p);
                operations(p, c);
            });
        }
        Frame.help(m, "Network", "Towers, most worn first. Wear builds", "with traffic; your field crews repair",
                "it (Operations), or contractors when", "crews aren't funded.", "",
                "Total upkeep: " + money(plugin.billing().upkeepCost(c)) + " per " + BillingService.period(plugin.settings().upkeepPeriodHours));
        m.open(p);
    }

    private ItemStack towerItem(Carrier c, Tower t) {
        int stage = plugin.billing().decayStage(c);
        List<String> lore = new ArrayList<>();
        String bh;
        if (t.backhaul() != null) bh = DepositMenu.TowerGuiNames.pretty(t.backhaul());
        else if (t.small() && t.wirelessLink() != 0) {
            Tower link = plugin.store().tower(t.wirelessLink());
            bh = "<aqua>wireless via " + Items.esc(link == null ? "?" : link.name());
        } else bh = "<red>no backhaul";
        lore.add("<gray>" + (t.small() ? "<aqua>Small cell</aqua> · " : "") + Items.esc(plugin.towers().modelName(t)) + " · Gen " + t.generation() + " · " + bh);
        lore.add("<gray>Users: <white>" + plugin.signal().usersOn(t) + (plugin.signal().simOn(t) > 0 ? " <dark_gray>+ " + plugin.signal().simOn(t) + " customers" : ""));
        if (plugin.workforce().enabled()) lore.add("<gray>Wear: " + OpsMenu.wearBar(t.wear()) + " <white>" + Math.round(t.wear()) + "%");
        var job = plugin.workforce().job(t);
        if (job != null) lore.add(job.started() ? "<aqua>⚒ Repair in progress" : "<yellow>Repair queued");
        String status = !t.online() ? "<red>Offline" : stage >= plugin.settings().silentStage() ? "<dark_red>Silent (upkeep unpaid)"
                : plugin.workforce().silent(t) ? "<dark_red>Silent (worn out)"
                : stage > 0 ? "<gold>Decaying (upkeep unpaid)"
                : plugin.workforce().wearStage(t) > 0 ? "<yellow>Worn: " + Math.round(plugin.workforce().wearSpeed(t) * 100) + "% speed" : "<green>Online";
        lore.add(status);
        lore.add("<dark_gray>" + (t.world() == null ? "?" : t.world().getName()) + " " + t.x() + ", " + t.y() + ", " + t.z());
        int ws = plugin.workforce().wearStage(t);
        Material icon = !t.online() || stage >= plugin.settings().silentStage() || ws >= 4 ? Material.IRON_BARS
                : ws >= 2 ? Material.REDSTONE_TORCH : t.small() ? Material.END_ROD : Material.LIGHTNING_ROD;
        return Items.of(icon, "<gold>" + Items.esc(t.name()), lore);
    }

    // =============================================================== customers

    public void customers(Player p, Carrier c, int pg) {
        boolean owner = isOwner(p, c);
        Menu m = page(p, c, CUSTOMERS);
        if (plugin.market().active()) m.set(10, marketItem(c));
        else m.set(10, Items.of(Material.PLAYER_HEAD, "<white>Subscribers", "<gray>Players: <white>" + realSubscribers(c),
                c.simulatedUsers() > 0 ? "<gray>Simulated: <white>" + c.simulatedUsers() : ""));

        double resale = plugin.settings().resalePercent;
        double tiNet = c.tradeInPercent() - resale;
        m.set(13, Items.of(c.tradeIn() ? Material.HOPPER_MINECART : Material.MINECART, "<white>Trade-ins: " + (c.tradeIn() ? "<green>on" : "<red>off"),
                "<gray>Credit <white>" + Math.round(c.tradeInPercent()) + "%</white> of a phone's price off a loan,",
                "<gray>resell it for " + Math.round(resale) + "%: " + (tiNet > 0 ? "<red>costs " + Math.round(tiNet) : "<green>earns " + Math.round(-tiNet)) + "%</red><gray> each.",
                owner ? "<yellow>Click · toggle · <yellow>Right-click · set rate" : "<dark_gray>Only the owner can change this"), e -> {
            if (!owner) return;
            click(p);
            if (e.isRightClick()) {
                askDouble(p, "<aqua>Trade-in rate in percent of the phone's price (0-100):", 0, 100, v -> {
                    c.tradeInPercent(v);
                    plugin.store().markDirty();
                    customers(p, c, pg);
                });
            } else {
                c.tradeIn(!c.tradeIn());
                plugin.store().markDirty();
                customers(p, c, pg);
            }
        });
        m.set(16, Items.of(Material.PAINTING, "<white>Branding: " + c.styled(),
                "<gray>" + dev.mccomm.cell.util.Style.describe(c.colors()),
                owner ? "<yellow>Click · change" : "<dark_gray>Only the owner can change this"), e -> {
            if (!owner) return;
            click(p);
            branding(p, "Branding · " + Items.esc(c.name()), c.name(), c.colors(), false, style -> {
                c.colors(style);
                plugin.store().markDirty();
                for (Tower t : plugin.store().towersOf(c.id())) plugin.towers().refresh(t);
            }, () -> customers(p, c, pg));
        });

        m.set(22, Items.of(Material.BOOK, "<white>Plans <dark_gray>(" + c.plans().size() + ")", "<gray>Below. Click a plan to edit it."));
        List<Plan> plans = new ArrayList<>(c.plans().values());
        var counts = plugin.simulation().planCounts(c);
        if (plans.isEmpty()) m.set(31, Frame.empty("No plans", "Nobody can subscribe until you add one."));
        Frame.page(m, p, plans, pg, Frame.range(27, 43), plan -> {
            List<String> lore = new ArrayList<>(plugin.phone().planLore(plan));
            int sim = counts.getOrDefault(plan.id(), 0);
            if (plan.business()) lore.add("<gold>Business plan");
            lore.add("<gray>Subscribers: <white>" + plugin.billing().subscribers(c, plan) + (sim > 0 ? " <dark_gray>+ " + sim + " customers" : ""));
            if (owner) {
                lore.add("<yellow>Click · edit · <yellow>Right · move later");
                lore.add("<yellow>Shift-left · move earlier · <yellow>Shift-right · delete");
            }
            return Items.of(Material.PAPER, "<b>" + plan.styled(c), lore);
        }, (plan, e) -> {
            if (!owner) return;
            click(p);
            if (e.isShiftClick() && e.isRightClick()) deletePlan(p, c, plan);
            else if (e.isShiftClick() || e.isRightClick()) {
                if (dev.mccomm.cell.util.Reorder.move(c.plans(), plan.id(), e.isShiftClick() ? -1 : 1)) plugin.store().markDirty();
                customers(p, c, pg);
            } else editor(p, c, plan);
        }, n -> customers(p, c, n));
        if (owner) {
            m.set(44, Items.of(Material.WRITABLE_BOOK, "<green>New plan", "<gray>Starts as a copy of the default.", "<yellow>Click · personal plan", "<yellow>Right-click · business plan"), e -> {
                click(p);
                final boolean biz = e.isRightClick();
                plugin.prompt().ask(p, "<aqua>Name for the new " + (biz ? "business " : "") + "plan:", in -> {
                    Plan plan = plugin.billing().addDefaultPlan(c);
                    plan.name(clip(in, 24));
                    if (biz) {
                        plan.segment(dev.mccomm.cell.broadband.Segment.BUSINESS);
                        plan.price(plan.price() * 3); // business lines start pricier
                    }
                    Msg.send(p, "<green>Plan <white><n></white> created. Set its price and limits.", Msg.p("n", plan.name()));
                    editor(p, c, plan);
                });
            });
        }
        Frame.help(m, "Customers", "Customers pick carriers on coverage,", "speed, support and price, and leave",
                "when unhappy or overcharged.", "", "Plans: price, period, band and speed", "caps, data, roaming and phone deals.");
        m.open(p);
    }

    /** Market standing: customers, share, satisfaction and what drives it. */
    public ItemStack marketItem(Carrier c) {
        var mk = plugin.market();
        var sc = mk.score(c);
        int size = Math.max(1, plugin.store().marketSize());
        int[] day = mk.lastDay(c);
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Customers: <white>" + c.simulatedUsers() + " <dark_gray>(" + Math.round(100.0 * c.simulatedUsers() / size) + "% of " + size + ")");
        if (sc == null) return Items.of(Material.PLAYER_HEAD, "<white>Market", lore);
        lore.add("<gray>Satisfaction: " + satColor(sc.satisfaction()) + Math.round(sc.satisfaction()) + "% <gray>· shown to players as "
                + dev.mccomm.cell.market.MarketService.starBar(mk.stars(c)) + " <white>" + dev.mccomm.cell.market.MarketService.starText(mk.stars(c)));
        lore.add("<dark_gray> Service " + pct(sc.quality()) + " · Coverage " + pct(sc.coverage()) + " · Support " + pct(sc.support()) + " · Price " + pct(sc.price()));
        lore.add("<gray>Price: <white>" + money(Math.round(sc.avgPrice())) + "</white>/day <dark_gray>(" + Math.round(sc.priceRatio() * 100) + "% of going rate)");
        if (sc.refused()) lore.add("<red>Too expensive: nobody will join.");
        lore.add("<gray>Leaving ~" + String.format("%.1f", sc.churnPerDay() * 100) + "%/day · Yesterday <green>+" + day[0] + " <red>-" + day[1]);
        return Items.of(Material.PLAYER_HEAD, "<white>Market <b>" + c.styled(), lore);
    }

    private static String pct(double v) {
        return Math.round(v * 100) + "%";
    }

    // ============================================================== operations

    public void operations(Player p, Carrier c) {
        Menu m = page(p, c, OPERATIONS);
        plugin.opsMenu().fill(m, p, c, isOwner(p, c), () -> operations(p, c));
        List<String> team = new ArrayList<>();
        team.add("<gray>Owner: <white>" + name(c.owner()));
        for (UUID id : c.employees()) team.add("<gray>• <white>" + name(id));
        team.add("");
        team.add("<dark_gray>/carrier employee add|remove " + Items.esc(c.name()) + " <player>");
        m.set(40, Items.of(Material.PLAYER_HEAD, "<white>Players with access", team));
        Frame.help(m, "Operations", "Field crews repair worn towers.", "Customer care keeps customers happy.",
                "Network ops keep the network fast.", "", "Each has a full cost that grows with", "your network and customers; choose how",
                "much to fund. Service follows a 3-day", "average of what was really paid.");
        m.open(p);
    }

    // =================================================================== money

    public void money(Player p, Carrier c) {
        Menu m = page(p, c, MONEY);
        long now = System.currentTimeMillis();
        m.set(20, Items.of(Material.HOPPER, "<green>Deposit ores", "<gray>Balance: <white>" + money(c.balance())), e -> {
            click(p);
            plugin.deposit().open(p, c, () -> money(p, c));
        });
        List<String> hist = new ArrayList<>();
        for (LedgerEntry le : c.ledger()) {
            if (hist.size() >= 15) break;
            hist.add(plugin.phone().ledgerLine(le, now));
        }
        if (hist.isEmpty()) hist.add("<dark_gray>Nothing yet");
        m.set(24, Items.of(Material.BOOK, "<white>Ledger", hist));
        plugin.companyMenu().items(m, p, c, () -> money(p, c));
        Frame.help(m, "Money", "Deposit ores into the company wallet.", "Moving money between you and the",
                "company costs " + plugin.settings().transferFeePercent + "%. Valuation is a guide for", "selling; liquidating closes it for good.");
        m.open(p);
    }

    private void deletePlan(Player p, Carrier c, Plan plan) {
        long now = System.currentTimeMillis();
        Long until = confirm.get(p.getUniqueId());
        if (until == null || until < now) {
            confirm.put(p.getUniqueId(), now + CONFIRM_MS);
            Msg.send(p, "<yellow>Shift-right-click again within 5 seconds to delete <white><n></white>. Its <white><s></white> subscriber(s) will lose service until they pick another plan.",
                    Msg.p("n", plan.name()), Msg.p("s", "" + plugin.billing().subscribers(c, plan)));
            return;
        }
        confirm.remove(p.getUniqueId());
        c.plans().remove(plan.id());
        plugin.billing().planDeleted(c, plan);
        Msg.send(p, "<yellow>Deleted plan <white><n></white>.", Msg.p("n", plan.name()));
        customers(p, c, 0);
    }

    public void editor(Player p, Carrier c, Plan plan) {
        Menu m = sub(p, "Edit · " + Items.esc(plan.name()), () -> customers(p, c, 0), "Plans");
        Plan before = plan.copy(); // state before this screen's edit, to tell subscribers what changed
        var st = plugin.settings();
        boolean prepaid = plan.prepaid();
        List<String> hdr = new ArrayList<>(plugin.phone().planLore(plan));
        int subCount = plugin.billing().subscribers(c, plan);
        if (subCount > 0) {
            hdr.add("");
            hdr.add("<gray>" + subCount + " subscriber(s). Changes in their favour");
            hdr.add("<gray>apply now; anything worse applies at");
            hdr.add("<gray>their next renewal. They're notified.");
        }
        m.set(4, Items.of(Material.PAPER, "<b>" + plan.styled(c), hdr));

        int planSubs = plugin.billing().subscribers(c, plan);
        m.set(6, Items.of(plan.business() ? Material.GOLD_BLOCK : Material.OAK_DOOR, "<white>For: " + (plan.business() ? "<gold>Business" : "<green>Personal"),
                "<gray>Personal accounts see personal plans;", "<gray>business accounts see business plans.",
                plan.business() ? "<gray>Business lines keep their speed on a busy band (×" + st.businessPriority + " priority)." : "<gray>Business plans cost more but get priority.",
                planSubs > 0 ? "<dark_gray>Can't change while it has subscribers." : "<yellow>Click to switch"), e -> {
            click(p);
            if (planSubs > 0) {
                Msg.send(p, "<red>This plan has <white><n></white> subscriber(s), so who it is for can't change. Create a new plan instead.", Msg.p("n", "" + planSubs));
                return;
            }
            plan.segment(plan.segment().other());
            saved(p, c, plan, before);
        });

        // ---- row 1: basics
        m.set(9, Items.of(Material.PAINTING, "<white>Style: " + plan.styled(c),
                "<gray>" + (plan.colors().isEmpty() ? "Uses the carrier's style" : dev.mccomm.cell.util.Style.describe(plan.colors())),
                "<yellow>Click to change"), e -> {
            click(p);
            branding(p, "Plan style · " + Items.esc(plan.name()), plan.name(), plan.colors(), true, style -> {
                plan.colors(style);
                plugin.store().markDirty();
            }, () -> editor(p, c, plan));
        });
        m.set(10, Items.of(Material.NAME_TAG, "<white>Name: <aqua>" + Items.esc(plan.name()), "<yellow>Click to rename"), e -> {
            click(p);
            plugin.prompt().ask(p, "<aqua>New name for the plan:", in -> {
                plan.name(clip(in, 24));
                saved(p, c, plan, before);
            });
        });
        String typeName = switch (plan.type()) {
            case POSTPAID -> "Postpaid";
            case FINANCED -> "Financed phone";
            case PREPAID -> "Prepaid";
        };
        m.set(11, Items.of(Material.COMPARATOR, "<white>Type: <aqua>" + typeName,
                "<gray>Postpaid: charged every period.", "<gray>Financed: you buy the subscriber a phone,",
                "<gray>  they repay it with each charge.", "<gray>Prepaid: they top up when they want; no debt.",
                "<yellow>Click to change"), e -> {
            click(p);
            int subs = plugin.billing().subscribers(c, plan);
            if (subs > 0) {
                Msg.send(p, "<red>This plan has <white><n></white> subscriber(s), so its type can't change. Create a new plan instead.",
                        Msg.p("n", "" + subs));
                return;
            }
            Plan.Type[] all = Plan.Type.values();
            plan.type(all[(plan.type().ordinal() + 1) % all.length]);
            if (plan.financed() && plan.financePhone() == null && !st.phones.isEmpty()) plan.financePhone(st.phones.keySet().iterator().next());
            saved(p, c, plan, before);
        });
        boolean onSale = plugin.sales().locked(plan.sale());
        m.set(12, Items.of(Material.GOLD_INGOT, "<white>" + (prepaid ? "Top-up price" : "Price") + ": <gold>" + st.money(plan.price()),
                prepaid ? "<gray>Paid each time they top up." : "<gray>Charged every period.",
                onSale ? "<red>Locked while the plan is on sale." : "<yellow>Click to change"), e -> {
            click(p);
            if (plugin.sales().locked(plan.sale())) {
                Msg.send(p, "<red>The price can't change during a sale. End the sale first.");
                return;
            }
            askLong(p, "<aqua>" + (prepaid ? "Top-up price" : "Price per period") + " in " + st.currencyName + ":", 0, 1_000_000, v -> {
                plan.price(v);
                saved(p, c, plan, before);
            });
        });
        m.set(13, Items.of(Material.CLOCK, "<white>" + (prepaid ? "Valid for" : "Period") + ": <aqua>" + BillingService.period(plan.periodHours()),
                prepaid ? "<gray>How long a top-up lasts (real time)." : "<gray>How often subscribers are charged (real time).",
                "<yellow>Click to change"), e -> {
            click(p);
            askLong(p, "<aqua>Hours (" + st.minPeriodHours + "-" + st.maxPeriodHours + ", e.g. 24 = a day, 168 = a week):",
                    st.minPeriodHours, st.maxPeriodHours, v -> {
                        plan.periodHours(v.intValue());
                        saved(p, c, plan, before);
                    });
        });
        String bandName = plan.maxBand() == null ? "All bands" : st.spec(plan.maxBand()).name();
        m.set(14, Items.of(Material.LIGHTNING_ROD, "<white>Max band: <aqua>" + bandName,
                "<gray>Subscribers can't use bands above this,", "<gray>even with a better phone.",
                "<yellow>Click <gray>next · <yellow>Right-click <gray>previous"), e -> {
            click(p);
            Band[] all = Band.values();
            int idx = plan.maxBand() == null ? all.length : plan.maxBand().ordinal();
            idx = e.isRightClick() ? idx - 1 : idx + 1;
            if (idx < 0) idx = all.length;
            if (idx > all.length) idx = 0;
            plan.maxBand(idx == all.length ? null : all[idx]);
            saved(p, c, plan, before);
        });
        m.set(15, Items.of(Material.SUGAR, "<white>Speed cap: <aqua>" + (plan.speedCapMbps() > 0 ? Msg.speed(plan.speedCapMbps()) : "None"),
                "<gray>Download is limited to this.", "<yellow>Click to change <gray>(0 = no cap)"), e -> {
            click(p);
            askDouble(p, "<aqua>Speed cap in Mbps (0 for no cap):", 0, 100_000, v -> {
                plan.speedCapMbps(v);
                saved(p, c, plan, before);
            });
        });
        m.set(16, Items.of(plan.texts() ? Material.WRITABLE_BOOK : Material.BOOK, "<white>Texts: " + (plan.texts() ? "<green>included" : "<red>not included"),
                "<yellow>Click to toggle"), e -> {
            click(p);
            plan.texts(!plan.texts());
            saved(p, c, plan, before);
        });
        m.set(17, Items.of(plan.calls() ? Material.BELL : Material.NOTE_BLOCK, "<white>Calls: " + (plan.calls() ? "<green>included" : "<red>not included"),
                "<yellow>Click to toggle"), e -> {
            click(p);
            plan.calls(!plan.calls());
            saved(p, c, plan, before);
        });

        // ---- row 2: data
        m.set(19, Items.of(Material.REPEATER, "<white>Data: <aqua>" + (plan.unlimited() ? "Unlimited" : dev.mccomm.cell.Settings.data(plan.dataMB())),
                prepaid ? "<gray>Added with each top-up." : "<gray>Resets every period.",
                "<gray>/internet costs " + st.mbPerCharSend + " MB per character sent;", "<gray>a speedtest uses what it downloads.",
                "<yellow>Click to change <gray>(type 'unlimited' or GB)"), e -> {
            click(p);
            plugin.prompt().ask(p, "<aqua>Data in GB (e.g. 5 or 0.5), or <white>unlimited</white>:", in -> {
                if (in.toLowerCase(Locale.ROOT).startsWith("unl")) plan.dataMB(-1);
                else {
                    Double gb = parseDouble(in.toLowerCase(Locale.ROOT).replace("gb", ""));
                    if (gb == null || gb < 0 || gb > 100_000) {
                        Msg.send(p, "<red>Enter a number of GB or 'unlimited'.");
                        return;
                    }
                    plan.dataMB(Math.round(gb * 1000));
                }
                saved(p, c, plan, before);
            });
        });
        if (!plan.unlimited()) {
            String ood = switch (plan.outOfData()) {
                case THROTTLE -> "Slow down (" + Msg.speed(st.throttleMbps) + ")";
                case BLOCK -> "Block data";
                case OVERAGE -> "Charge per GB";
            };
            m.set(20, Items.of(Material.HOPPER, "<white>Out of data: <aqua>" + ood, "<yellow>Click to change"), e -> {
                click(p);
                Plan.OutOfData[] all = Plan.OutOfData.values();
                plan.outOfData(all[(plan.outOfData().ordinal() + 1) % all.length]);
                saved(p, c, plan, before);
            });
            if (plan.outOfData() == Plan.OutOfData.OVERAGE) {
                m.set(21, Items.of(Material.GOLD_NUGGET, "<white>Overage: <gold>" + st.money(plan.overagePerGb()) + " <white>per GB",
                        "<gray>Taken from their wallet as they use it.", "<yellow>Click to change"), e -> {
                    click(p);
                    askLong(p, "<aqua>Price per extra GB:", 0, 100_000, v -> {
                        plan.overagePerGb(v);
                        saved(p, c, plan, before);
                    });
                });
            }
            m.set(23, Items.of(Material.CHEST, "<white>Data pack: <aqua>" + (plan.packMB() > 0 ? dev.mccomm.cell.Settings.data(plan.packMB()) : "none"),
                    "<gray>An add-on subscribers can buy any time.", "<yellow>Click to change <gray>(0 = no packs)"), e -> {
                click(p);
                askDouble(p, "<aqua>Data pack size in GB (0 for none):", 0, 100_000, v -> {
                    plan.packMB(Math.round(v * 1000));
                    saved(p, c, plan, before);
                });
            });
            if (plan.packMB() > 0) {
                m.set(24, Items.of(Material.GOLD_INGOT, "<white>Pack price: <gold>" + st.money(plan.packPrice()), "<yellow>Click to change"), e -> {
                    click(p);
                    askLong(p, "<aqua>Data pack price:", 0, 1_000_000, v -> {
                        plan.packPrice(v);
                        saved(p, c, plan, before);
                    });
                });
            }
        }

        m.set(25, Items.of(plan.roaming() ? Material.COMPASS : Material.BARRIER, "<white>Roaming: " + (plan.roaming() ? "<green>allowed" : "<red>not allowed"),
                "<gray>Lets subscribers use partner networks", "<gray>where you have no coverage.", "<yellow>Click to toggle"), e -> {
            click(p);
            plan.roaming(!plan.roaming());
            saved(p, c, plan, before);
        });
        if (plan.roaming()) {
            m.set(26, Items.of(Material.GOLD_NUGGET, "<white>Roaming surcharge: <gold>" + (plan.roamingPerGb() > 0 ? st.money(plan.roamingPerGb()) + " <white>per GB" : "<green>free"),
                    "<gray>Charged to subscribers for data used", "<gray>while roaming. You pay partners their rate.", "<yellow>Click to change <gray>(0 = free)"), e -> {
                click(p);
                askLong(p, "<aqua>Roaming surcharge per GB (0 for free roaming):", 0, 100_000, v -> {
                    plan.roamingPerGb(v);
                    saved(p, c, plan, before);
                });
            });
        }

        // ---- row 3: financing
        if (plan.financed()) {
            dev.mccomm.cell.economy.PhoneModel ph = st.phone(plan.financePhone());
            m.set(28, Items.of(ph == null ? Material.BARRIER : ph.icon(), "<white>Phone: <light_purple>" + (ph == null ? "none" : Items.esc(ph.name())),
                    ph == null ? "<red>Pick a phone" : "<gray>Retail " + st.money(ph.price()) + ", paid from your wallet",
                    "<gray>when someone signs up.", "<yellow>Click <gray>next · <yellow>Right-click <gray>previous"), e -> {
                click(p);
                List<String> ids = new ArrayList<>(st.phones.keySet());
                if (ids.isEmpty()) return;
                int idx = Math.max(0, ids.indexOf(plan.financePhone()));
                idx = (idx + (e.isRightClick() ? ids.size() - 1 : 1)) % ids.size();
                plan.financePhone(ids.get(idx));
                saved(p, c, plan, before);
            });
            m.set(29, Items.of(Material.REPEATER, "<white>Instalments: <aqua>" + plan.installments(), "<gray>Number of periods to repay over.", "<yellow>Click to change"), e -> {
                click(p);
                askLong(p, "<aqua>Number of instalments (1-60):", 1, 60, v -> {
                    plan.installments(v.intValue());
                    saved(p, c, plan, before);
                });
            });
            m.set(30, Items.of(Material.EXPERIENCE_BOTTLE, "<white>Interest: <aqua>" + plan.interestPercent() + "%", "<gray>Added to the phone price.", "<yellow>Click to change"), e -> {
                click(p);
                askDouble(p, "<aqua>Interest in percent (0-200):", 0, 200, v -> {
                    plan.interestPercent(v);
                    saved(p, c, plan, before);
                });
            });
            m.set(31, Items.of(Material.EMERALD, "<white>Down payment: <gold>" + st.money(plan.downPayment()), "<gray>Paid up front at sign-up.", "<yellow>Click to change"), e -> {
                click(p);
                askLong(p, "<aqua>Down payment:", 0, 1_000_000, v -> {
                    plan.downPayment(v);
                    saved(p, c, plan, before);
                });
            });
            if (ph != null) {
                long total = plan.financeTotal(ph.price());
                m.set(33, Items.of(Material.BOOK, "<white>Phone deal",
                        "<gray>You pay: <red>" + st.money(ph.price()) + " <gray>at sign-up",
                        "<gray>They repay: <green>" + st.money(total + plan.downPayment()) + " <gray>total",
                        "<gray>  " + st.money(plan.downPayment()) + " down + " + st.money(plan.installment(ph.price())) + " × " + plan.installments(),
                        "<gray>If they default the rest comes due at once",
                        "<gray>and their phone is blacklisted.",
                        c.balance() < ph.price() ? "<gold>Your wallet can't cover a phone right now!" : "<green>Your wallet covers " + (c.balance() / Math.max(1, ph.price())) + " phone(s)"));
            }
        }

        // ---- a temporary sale (next to the price)
        SaleButton.set(plugin, m, 22, p, c, plan.name(), plan.price(), plan.sale(), isOwner(p, c), () -> editor(p, c, plan));

        // ---- order in the plan lists (here and on the phone)
        int pos = dev.mccomm.cell.util.Reorder.indexOf(c.plans(), plan.id());
        int total = c.plans().size();
        m.set(38, Items.of(Material.SPECTRAL_ARROW, "<white>Move earlier", "<gray>Position <white>" + (pos + 1) + "<gray> of <white>" + total,
                "<gray>Plans show in this order to subscribers.", "<yellow>Click · up one · <yellow>Shift-click · to the top"), e -> {
            click(p);
            if (dev.mccomm.cell.util.Reorder.move(c.plans(), plan.id(), e.isShiftClick() ? -total : -1)) plugin.store().markDirty();
            editor(p, c, plan);
        });
        m.set(42, Items.of(Material.ARROW, "<white>Move later", "<gray>Position <white>" + (pos + 1) + "<gray> of <white>" + total,
                "<gray>Plans show in this order to subscribers.", "<yellow>Click · down one · <yellow>Shift-click · to the bottom"), e -> {
            click(p);
            if (dev.mccomm.cell.util.Reorder.move(c.plans(), plan.id(), e.isShiftClick() ? total : 1)) plugin.store().markDirty();
            editor(p, c, plan);
        });

        Frame.help(m, "Editing a plan", "Changes in subscribers' favour apply", "now; anything worse applies at their",
                "next renewal (they're told).", "", "Simulated customers pick plans evenly,", "so every plan counts towards your price.");
        m.open(p);
    }

    private void askLong(Player p, String q, long min, long max, java.util.function.Consumer<Long> ok) {
        plugin.prompt().ask(p, q, in -> {
            Long v = parseLong(in);
            if (v == null || v < min || v > max) {
                Msg.send(p, "<red>Enter a whole number from " + min + " to " + max + ".");
                return;
            }
            ok.accept(v);
        });
    }

    private void askDouble(Player p, String q, double min, double max, java.util.function.Consumer<Double> ok) {
        plugin.prompt().ask(p, q, in -> {
            Double v = parseDouble(in);
            if (v == null || v < min || v > max) {
                Msg.send(p, "<red>Enter a number from " + (long) min + " to " + (long) max + ".");
                return;
            }
            ok.accept(v);
        });
    }

    private void saved(Player p, Carrier c, Plan plan, Plan before) {
        plugin.store().markDirty();
        plugin.billing().planEdited(c, before, plan);
        editor(p, c, plan);
    }

    // ================================================================ partners

    private String mvnoNetworkName(Carrier c) {
        var deal = plugin.partners().mvnoHost(c);
        return deal == null ? "<red>none" : deal.host().styled();
    }

    public void partners(Player p, Carrier c) {
        boolean owner = isOwner(p, c);
        Menu m = page(p, c, PARTNERS);
        long now = System.currentTimeMillis();
        Frame.help(m, "Partners", c.mvno() ? "MVNO: you run on a host network." : "You own towers.",
                "Roaming: your subscribers use partners'", "towers where you have no coverage.",
                c.mvno() ? "Host: its towers are your home network." : "MVNOs: virtual carriers renting your network.",
                "", "Top rows: agreements. Row 4: offers.");

        // Row 1-2: active agreements (as host, then as partner)
        int slot = 9;
        for (Agreement a : new ArrayList<>(c.agreements().values())) {
            if (slot > 26) break;
            Carrier partner = plugin.store().carrier(a.partnerId());
            if (partner == null) continue;
            PartnerService.Deal deal = new PartnerService.Deal(c, a);
            List<String> lore = new ArrayList<>(terms(a));
            lore.add(a.kind() == Agreement.Kind.MVNO ? "<gray>They pay you a revenue share." : "<gray>They pay you per GB their roamers use.");
            if (a.endsAt() != 0) lore.add("<gold>Ends in " + BillingService.until(a.endsAt() - now));
            if (owner) {
                lore.add("");
                lore.add("<yellow>Click <gray>edit terms · <yellow>Shift-right <gray>end");
            }
            m.set(slot++, Items.of(a.kind() == Agreement.Kind.MVNO ? Material.ENDER_EYE : Material.COMPASS,
                    "<white>" + (a.kind() == Agreement.Kind.MVNO ? "Hosting MVNO " : "Roaming partner ") + partner.styled(), lore), e -> {
                if (!owner) return;
                click(p);
                if (e.isShiftClick() && e.isRightClick()) confirmEnd(p, c, deal);
                else terms(p, c, a, () -> partners(p, c));
            });
        }
        for (PartnerService.Deal deal : plugin.partners().asPartner(c)) {
            if (slot > 26) break;
            Agreement a = deal.agreement();
            List<String> lore = new ArrayList<>(terms(a));
            lore.add(a.kind() == Agreement.Kind.MVNO ? "<gray>Their towers are your home network." : "<gray>Your subscribers roam here when you have no coverage.");
            if (a.endsAt() != 0) lore.add("<gold>Ends in " + BillingService.until(a.endsAt() - now));
            if (owner) {
                lore.add("");
                lore.add("<yellow>Shift-right <gray>end");
            }
            m.set(slot++, Items.of(a.kind() == Agreement.Kind.MVNO ? Material.BEACON : Material.MAP,
                    "<white>" + (a.kind() == Agreement.Kind.MVNO ? "Your host " : "Roaming on ") + deal.host().styled(), lore), e -> {
                if (!owner || !(e.isShiftClick() && e.isRightClick())) return;
                click(p);
                confirmEnd(p, c, deal);
            });
        }
        if (slot == 9) m.set(13, Frame.empty("No agreements yet", "Offer roaming or MVNO hosting below."));

        // Row 3: offers received and offers sent
        slot = 27;
        for (PartnerService.Deal offer : plugin.partners().offersTo(c)) {
            if (slot > 35) break;
            List<String> lore = new ArrayList<>(terms(offer.agreement()));
            if (owner) {
                lore.add("");
                lore.add("<yellow>Click <gray>accept · <yellow>Shift-right <gray>decline");
            }
            m.set(slot++, Items.glow(Items.of(Material.WRITABLE_BOOK, "<yellow>Offer from " + offer.host().styled()
                    + " <gray>(" + (offer.agreement().kind() == Agreement.Kind.MVNO ? "MVNO hosting" : "roaming") + ")", lore)), e -> {
                if (!owner) return;
                click(p);
                if (e.isShiftClick() && e.isRightClick()) plugin.partners().decline(p, offer);
                else plugin.partners().accept(p, c, offer);
                partners(p, c);
            });
        }
        for (Agreement a : new ArrayList<>(c.offers().values())) {
            if (slot > 35) break;
            Carrier partner = plugin.store().carrier(a.partnerId());
            if (partner == null) continue;
            List<String> lore = new ArrayList<>(terms(a));
            lore.add("<gray>Waiting for them to accept.");
            if (owner) {
                lore.add("");
                lore.add("<yellow>Click <gray>edit terms · <yellow>Shift-right <gray>withdraw");
            }
            m.set(slot++, Items.of(Material.PAPER, "<gray>Offer sent to " + partner.styled()
                    + " <gray>(" + (a.kind() == Agreement.Kind.MVNO ? "MVNO hosting" : "roaming") + ")", lore), e -> {
                if (!owner) return;
                click(p);
                if (e.isShiftClick() && e.isRightClick()) {
                    c.offers().remove(a.key());
                    plugin.store().markDirty();
                    partners(p, c);
                } else terms(p, c, a, () -> partners(p, c));
            });
        }

        if (owner && !c.mvno()) {
            m.set(39, Items.of(Material.COMPASS, "<green>Offer roaming", "<gray>Let another carrier's subscribers", "<gray>use your towers, for a fee per GB."), e -> {
                click(p);
                plugin.prompt().ask(p, "<aqua>Offer roaming to which carrier?", in -> {
                    Carrier other = plugin.store().carrier(in);
                    if (other == null) {
                        Msg.send(p, "<red>No carrier named <white><n></white>.", Msg.p("n", in));
                        return;
                    }
                    Agreement a = plugin.partners().offer(p, c, Agreement.Kind.ROAMING, other);
                    if (a != null) {
                        Msg.send(p, "<green>Offer sent to <white><n></white>. Adjust the terms until they accept.", Msg.p("n", other.name()));
                        terms(p, c, a, () -> partners(p, c));
                    }
                });
            });
            m.set(41, Items.of(Material.ENDER_EYE, "<green>Offer MVNO hosting", "<gray>Let a virtual carrier run on your", "<gray>network for a share of its revenue."), e -> {
                click(p);
                plugin.prompt().ask(p, "<aqua>Host which MVNO?", in -> {
                    Carrier other = plugin.store().carrier(in);
                    if (other == null) {
                        Msg.send(p, "<red>No carrier named <white><n></white>.", Msg.p("n", in));
                        return;
                    }
                    Agreement a = plugin.partners().offer(p, c, Agreement.Kind.MVNO, other);
                    if (a != null) {
                        Msg.send(p, "<green>Offer sent to <white><n></white>. Adjust the terms until they accept.", Msg.p("n", other.name()));
                        terms(p, c, a, () -> partners(p, c));
                    }
                });
            });
        }
        m.open(p);
    }

    private List<String> terms(Agreement a) {
        var st = plugin.settings();
        List<String> lore = new ArrayList<>();
        if (a.kind() == Agreement.Kind.ROAMING) lore.add("<gray>Rate: <white>" + st.money(a.perGb()) + " per GB");
        else lore.add("<gray>Revenue share: <white>" + Math.round(a.sharePercent()) + "%");
        lore.add("<gray>Max band: <white>" + (a.maxBand() == null ? "All" : st.spec(a.maxBand()).name()));
        lore.add("<gray>Priority: <white>" + (a.deprioritize() ? "below the host's customers" : "equal"));
        return lore;
    }

    private void confirmEnd(Player p, Carrier c, PartnerService.Deal deal) {
        long now = System.currentTimeMillis();
        Long until = confirm.get(p.getUniqueId());
        if (until == null || until < now) {
            confirm.put(p.getUniqueId(), now + CONFIRM_MS);
            Msg.send(p, "<yellow>Shift-right-click again within 5 seconds to end this agreement. It keeps working for "
                    + plugin.settings().noticeHours + " h.");
            return;
        }
        confirm.remove(p.getUniqueId());
        plugin.partners().end(p, deal);
        partners(p, c);
    }

    /** Host-side terms editor (works on an offer or an active agreement). */
    public void terms(Player p, Carrier host, Agreement a, Runnable back) {
        Carrier partner = plugin.store().carrier(a.partnerId());
        Menu m = sub(p, "Terms · " + Items.esc(partner == null ? "?" : partner.name()), back, "Partners");
        var st = plugin.settings();
        m.set(4, Items.of(a.kind() == Agreement.Kind.MVNO ? Material.ENDER_EYE : Material.COMPASS,
                "<white>" + (a.kind() == Agreement.Kind.MVNO ? "MVNO hosting for " : "Roaming for ") + (partner == null ? "?" : partner.styled()), terms(a)));
        if (a.kind() == Agreement.Kind.ROAMING) {
            m.set(20, Items.of(Material.GOLD_INGOT, "<white>Rate: <gold>" + st.money(a.perGb()) + " <white>per GB",
                    "<gray>Paid by their carrier for data", "<gray>their roamers use on your towers.", "<yellow>Click to change"), e -> {
                click(p);
                askLong(p, "<aqua>Wholesale price per GB:", 0, 100_000, v -> {
                    a.perGb(v);
                    plugin.store().markDirty();
                    terms(p, host, a, back);
                });
            });
        } else {
            m.set(20, Items.of(Material.GOLD_INGOT, "<white>Revenue share: <gold>" + Math.round(a.sharePercent()) + "%",
                    "<gray>Your cut of every plan payment,", "<gray>top-up, data pack and overage they collect.", "<yellow>Click to change"), e -> {
                click(p);
                askDouble(p, "<aqua>Revenue share in percent (0-90):", 0, 90, v -> {
                    a.sharePercent(v);
                    plugin.store().markDirty();
                    terms(p, host, a, back);
                });
            });
        }
        m.set(22, Items.of(Material.LIGHTNING_ROD, "<white>Max band: <aqua>" + (a.maxBand() == null ? "All bands" : st.spec(a.maxBand()).name()),
                "<gray>Their users can't go above this on your towers.", "<yellow>Click <gray>next · <yellow>Right-click <gray>previous"), e -> {
            click(p);
            Band[] all = Band.values();
            int idx = a.maxBand() == null ? all.length : a.maxBand().ordinal();
            idx = e.isRightClick() ? idx - 1 : idx + 1;
            if (idx < 0) idx = all.length;
            if (idx > all.length) idx = 0;
            a.maxBand(idx == all.length ? null : all[idx]);
            plugin.store().markDirty();
            terms(p, host, a, back);
        });
        m.set(24, Items.of(a.deprioritize() ? Material.HOPPER : Material.CHEST, "<white>Priority: <aqua>" + (a.deprioritize() ? "Below your customers" : "Equal"),
                "<gray>When a band is busy, deprioritized users", "<gray>get " + Math.round(st.deprioritizedSpeed * 100) + "% of the speed.", "<yellow>Click to toggle"), e -> {
            click(p);
            a.deprioritize(!a.deprioritize());
            plugin.store().markDirty();
            terms(p, host, a, back);
        });
        m.open(p);
    }

    // ================================================================ branding

    /**
     * Pick a solid colour, a preset gradient or type a custom style. {@code allowReset} adds a
     * "use carrier style" option (for plans, where an empty style means inherit).
     */
    public void branding(Player p, String title, String sample, List<net.kyori.adventure.text.format.TextColor> current,
                         boolean allowReset, java.util.function.Consumer<List<net.kyori.adventure.text.format.TextColor>> apply, Runnable back) {
        Menu m = sub(p, title, back, "Back");
        m.set(4, Items.glow(Items.of(Material.NAME_TAG, "<b>" + dev.mccomm.cell.util.Style.mm(current, sample),
                "<gray>Current: <white>" + dev.mccomm.cell.util.Style.describe(current))));
        int[] solidSlots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
        int i = 0;
        for (dev.mccomm.cell.util.Style.Solid sol : dev.mccomm.cell.util.Style.SOLIDS) {
            if (i >= solidSlots.length) break;
            List<net.kyori.adventure.text.format.TextColor> style = List.of(sol.color());
            m.set(solidSlots[i++], Items.of(sol.swatch(), "<b>" + dev.mccomm.cell.util.Style.mm(style, sample),
                    "<gray>" + sol.name() + " · solid", "<yellow>Click to use"), e -> {
                click(p);
                apply.accept(style);
                branding(p, title, sample, style, allowReset, apply, back);
            });
        }
        int[] gradSlots = {28, 29, 30, 31, 32, 33, 34, 37, 38, 39};
        i = 0;
        for (var g : dev.mccomm.cell.util.Style.GRADIENTS.entrySet()) {
            if (i >= gradSlots.length) break;
            List<net.kyori.adventure.text.format.TextColor> style = g.getValue();
            m.set(gradSlots[i++], Items.of(Material.FIREWORK_STAR, "<b>" + dev.mccomm.cell.util.Style.mm(style, sample),
                    "<gray>" + g.getKey() + " · gradient", "<yellow>Click to use"), e -> {
                click(p);
                apply.accept(style);
                branding(p, title, sample, style, allowReset, apply, back);
            });
        }
        m.set(41, Items.of(Material.WRITABLE_BOOK, "<aqua>Custom style",
                "<gray>Type 1 colour for solid, 2-4 for a gradient:", "<white>#33ccff", "<white>#ff5f6d #ffc371", "<white>red gold aqua",
                "<yellow>Click to type"), e -> {
            click(p);
            plugin.prompt().ask(p, "<aqua>Colours (hex or names), 1 for solid or 2-4 for a gradient:", in -> {
                List<net.kyori.adventure.text.format.TextColor> style = dev.mccomm.cell.util.Style.parse(in);
                if (style == null) {
                    Msg.send(p, "<red>Couldn't read that. Try <white>#ff5f6d #ffc371</white> or <white>aqua</white>.");
                    branding(p, title, sample, current, allowReset, apply, back);
                    return;
                }
                apply.accept(style);
                Msg.send(p, "<green>Style set: <b>" + dev.mccomm.cell.util.Style.mm(style, sample));
                branding(p, title, sample, style, allowReset, apply, back);
            });
        });
        if (allowReset) {
            m.set(43, Items.of(Material.BARRIER, "<white>Use the carrier's style", "<gray>Clears this plan's own colours."), e -> {
                click(p);
                apply.accept(List.of());
                back.run();
            });
        }
        m.open(p);
    }

    // ================================================================= helpers

    private static String clip(String s, int n) {
        return s.length() > n ? s.substring(0, n) : s;
    }

    private static Long parseLong(String s) {
        try {
            return Long.parseLong(s.replace(",", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double parseDouble(String s) {
        try {
            return Double.parseDouble(s.replace(",", "").trim().toLowerCase(Locale.ROOT).replace("mbps", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String name(UUID id) {
        String n = Bukkit.getOfflinePlayer(id).getName();
        return n == null ? id.toString().substring(0, 8) : n;
    }
}
