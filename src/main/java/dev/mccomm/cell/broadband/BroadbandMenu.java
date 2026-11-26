package dev.mccomm.cell.broadband;

import dev.mccomm.cell.CellPlugin;
import dev.mccomm.cell.broadband.BroadbandService.Link;
import dev.mccomm.cell.broadband.BroadbandService.Quote;
import dev.mccomm.cell.broadband.BroadbandSettings.TechSpec;
import dev.mccomm.cell.economy.BillingService;
import dev.mccomm.cell.gui.Frame;
import dev.mccomm.cell.gui.Items;
import dev.mccomm.cell.gui.Menu;
import dev.mccomm.cell.model.LedgerEntry;
import dev.mccomm.cell.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** The screens of home internet: the ISP management menu, the router, the computer and nodes. */
public final class BroadbandMenu {
    private static final String OVERVIEW = "overview", PLANS = "plans", NETWORK = "network", CUSTOMERS = "customers";
    private static final String STATUS = "status", PROVIDERS = "providers";
    private static final int[] LIST3 = Frame.rows(1, 3);
    private static final int[] LIST4 = Frame.rows(1, 4);

    private final CellPlugin plugin;

    public BroadbandMenu(CellPlugin plugin) {
        this.plugin = plugin;
    }

    private BroadbandService bb() {
        return plugin.broadband();
    }

    private BroadbandStore store() {
        return plugin.broadband().store();
    }

    private String money(long v) {
        return plugin.settings().money(v);
    }

    private TechSpec spec(Technology t) {
        return bb().cfg().spec(t);
    }

    private static String nameOf(UUID id) {
        String n = id == null ? null : Bukkit.getOfflinePlayer(id).getName();
        return n == null ? "?" : n;
    }

    private boolean admin(Player p) {
        return p.hasPermission("mccomm.admin");
    }

    private void confirm(Player p, String title, List<String> lore, Runnable yes, Runnable no) {
        Menu m = Frame.create(p, "Are you sure?", "bb-confirm");
        m.set(13, Items.of(Material.PAPER, title, lore));
        m.set(29, Items.of(Material.LIME_CONCRETE, "<green>Yes"), e -> {
            Frame.click(p);
            yes.run();
        });
        m.set(33, Items.of(Material.RED_CONCRETE, "<red>No, go back"), e -> {
            Frame.click(p);
            no.run();
        });
        m.open(p);
    }

    private void askNumber(Player p, String question, double min, java.util.function.DoubleConsumer ok) {
        plugin.prompt().ask(p, question, in -> {
            try {
                double v = Double.parseDouble(in.replace(",", "").trim());
                if (v < min || Double.isNaN(v) || Double.isInfinite(v)) throw new NumberFormatException();
                ok.accept(v);
            } catch (NumberFormatException ex) {
                Msg.send(p, "<red>Enter a number of at least <min>.", Msg.p("min", "" + (min == Math.floor(min) ? "" + (long) min : "" + min)));
            }
        });
    }

    private static String dataText(InternetPlan pl) {
        return pl.unlimited() ? "Unlimited" : dev.mccomm.cell.Settings.data(pl.dataMB());
    }

    // ================================================================== ISP menu

    public void isp(Player p, Isp isp) {
        isp(p, isp, OVERVIEW, 0);
    }

    public void isp(Player p, Isp isp, String tab, int page) {
        if (store().isp(isp.id()) != isp) {
            Msg.send(p, "<red>That ISP no longer exists.");
            return;
        }
        if (!bb().canManage(p, isp)) {
            Msg.send(p, "<red>You don't work for <white><isp></white>.", Msg.p("isp", isp.name()));
            return;
        }
        Menu m = Frame.create(p, "ISP · " + isp.name(), "bb-isp-" + tab);
        Frame.tabs(m, p, List.of(
                new Frame.Tab(OVERVIEW, Material.COMPARATOR, "Overview"),
                new Frame.Tab(PLANS, Material.PAPER, "Plans"),
                new Frame.Tab(NETWORK, Material.LIGHTNING_ROD, isp.carrierBacked() ? "Towers" : "Network"),
                new Frame.Tab(CUSTOMERS, Material.PLAYER_HEAD, "Customers")), tab, k -> isp(p, isp, k, 0));
        switch (tab) {
            case PLANS -> plansTab(m, p, isp, page);
            case NETWORK -> networkTab(m, p, isp, page);
            case CUSTOMERS -> customersTab(m, p, isp, page);
            default -> overviewTab(m, p, isp);
        }
        m.open(p);
    }

    private void overviewTab(Menu m, Player p, Isp isp) {
        List<Node> nodes = store().nodesOf(isp.id());
        long due = bb().upkeepDue(isp);
        List<String> info = new ArrayList<>();
        info.add("<gray>Owner: <white>" + Items.esc(nameOf(isp.owner())));
        if (isp.carrierBacked()) {
            var c = plugin.store().carrier(isp.carrierId());
            info.add("<gray>5G Home on <white>" + Items.esc(c == null ? "?" : c.name()) + "<gray>'s towers");
        }
        info.add("<gray>Rating: " + bb().ratingLore(isp));
        info.add("<gray>Balance: <white>" + money(isp.balance()));
        info.add("<gray>Nodes: <white>" + nodes.size() + " <gray>· Customers: <white>" + bb().customers(isp)
                + (isp.simCustomers() > 0 ? " <gray>+ <white>" + isp.simCustomers() + " <gray>households" : ""));
        if (!isp.carrierBacked()) {
            info.add("<gray>Upkeep: <white>" + money(due) + " <gray>every " + BillingService.period(bb().cfg().upkeepPeriodHours()));
            if (isp.nextUpkeep() > 0) info.add("<gray>Next upkeep: <white>in " + BillingService.until(isp.nextUpkeep() - System.currentTimeMillis()));
            info.add(isp.paidUp() ? "<green>Network up" : "<red>Network down: deposit to pay the upkeep");
        }
        m.set(13, Items.of(Material.COMPARATOR, isp.styled(), info));

        if (!isp.carrierBacked()) {
            double cap = bb().transitCapacity(isp), demand = bb().transitDemand(isp);
            List<String> tl = new ArrayList<>();
            tl.add("<gray>Tier: <white>" + isp.transitTier() + "<gray>/" + bb().cfg().transit().maxTier());
            tl.add("<gray>Backbone: <white>" + Msg.speed(cap) + " <gray>· demand <white>" + Msg.speed(demand));
            tl.add(demand > cap ? "<red>Congested: every customer is slowed to " + Math.round(cap / demand * 100) + "%" : "<green>Keeping up");
            if (isp.transitTier() < bb().cfg().transit().maxTier()) {
                tl.add("<gray>Next tier: <white>" + (admin(p) ? "free (admin)" : money(bb().cfg().transit().upgradeCost() * isp.transitTier())) + " <gray>+ upkeep");
                tl.add("<yellow>Click to buy more transit");
            }
            m.set(20, Items.of(Material.IRON_BARS, "<aqua>Transit", tl), e -> {
                Frame.click(p);
                bb().upgradeTransit(p, isp);
                isp(p, isp, OVERVIEW, 0);
            });
        }
        List<String> dl = new ArrayList<>();
        dl.add("<gray>Peers (share backbone):");
        if (isp.peers().isEmpty()) dl.add("<dark_gray>  none");
        for (String id : isp.peers()) {
            Isp o = store().isp(id);
            dl.add("<gray>  · " + (o == null ? id : o.styled()));
        }
        for (String id : isp.peerOffers()) {
            Isp o = store().isp(id);
            dl.add("<yellow>  offer from " + (o == null ? id : o.styled()));
        }
        dl.add("<gray>Bundles with carriers:");
        if (isp.bundles().isEmpty()) dl.add("<dark_gray>  none");
        for (Bundle b : isp.bundles().values()) {
            var c = plugin.store().carrier(b.carrierId());
            dl.add("<gray>  · " + (c == null ? b.carrierId() : c.styled()) + " <white>" + b.ispDiscount() + "%/" + b.carrierDiscount() + "%"
                    + (b.accepted() ? "" : " <yellow>(pending)"));
        }
        dl.add("<dark_gray>/isp peer ... · /isp bundle ...");
        m.set(22, Items.of(Material.LEAD, "<white>Peering and bundles", dl));

        m.set(29, Items.of(Material.GOLD_INGOT, "<gold>Deposit", "<gray>Move credits from your wallet into the ISP.",
                "<gray>It pays for nodes, upgrades and upkeep."), e -> {
            Frame.click(p);
            askNumber(p, "<aqua>Deposit how many credits into <white>" + Items.esc(isp.name()) + "</white>?", 1, v -> {
                bb().deposit(p, isp, (long) v);
                isp(p, isp, OVERVIEW, 0);
            });
        });
        boolean owner = isp.owner().equals(p.getUniqueId()) || admin(p);
        m.set(31, Items.of(Material.EMERALD, owner ? "<green>Withdraw" : "<dark_gray>Withdraw",
                owner ? List.of("<gray>Take profit out to your wallet.", "<gray>The next upkeep bill stays in.") : List.of("<gray>Only the owner can withdraw.")),
                owner ? e -> {
                    Frame.click(p);
                    askNumber(p, "<aqua>Withdraw how many credits from <white>" + Items.esc(isp.name()) + "</white>?", 1, v -> {
                        bb().withdraw(p, isp, (long) v);
                        isp(p, isp, OVERVIEW, 0);
                    });
                } : null);
        List<String> led = new ArrayList<>();
        for (LedgerEntry le : isp.ledger().stream().limit(8).toList()) {
            led.add((le.amount() >= 0 ? "<green>+" : "<red>") + money(le.amount()) + " <gray>" + Items.esc(le.note()));
        }
        if (led.isEmpty()) led.add("<dark_gray>No transactions yet.");
        m.set(33, Items.of(Material.BOOK, "<white>Ledger", led));
        if (owner) {
            m.set(40, Items.of(Material.TNT, "<red>Shut down this ISP", "<gray>Takes every node down and cancels",
                    "<gray>every customer's plan. No refunds."), e -> {
                Frame.click(p);
                confirm(p, "<red>Shut down " + Items.esc(isp.name()) + "?",
                        List.of("<gray>Its " + nodes.size() + " node(s) come down and its " + bb().customers(isp) + " customer(s) lose service.",
                                "<gray>The ISP's balance is lost."),
                        () -> {
                            bb().deleteIsp(isp);
                            Msg.send(p, "<yellow>ISP <white><isp></white> shut down.", Msg.p("isp", isp.name()));
                            p.closeInventory();
                        }, () -> isp(p, isp, OVERVIEW, 0));
            });
        }
        Frame.help(m, "ISP", "Build nodes on wall signs with /isp node install,",
                "sell plans, and keep the balance above the upkeep.", "Customers connect to your nearest healthy node of the plan's technology.",
                "Better speed, uptime, value and coverage raise your rating.");
    }

    private void plansTab(Menu m, Player p, Isp isp, int page) {
        List<InternetPlan> plans = new ArrayList<>(isp.plans().values());
        if (plans.isEmpty()) m.set(22, Frame.empty("No plans", "Add one with the button below."));
        Frame.page(m, p, plans, page, LIST3, pl -> planItem(isp, pl), (pl, e) -> {
            Frame.click(p);
            if (e.isShiftClick() ? e.isLeftClick() : e.isRightClick()) { // shift-left earlier, right later
                if (dev.mccomm.cell.util.Reorder.move(isp.plans(), pl.id(), e.isShiftClick() ? -1 : 1)) store().markDirty();
                isp(p, isp, PLANS, page);
                return;
            }
            if (e.isShiftClick()) { // shift-right: quick delete, with a confirmation
                long subs = store().routersOfIsp(isp.id()).stream().filter(r -> pl.id().equals(r.planId())).count();
                confirm(p, "<red>Delete " + Items.esc(pl.name()) + "?", List.of("<gray>" + subs + " subscriber(s) will be cancelled, no refunds."), () -> {
                    bb().planDeleted(isp, pl);
                    isp(p, isp, PLANS, 0);
                }, () -> isp(p, isp, PLANS, 0));
                return;
            }
            plan(p, isp, pl);
        }, pg -> isp(p, isp, PLANS, pg));
        m.set(40, Items.of(Material.WRITABLE_BOOK, "<green>New plan", "<gray>Pick a technology, then name it."), e -> {
            Frame.click(p);
            if (isp.plans().size() >= bb().cfg().maxPlansPerIsp()) {
                Msg.send(p, "<red>An ISP can have at most <n> plans.", Msg.p("n", "" + bb().cfg().maxPlansPerIsp()));
                return;
            }
            newPlan(p, isp);
        });
    }

    private ItemStack planItem(Isp isp, InternetPlan pl) {
        return planItem(isp, pl, true);
    }

    private ItemStack planItem(Isp isp, InternetPlan pl, boolean hints) {
        TechSpec s = spec(pl.tech());
        long subs = store().routersOfIsp(isp.id()).stream().filter(r -> pl.id().equals(r.planId())).count();
        List<String> lore = new ArrayList<>(List.of(
                "<gray>Technology: <white>" + s.name(),
                "<gray>Price: <white>" + money(pl.price()) + " <gray>per " + BillingService.period(pl.periodHours()),
                "<gray>Speed cap: <white>" + Msg.speed(pl.speedCapMbps()),
                "<gray>Data: <white>" + dataText(pl) + (pl.unlimited() ? "" : " <gray>then " + Msg.speed(bb().cfg().throttleMbps())),
                "<gray>Install fee: <white>" + money(pl.installFee()),
                "<gray>Subscribers: <white>" + subs));
        if (hints) {
            lore.add("<yellow>Click · edit · <yellow>Right · move later");
            lore.add("<yellow>Shift-left · move earlier · <yellow>Shift-right · delete");
        }
        return Items.of(s.icon(), "<white>" + Items.esc(pl.name()), lore);
    }

    private void newPlan(Player p, Isp isp) {
        Menu m = Frame.create(p, "New plan · pick a technology", "bb-newplan");
        int[] slots = {20, 22, 24, 26, 22};
        Technology[] techs = bb().techsFor(isp);
        int i = 0;
        for (Technology t : techs) {
            TechSpec s = spec(t);
            int slot = techs.length == 1 ? 22 : slots[i++];
            m.set(slot, Items.of(s.icon(), "<white>" + s.name(), Items.wrap(s.blurb(), 34, "<gray>")), e -> {
                Frame.click(p);
                plugin.prompt().ask(p, "<aqua>Name for the new " + s.name() + " plan?", name -> {
                    if (name.length() > 24) {
                        Msg.send(p, "<red>Plan names are at most 24 characters.");
                        return;
                    }
                    if (isp.plans().size() >= bb().cfg().maxPlansPerIsp()) return;
                    InternetPlan pl = new InternetPlan(bb().newPlanId(isp, name), name, t, s.starterPrice(), 24,
                            Math.max(1, Math.round(s.maxMbps() * 0.5)), s.installFee());
                    isp.plans().put(pl.id(), pl);
                    store().markDirty();
                    plan(p, isp, pl);
                });
            });
        }
        Frame.back(m, p, "Plans", () -> isp(p, isp, PLANS, 0));
        m.open(p);
    }

    public void plan(Player p, Isp isp, InternetPlan pl) {
        if (isp.plans().get(pl.id()) != pl) {
            isp(p, isp, PLANS, 0);
            return;
        }
        TechSpec s = spec(pl.tech());
        Menu m = Frame.create(p, "Plan · " + Items.esc(pl.name()), "bb-plan");
        m.set(13, planItem(isp, pl, false));
        Runnable again = () -> plan(p, isp, pl);
        m.set(20, Items.of(Material.GOLD_INGOT, "<gold>Price", "<gray>Now: <white>" + money(pl.price()),
                "<gray>Existing customers keep their old price", "<gray>until their next renewal.", "<yellow>Click to change"),
                e -> askNumber(p, "<aqua>Price per period, in credits?", 0, v -> {
                    pl.price((long) v);
                    store().markDirty();
                    again.run();
                }));
        m.set(22, Items.of(Material.CLOCK, "<aqua>Billing period", "<gray>Now: <white>" + BillingService.period(pl.periodHours()),
                "<yellow>Click to change"), e -> askNumber(p, "<aqua>Hours per billing period (" + bb().cfg().minPeriodHours() + "-" + bb().cfg().maxPeriodHours() + ")?",
                bb().cfg().minPeriodHours(), v -> {
                    pl.periodHours((int) Math.min(bb().cfg().maxPeriodHours(), v));
                    store().markDirty();
                    again.run();
                }));
        m.set(24, Items.of(Material.REDSTONE, "<red>Speed cap", "<gray>Now: <white>" + Msg.speed(pl.speedCapMbps()),
                "<gray>The line itself tops out at " + Msg.speed(s.maxMbps()) + ".", "<yellow>Click to change"),
                e -> askNumber(p, "<aqua>Speed cap in Mbps?", 0.1, v -> {
                    pl.speedCapMbps(v);
                    store().markDirty();
                    again.run();
                }));
        m.set(29, Items.of(Material.IRON_PICKAXE, "<yellow>Install fee", "<gray>Now: <white>" + money(pl.installFee()),
                "<gray>Paid once when someone signs up.", "<gray>(Cabling by distance is charged on top.)", "<yellow>Click to change"),
                e -> askNumber(p, "<aqua>Install fee in credits?", 0, v -> {
                    pl.installFee((long) v);
                    store().markDirty();
                    again.run();
                }));
        m.set(31, Items.of(Material.NAME_TAG, "<white>Rename", "<yellow>Click to change"), e -> plugin.prompt().ask(p, "<aqua>New plan name?", name -> {
            if (name.length() > 24) {
                Msg.send(p, "<red>Plan names are at most 24 characters.");
                return;
            }
            pl.name(name);
            store().markDirty();
            again.run();
        }));
        long subs = store().routersOfIsp(isp.id()).stream().filter(r -> pl.id().equals(r.planId())).count();
        m.set(33, Items.of(Material.BARRIER, "<red>Delete plan", "<gray>" + subs + " subscriber(s) will lose service.", "<yellow>Click to delete"), e -> {
            Frame.click(p);
            confirm(p, "<red>Delete " + Items.esc(pl.name()) + "?", List.of("<gray>" + subs + " subscriber(s) will be cancelled, no refunds."), () -> {
                bb().planDeleted(isp, pl);
                isp(p, isp, PLANS, 0);
            }, again);
        });
        m.set(38, Items.of(Material.WATER_BUCKET, "<aqua>Data allowance", "<gray>Now: <white>" + dataText(pl),
                "<gray>When it runs out the line drops to", "<gray>" + Msg.speed(bb().cfg().throttleMbps()) + " until the next renewal.", "<yellow>Click to change"),
                e -> askNumber(p, "<aqua>Data allowance per period in GB? (0 = unlimited)", 0, v -> {
                    pl.dataMB(v <= 0 ? -1 : Math.round(v * 1000));
                    store().markDirty();
                    again.run();
                }));
        int pos = dev.mccomm.cell.util.Reorder.indexOf(isp.plans(), pl.id());
        int total = isp.plans().size();
        m.set(40, Items.of(Material.SPECTRAL_ARROW, "<white>Move earlier", "<gray>Position <white>" + (pos + 1) + "<gray> of <white>" + total,
                "<gray>Plans show in this order to customers.", "<yellow>Click · up one · <yellow>Shift-click · to the top"), e -> {
            Frame.click(p);
            if (dev.mccomm.cell.util.Reorder.move(isp.plans(), pl.id(), e.isShiftClick() ? -total : -1)) store().markDirty();
            again.run();
        });
        m.set(42, Items.of(Material.ARROW, "<white>Move later", "<gray>Position <white>" + (pos + 1) + "<gray> of <white>" + total,
                "<gray>Plans show in this order to customers.", "<yellow>Click · down one · <yellow>Shift-click · to the bottom"), e -> {
            Frame.click(p);
            if (dev.mccomm.cell.util.Reorder.move(isp.plans(), pl.id(), e.isShiftClick() ? total : 1)) store().markDirty();
            again.run();
        });
        Frame.back(m, p, "Plans", () -> isp(p, isp, PLANS, 0));
        m.open(p);
    }

    private void networkTab(Menu m, Player p, Isp isp, int page) {
        if (isp.carrierBacked()) {
            var c = plugin.store().carrier(isp.carrierId());
            List<dev.mccomm.cell.model.Tower> towers = c == null ? List.of() : plugin.store().towersOf(c.id());
            if (towers.isEmpty()) m.set(22, Frame.empty("No towers", "5G Home runs on the carrier's towers."));
            Frame.page(m, p, towers, page, LIST3, t -> Items.of(Material.BLAZE_ROD, "<white>" + Items.esc(t.name()), List.of(
                    "<gray>Fixed wireless customers: <white>" + store().routers().stream().filter(r -> r.towerId() == t.id() && r.state() == Router.State.ACTIVE).count(),
                    t.online() ? "<green>Online" : "<red>Offline")), null, pg -> isp(p, isp, NETWORK, pg));
            m.set(40, Items.of(Material.OAK_SIGN, "<green>5G Home", "<gray>Customers connect to your carrier's towers:",
                    "<gray>the better the 5G signal at their door, the faster the line."));
            return;
        }
        List<Node> nodes = store().nodesOf(isp.id());
        if (nodes.isEmpty()) m.set(22, Frame.empty("No nodes yet", "Look at a wall sign and run", "/isp node install <tech> <name>"));
        Frame.page(m, p, nodes, page, LIST3, n -> nodeItem(n), (n, e) -> {
            Frame.click(p);
            node(p, n);
        }, pg -> isp(p, isp, NETWORK, pg));
        List<String> help = new ArrayList<>();
        help.add("<gray>Look at a wall sign and run:");
        help.add("<white>/isp node install <tech> <name>");
        for (Technology t : Technology.nodeTechs()) {
            TechSpec s = spec(t);
            help.add("<gray>" + s.name() + ": <white>" + money(s.nodeCost()) + " <gray>· reaches "
                    + (s.range() > 0 ? Math.round(s.range()) + " blocks" : "anywhere"));
        }
        m.set(40, Items.of(Material.OAK_SIGN, "<green>Build a node", help));
    }

    private ItemStack nodeItem(Node n) {
        TechSpec s = spec(n.tech());
        Isp isp = store().isp(n.ispId());
        boolean up = isp != null && isp.paidUp() && !n.faulted();
        return Items.of(s.icon(), "<white>" + Items.esc(n.name()) + " <gray>· " + s.name(), List.of(
                "<gray>Tier: <white>" + n.tier() + "<gray>/" + s.maxTier(),
                "<gray>Customers: <white>" + bb().users(n) + "<gray>/" + Math.round(bb().capacity(n)),
                "<gray>Upkeep: <white>" + money(bb().nodeUpkeep(n)),
                "<gray>At: <white>" + n.key().x() + ", " + n.key().y() + ", " + n.key().z(),
                n.faulted() ? "<red>FAULT: customers are offline" : up ? "<green>Online" : "<red>Down",
                "<yellow>Click to manage"));
    }

    private void customersTab(Menu m, Player p, Isp isp, int page) {
        List<Router> rs = new ArrayList<>(store().routersOfIsp(isp.id()));
        if (rs.isEmpty()) m.set(22, Frame.empty("No customers yet", "Customers sign up on their router."));
        Frame.page(m, p, rs, page, LIST3, r -> {
            InternetPlan pl = isp.plans().get(r.planId());
            Link l = bb().link(r);
            List<String> lore = new ArrayList<>();
            lore.add("<gray>Home: <white>" + Items.esc(r.name()));
            lore.add("<gray>Plan: <white>" + (pl == null ? "?" : Items.esc(pl.name())));
            lore.add("<gray>Status: <white>" + r.state().name().toLowerCase(Locale.ROOT));
            if (l != null) lore.add("<gray>Speed: <white>" + Msg.speed(l.downMbps()) + " <gray>via " + Items.esc(l.sourceName()));
            return Items.head(Bukkit.getOfflinePlayer(r.owner()), "<white>" + Items.esc(nameOf(r.owner())), lore);
        }, null, pg -> isp(p, isp, CUSTOMERS, pg));
    }

    // ================================================================== node menu

    public void node(Player p, Node n) {
        Isp isp = store().isp(n.ispId());
        if (isp == null) return;
        if (!bb().canManage(p, isp)) {
            Msg.raw(p, "<dark_gray>━━━ <aqua>ISP node</aqua> ━━━");
            Msg.raw(p, "<gray>Provider: " + isp.styled());
            Msg.raw(p, "<gray>Technology: <white><t>", Msg.p("t", spec(n.tech()).name()));
            Msg.raw(p, "<gray>Status: " + (n.faulted() ? "<red>Fault" : isp.paidUp() ? "<green>Online" : "<red>Down"));
            return;
        }
        TechSpec s = spec(n.tech());
        Menu m = Frame.create(p, "Node · " + n.name(), "bb-node");
        m.set(13, nodeItem(n));
        if (n.faulted()) {
            m.set(22, Items.of(Material.IRON_PICKAXE, "<gold>Send a technician", "<gray>Fixes the fault now.",
                    "<gray>Cost: <white>" + (admin(p) ? "free (admin)" : money(s.repairCost())) + " <gray>from the ISP balance.",
                    "<gray>Otherwise a crew arrives in " + bb().cfg().faults().autoRepairHours() + " h."), e -> {
                Frame.click(p);
                bb().repairNode(p, n);
                node(p, n);
            });
        }
        long up = bb().upgradeCost(n);
        boolean top = n.tier() >= s.maxTier();
        m.set(29, Items.of(Material.ANVIL, top ? "<dark_gray>Max tier" : "<green>Upgrade to tier " + (n.tier() + 1),
                top ? List.of("<gray>Already at the top tier.") : List.of("<gray>Cost: <white>" + (admin(p) ? "free (admin)" : money(up)) + " <gray>from the ISP balance",
                        "<gray>Serves <white>" + Math.round(s.nodeCapacity() * (n.tier() + 1)) + "<gray> customers at full speed.",
                        "<gray>Upkeep rises too.")), top ? null : e -> {
            Frame.click(p);
            bb().upgradeNode(p, n);
            node(p, n);
        });
        m.set(31, Items.of(Material.NAME_TAG, "<white>Rename", "<yellow>Click to change"), e -> plugin.prompt().ask(p, "<aqua>New node name?", name -> {
            if (!bb().cfg().nodeNamePattern().matcher(name).matches()) {
                Msg.send(p, "<red>Node names are 1-12 letters, numbers, _ or -.");
                return;
            }
            Node other = store().nodeByName(isp.id(), name);
            if (other != null && other != n) {
                Msg.send(p, "<red>You already have a node named <white><n></white>.", Msg.p("n", name));
                return;
            }
            n.name(name);
            store().markDirty();
            bb().refresh(n);
            node(p, n);
        }));
        m.set(33, Items.of(Material.TNT, "<red>Take down", "<gray>Removes the node. Customers on it will", "<gray>re-connect to another node if one reaches them."), e -> {
            Frame.click(p);
            confirm(p, "<red>Take down node " + Items.esc(n.name()) + "?", List.of("<gray>The node's cost is not refunded."), () -> {
                bb().removeNode(n, true);
                Msg.send(p, "<yellow>Node <white><n></white> taken down.", Msg.p("n", n.name()));
                isp(p, isp, NETWORK, 0);
            }, () -> node(p, n));
        });
        Frame.back(m, p, "ISP", () -> isp(p, isp, NETWORK, 0));
        m.open(p);
    }

    // ============================================================== router menu

    /** Owners get the router menu; others get a short note about the Wi-Fi. */
    public void routerOrInfo(Player p, Router r) {
        if (r.owner().equals(p.getUniqueId()) || admin(p)) {
            router(p, r, STATUS, 0);
            return;
        }
        Link l = bb().link(r);
        Msg.raw(p, "<dark_gray>━━━ <aqua>Router</aqua> ━━━");
        Msg.raw(p, "<gray>Owner: <white><o>", Msg.p("o", nameOf(r.owner())));
        if (r.open() && l != null) {
            Msg.raw(p, "<green>Guest Wi-Fi: <gray>free to use within <white><d></white> blocks (<w>).",
                    Msg.p("d", "" + Math.round(bb().reach(r))), Msg.p("w", bb().cfg().tier(r.tier()).name()));
            Msg.raw(p, "<gray>Provider: " + l.isp().styled() + " <gray>· <white><spd>", Msg.p("spd", Msg.speed(l.downMbps())));
        } else {
            Msg.raw(p, "<gray>This Wi-Fi is private.");
        }
    }

    public void router(Player p, Router r, String tab, int page) {
        if (store().router(r.id()) != r) {
            Msg.send(p, "<red>That router is gone.");
            return;
        }
        Menu m = Frame.create(p, "Router · " + r.name(), "bb-router-" + tab);
        Frame.tabs(m, p, List.of(
                new Frame.Tab(STATUS, Material.COMPARATOR, "Connection"),
                new Frame.Tab(PROVIDERS, Material.COMPASS, "Providers")), tab, k -> router(p, r, k, 0));
        if (PROVIDERS.equals(tab)) providersTab(m, p, r, page);
        else statusTab(m, p, r);
        m.open(p);
    }

    private List<String> routerLore(Router r) {
        List<String> l = new ArrayList<>();
        Isp isp = r.ispId() == null ? null : store().isp(r.ispId());
        InternetPlan plan = isp == null ? null : isp.plans().get(r.planId());
        InternetPlan terms = r.terms() != null ? r.terms() : plan;
        l.add("<gray>Status: " + switch (r.state()) {
            case NONE -> "<yellow>No internet plan yet";
            case INSTALLING -> "<gold>Technician on the way";
            case ACTIVE -> bb().problem(r) == null ? "<green>Online" : "<red>No signal";
            case SUSPENDED -> "<red>Suspended: unpaid";
        });
        if (isp != null) l.add("<gray>Provider: " + isp.styled());
        if (terms != null) {
            l.add("<gray>Plan: <white>" + Items.esc(terms.name()) + " <gray>(" + spec(terms.tech()).name() + ")");
            l.add("<gray>Price: <white>" + money(terms.price()) + " <gray>per " + BillingService.period(terms.periodHours()));
            if (plan != null && !plan.sameTermsAs(terms)) l.add("<yellow>The provider changed this plan: new terms start at renewal.");
            if (!terms.unlimited() && r.state() == Router.State.ACTIVE) {
                l.add("<gray>Data left: <white>" + dev.mccomm.cell.Settings.data(r.dataLeftMB()) + "<gray> of " + dataText(terms)
                        + (bb().throttled(r) ? " <red>(slowed to " + Msg.speed(bb().cfg().throttleMbps()) + ")" : ""));
            }
        }
        Link link = bb().link(r);
        if (link != null) {
            l.add("<gray>Via: <white>" + Items.esc(link.sourceName()) + " <gray>(" + Msg.distance(link.distance()) + " away)");
            l.add("<green>↓ <white>" + Msg.speed(link.downMbps()) + " <light_purple>↑ <white>" + Msg.speed(link.upMbps())
                    + " <gray>· <white>" + Math.round(link.pingMs()) + " ms");
            l.add("<gray>Renews in: <white>" + BillingService.until(r.nextBill() - System.currentTimeMillis()));
        } else if (r.state() == Router.State.ACTIVE) {
            String why = bb().problem(r);
            if (why != null) l.add("<red>" + Items.esc(why));
        }
        var tier = bb().cfg().tier(r.tier());
        l.add("<gray>Wi-Fi: <white>" + tier.name() + " " + (r.open() ? "<green>shared" : "<yellow>private") + " <dark_gray>(" + Math.round(bb().reach(r)) + " blocks, " + tier.maxGuests() + " guests)");
        return l;
    }

    private void statusTab(Menu m, Player p, Router r) {
        Material icon = r.tech() == null ? Material.COMPARATOR : spec(r.tech()).icon();
        m.set(13, Items.of(icon, "<aqua>" + Items.esc(r.name()), routerLore(r)));
        Link link = bb().link(r);
        m.set(29, Items.of(Material.CLOCK, link == null ? "<dark_gray>Speed test" : "<aqua>Speed test",
                link == null ? List.of("<gray>Needs a working connection.") : List.of("<gray>Ping, download and upload over your line.",
                        "<gray>Counts against a capped plan's data.")),
                link == null ? null : e -> {
                    Frame.click(p);
                    p.closeInventory();
                    bb().speedtest(p, link);
                });
        m.set(31, Items.of(r.open() ? Material.LIME_DYE : Material.GRAY_DYE, r.open() ? "<green>Wi-Fi: shared" : "<yellow>Wi-Fi: private",
                "<gray>Shared: anyone in range can use it", "<gray>(up to " + bb().cfg().tier(r.tier()).maxGuests() + " guests at once).", "<gray>Private: only you.", "<yellow>Click to switch"), e -> {
            Frame.click(p);
            r.open(!r.open());
            store().markDirty();
            router(p, r, STATUS, 0);
        });
        m.set(33, Items.of(Material.NAME_TAG, "<white>Rename", "<yellow>Click to change"), e -> plugin.prompt().ask(p, "<aqua>New name for this router?", name -> {
            if (!BroadbandService.validName(name)) {
                Msg.send(p, "<red>Names are 1-16 letters, numbers, spaces, _ or -.");
                return;
            }
            r.name(name);
            store().markDirty();
            bb().refresh(r);
            router(p, r, STATUS, 0);
        }));
        boolean maxed = r.tier() >= bb().cfg().maxWifiTier();
        var next = maxed ? null : bb().cfg().tier(r.tier() + 1);
        m.set(20, Items.of(Material.REPEATER, maxed ? "<dark_gray>Newest Wi-Fi" : "<green>Upgrade to " + next.name(),
                maxed ? List.of("<gray>This router is the newest generation.") : List.of(
                        "<gray>Reach: <white>" + Math.round(bb().cfg().wifiRange() * next.rangeFactor()) + " blocks",
                        "<gray>Guests: <white>" + next.maxGuests(),
                        "<gray>Cost: <white>" + (admin(p) ? "free (admin)" : money(next.price())),
                        "<gray>Walls in the way cost range.")), maxed ? null : e -> {
            Frame.click(p);
            bb().upgradeWifi(p, r);
            router(p, r, STATUS, 0);
        });
        boolean hasLine = link != null && (link.node() != null);
        m.set(22, Items.of(Material.END_ROD, hasLine ? "<aqua>Show the line" : "<dark_gray>Show the line",
                hasLine ? List.of("<gray>Draws the cable from the node", "<gray>to this router in sparks.") : List.of("<gray>Needs a wired connection.")),
                hasLine ? e -> {
                    Frame.click(p);
                    p.closeInventory();
                    bb().showLine(p, r);
                } : null);
        if (r.state() == Router.State.SUSPENDED) {
            Isp isp = store().isp(r.ispId());
            InternetPlan plan = isp == null ? null : isp.plans().get(r.planId());
            m.set(38, Items.of(Material.EMERALD, "<green>Pay now", "<gray>Pay the missed renewal" + (plan == null ? "" : " (" + money(bb().priceFor(isp, plan, r.owner())) + ")") + ".",
                    "<gray>Service comes straight back."), e -> {
                Frame.click(p);
                bb().payNow(p, r);
                router(p, r, STATUS, 0);
            });
        }
        if (r.subscribed()) {
            m.set(40, Items.of(Material.ORANGE_CONCRETE, "<gold>Cancel service", "<gray>Ends your plan. No refund.", "<gray>The router stays so you can pick a new provider."), e -> {
                Frame.click(p);
                confirm(p, "<gold>Cancel your internet?", List.of("<gray>You lose the rest of this billing period."), () -> {
                    bb().cancel(r);
                    router(p, r, PROVIDERS, 0);
                }, () -> router(p, r, STATUS, 0));
            });
        }
        m.set(42, Items.of(Material.TNT, "<red>Remove router", "<gray>Takes the router off the wall sign.", "<gray>Cancels any plan on it."), e -> {
            Frame.click(p);
            confirm(p, "<red>Remove this router?", List.of("<gray>Your plan on it ends. No refund."), () -> {
                bb().removeRouter(r, true);
                Msg.send(p, "<yellow>Router removed.");
                p.closeInventory();
            }, () -> router(p, r, STATUS, 0));
        });
        Frame.help(m, "Your router", "Pick a provider on the Providers tab.", "Computers and phones within reach of this sign",
                "can use the connection. Walls in between cost range.", "Phones on your Wi-Fi use no mobile data.");
    }

    private record Offer(Isp isp, InternetPlan plan, Quote quote) {
    }

    /** The Providers tab: one "folder" per ISP. Click one to see its plans. */
    private void providersTab(Menu m, Player p, Router r, int page) {
        // Only providers that can serve this address (plus your current one). Yours first, then by name.
        List<Isp> isps = new ArrayList<>();
        for (Isp isp : store().isps()) {
            boolean mine = r.subscribed() && isp.id().equals(r.ispId());
            boolean any = false;
            for (InternetPlan pl : isp.plans().values()) if (bb().quote(isp, pl, r.key(), r).ok()) any = true;
            if (mine || any) isps.add(isp);
        }
        isps.sort(Comparator.comparing((Isp i) -> !(r.subscribed() && i.id().equals(r.ispId())))
                .thenComparing(i -> i.name().toLowerCase(Locale.ROOT)));
        if (isps.isEmpty()) m.set(22, Frame.empty("No providers reach you", "Nobody serves this address yet.", "An ISP needs a node in range of it."));
        Frame.page(m, p, isps, page, LIST4, isp -> ispFolder(r, isp), (isp, e) -> {
            Frame.click(p);
            providerPlans(p, r, isp, 0);
        }, pg -> router(p, r, PROVIDERS, pg));
    }

    private ItemStack ispFolder(Router r, Isp isp) {
        boolean current = r.subscribed() && isp.id().equals(r.ispId());
        Set<String> techs = new LinkedHashSet<>();
        long cheapest = Long.MAX_VALUE;
        double fastest = 0;
        int ok = 0;
        for (InternetPlan pl : isp.plans().values()) {
            techs.add(spec(pl.tech()).name());
            Quote q = bb().quote(isp, pl, r.key(), r);
            if (!q.ok()) continue;
            ok++;
            cheapest = Math.min(cheapest, q.price());
            fastest = Math.max(fastest, q.downMbps());
        }
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Rating: " + bb().ratingLore(isp));
        lore.add("<gray>Technologies: <white>" + String.join(", ", techs));
        lore.add("<gray>Plans here: <white>" + ok);
        int hidden = isp.plans().size() - ok;
        if (hidden > 0) lore.add("<dark_gray>" + hidden + " more not available at this spot (open to see why)");
        if (ok > 0) {
            lore.add("<gray>From: <white>" + money(cheapest));
            lore.add("<green>↓ <white>up to ~" + Msg.speed(fastest));
        } else {
            lore.add("<red>Your plan is no longer available here");
        }
        if (current) lore.add("<green>Your current provider");
        lore.add("<yellow>Click to open");
        ItemStack it = Items.of(Material.CHEST, "<white>" + isp.styled(), lore);
        return current ? Items.glow(it) : it;
    }

    /** Inside an ISP's folder: its plans, in the order the ISP set. */
    private void providerPlans(Player p, Router r, Isp isp, int page) {
        if (store().isp(isp.id()) != isp) {
            router(p, r, PROVIDERS, 0);
            return;
        }
        Menu m = Frame.create(p, "Router · " + r.name(), "bb-router-providers");
        Frame.tabs(m, p, List.of(
                new Frame.Tab(STATUS, Material.COMPARATOR, "Connection"),
                new Frame.Tab(PROVIDERS, Material.COMPASS, "Providers")), PROVIDERS, k -> router(p, r, k, 0));
        List<Offer> offers = new ArrayList<>();
        List<String> hiddenInfo = new ArrayList<>();
        for (InternetPlan pl : isp.plans().values()) {
            Quote q = bb().quote(isp, pl, r.key(), r);
            boolean current = r.subscribed() && isp.id().equals(r.ispId()) && pl.id().equals(r.planId());
            if (q.ok() || current) offers.add(new Offer(isp, pl, q)); // plans that can't be ordered here stay hidden
            else hiddenInfo.add("<gray>• <white>" + Items.esc(pl.name()) + " <gray>(" + spec(pl.tech()).name() + "): <red>" + Items.esc(q.problem()));
        }
        if (offers.isEmpty()) m.set(22, Frame.empty("No plans here", "None of this provider's plans reach you."));
        Frame.page(m, p, offers, page, LIST4, o -> offerItem(r, o), (o, e) -> {
            Frame.click(p);
            if (!o.quote().ok()) {
                Msg.send(p, "<red>Not available here: <why>.", Msg.p("why", o.quote().problem()));
                return;
            }
            boolean current = r.subscribed() && o.isp().id().equals(r.ispId()) && o.plan().id().equals(r.planId());
            if (current) return;
            boolean switching = r.subscribed() && o.isp().id().equals(r.ispId()) && o.plan().tech() == r.tech();
            long upfront = switching ? 0 : o.plan().installFee() + o.quote().dropFee();
            confirm(p, "<green>Order " + Items.esc(o.plan().name()) + "?", List.of(
                    "<gray>Provider: " + o.isp().styled(),
                    "<gray>Pay now: <white>" + money(o.quote().price() + upfront) + (switching ? " <gray>(no install fee)" : " <gray>(install, cabling and first period)"),
                    "<gray>Then <white>" + money(o.quote().price()) + " <gray>every " + BillingService.period(o.plan().periodHours()),
                    "<gray>Data: <white>" + dataText(o.plan()),
                    "<gray>Estimated speed: <white>" + Msg.speed(o.quote().downMbps())),
                    () -> {
                        bb().subscribe(p, r, o.isp(), o.plan());
                        router(p, r, STATUS, 0);
                    }, () -> providerPlans(p, r, isp, page));
        }, pg -> providerPlans(p, r, isp, pg));
        if (hiddenInfo.isEmpty()) Frame.help(m, "Plans", "Only plans that reach this router are listed.");
        else {
            List<String> lines = new ArrayList<>();
            lines.add("Not shown (can't be ordered here):");
            lines.addAll(hiddenInfo);
            Frame.help(m, "Hidden plans", lines.toArray(new String[0]));
        }
        Frame.back(m, p, "Providers", () -> router(p, r, PROVIDERS, 0));
        m.open(p);
    }

    private ItemStack offerItem(Router r, Offer o) {
        TechSpec s = spec(o.plan().tech());
        Quote q = o.quote();
        boolean current = r.subscribed() && o.isp().id().equals(r.ispId()) && o.plan().id().equals(r.planId());
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Provider: " + o.isp().styled());
        lore.add("<gray>Rating: " + bb().ratingLore(o.isp()));
        lore.add("<gray>Technology: <white>" + s.name());
        lore.add("<gray>Price: <white>" + money(q.price()) + " <gray>per " + BillingService.period(o.plan().periodHours())
                + (q.price() < o.plan().price() ? " <green>(bundle: was " + money(o.plan().price()) + ")" : ""));
        lore.add("<gray>Data: <white>" + dataText(o.plan()));
        lore.add("<gray>Install fee: <white>" + money(o.plan().installFee()) + (q.dropFee() > 0 ? " <gray>+ <white>" + money(q.dropFee()) + " <gray>cabling" : ""));
        if (q.ok()) {
            lore.add("<green>↓ <white>~" + Msg.speed(q.downMbps()) + " <gray>· <white>" + Math.round(q.pingMs()) + " ms");
            lore.add("<gray>Via: <white>" + Items.esc(q.sourceName()) + " <gray>(" + Msg.distance(q.distance()) + " away)");
            lore.add(current ? "<green>Your current plan" : "<yellow>Click to order");
        } else {
            lore.add("<red>" + Items.esc(q.problem()));
        }
        ItemStack it = Items.of(q.ok() ? s.icon() : Material.GRAY_DYE, (q.ok() ? "<white>" : "<gray>") + Items.esc(o.plan().name()), lore);
        return current ? Items.glow(it) : it;
    }

    // ============================================================= computer menu

    public void computer(Player p, Computer c) {
        if (store().computer(c.id()) != c) {
            Msg.send(p, "<red>That computer is gone.");
            return;
        }
        Menu m = Frame.create(p, "Computer · " + c.name(), "bb-computer");
        Link l = bb().computerLink(c, p.getUniqueId());
        boolean owner = c.owner().equals(p.getUniqueId()) || admin(p);
        if (l == null) {
            m.set(13, Items.of(Material.BARRIER, "<red>Not connected", List.of(
                    "<gray>No Wi-Fi with a working connection reaches",
                    "<gray>this computer (range " + Math.round(bb().cfg().wifiRange()) + "+ blocks, less through walls).",
                    "<gray>Put a router within range and sign up on its",
                    "<gray>Providers tab.")));
        } else {
            m.set(13, Items.of(spec(l.tech()).icon(), "<green>Connected: " + Items.esc(l.router().name()), List.of(
                    "<gray>Provider: " + l.isp().styled(),
                    "<gray>Plan: <white>" + Items.esc(l.plan().name()) + " <gray>(" + spec(l.tech()).name() + ")",
                    "<green>↓ <white>" + Msg.speed(l.downMbps()) + " <light_purple>↑ <white>" + Msg.speed(l.upMbps()),
                    "<gray>Ping: <white>" + Math.round(l.pingMs()) + " ms")));
        }
        m.set(29, Items.of(Material.CLOCK, l == null ? "<dark_gray>Speed test" : "<aqua>Speed test",
                l == null ? List.of("<gray>Needs a connection.") : List.of("<gray>Ping, download and upload.")),
                l == null ? null : e -> {
                    Frame.click(p);
                    p.closeInventory();
                    bb().speedtest(p, l);
                });
        boolean chat = plugin.internet().inMode(p);
        m.set(31, Items.of(chat ? Material.LIME_DYE : Material.GRAY_DYE, chat ? "<green>Internet chat: on" : "<yellow>Internet chat: off",
                l == null && !chat ? List.of("<gray>Needs a connection.") : List.of("<gray>Your chat goes to the internet channel.", "<gray>Free over Wi-Fi.", "<yellow>Click to switch")),
                l == null && !chat ? null : e -> {
                    Frame.click(p);
                    plugin.internet().toggle(p);
                    computer(p, c);
                });
        List<Router> near = bb().routersInRange(c, p.getUniqueId());
        List<String> wl = new ArrayList<>();
        wl.add("<gray>Networks in range:");
        wl.add((c.routerId() == 0 ? "<green>▶ " : "<gray>  ") + "Automatic (best one)");
        for (Router r : near) wl.add((c.routerId() == r.id() ? "<green>▶ " : "<gray>  ") + Items.esc(r.name()) + " <dark_gray>(" + Items.esc(nameOf(r.owner())) + ")");
        if (owner) wl.add("<yellow>Click to switch network");
        m.set(33, Items.of(Material.COMPASS, "<aqua>Wi-Fi", wl), owner ? e -> {
            Frame.click(p);
            List<Integer> order = new ArrayList<>();
            order.add(0);
            for (Router r : near) order.add(r.id());
            int idx = order.indexOf(c.routerId());
            c.routerId(order.get((idx + 1) % order.size()));
            store().markDirty();
            computer(p, c);
        } : null);
        boolean web = bb().cfg().browser().enabled() && l != null;
        m.set(22, Items.of(Material.ENCHANTED_BOOK, web ? "<aqua>Browser" : "<dark_gray>Browser",
                web ? List.of("<gray>News, a marketplace and mail.", "<gray>Pages load at your line's speed", "<gray>and use data on a capped plan.")
                        : List.of("<gray>Needs a connection.")), web ? e -> {
            Frame.click(p);
            plugin.browser().open(p, c);
        } : null);
        if (owner) {
            m.set(38, Items.of(Material.NAME_TAG, "<white>Rename", "<yellow>Click to change"), e -> plugin.prompt().ask(p, "<aqua>New name for this computer?", name -> {
                if (!BroadbandService.validName(name)) {
                    Msg.send(p, "<red>Names are 1-16 letters, numbers, spaces, _ or -.");
                    return;
                }
                c.name(name);
                store().markDirty();
                bb().refresh(c);
                computer(p, c);
            }));
            m.set(42, Items.of(Material.TNT, "<red>Remove computer", "<gray>Takes it off the wall sign."), e -> {
                Frame.click(p);
                confirm(p, "<red>Remove this computer?", List.of("<gray>You can set up another on any wall sign."), () -> {
                    bb().removeComputer(c, true);
                    Msg.send(p, "<yellow>Computer removed.");
                    p.closeInventory();
                }, () -> computer(p, c));
            });
        }
        Frame.help(m, "Your computer", "A computer joins the Wi-Fi of a router in reach.",
                "The router's owner picks the provider.", "Internet chat over Wi-Fi uses no mobile data.");
        m.open(p);
    }
}
