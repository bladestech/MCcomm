package dev.mccomm.cell.gui;

import dev.mccomm.cell.CellPlugin;
import dev.mccomm.cell.broadband.Isp;
import dev.mccomm.cell.economy.CompanyService;
import dev.mccomm.cell.economy.Group;
import dev.mccomm.cell.model.Carrier;
import dev.mccomm.cell.model.Company;
import dev.mccomm.cell.model.LedgerEntry;
import dev.mccomm.cell.model.Manufacturer;
import dev.mccomm.cell.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** /company: a parent company's menu. Tabs: Overview, Companies, Money, Loans. */
public final class GroupMenu {
    private static final long CONFIRM_MS = 5000;
    public static final String OVERVIEW = "overview", COMPANIES = "companies", MONEY = "money", LOANS = "loans";
    private static final List<Frame.Tab> TABS = List.of(
            new Frame.Tab(OVERVIEW, Material.BEACON, "Overview"),
            new Frame.Tab(COMPANIES, Material.BOOKSHELF, "Companies"),
            new Frame.Tab(MONEY, Material.GOLD_INGOT, "Money"),
            new Frame.Tab(LOANS, Material.GOLD_NUGGET, "Loans"));

    private final CellPlugin plugin;
    private final Map<String, Long> confirms = new HashMap<>();

    public GroupMenu(CellPlugin plugin) {
        this.plugin = plugin;
    }

    private String money(long v) {
        return plugin.settings().money(v);
    }

    private Menu page(Player p, Group gr, String tab) {
        Menu m = Frame.create(p, Items.esc(gr.name()), "group");
        Frame.tabs(m, p, TABS, tab, key -> tab(p, gr, key, 0));
        return m;
    }

    public void open(Player p, Group gr) {
        tab(p, gr, OVERVIEW, 0);
    }

    public void tab(Player p, Group gr, String tab, int pg) {
        if (!plugin.groups().canManage(p, gr)) {
            Msg.send(p, "<red>Only the owner of <white><g></white> can open it.", Msg.p("g", gr.name()));
            return;
        }
        switch (tab) {
            case COMPANIES -> companies(p, gr, pg);
            case MONEY -> money(p, gr);
            case LOANS -> loans(p, gr, pg);
            default -> overview(p, gr);
        }
    }

    private static Material icon(Company c) {
        return switch (c.kind()) {
            case CARRIER -> Material.LIGHTNING_ROD;
            case MANUFACTURER -> Material.SMITHING_TABLE;
            case ISP -> Material.COMPARATOR;
        };
    }

    /** Opens a member's own menu. */
    private void openCompany(Player p, Company c) {
        if (c instanceof Carrier car) plugin.carrierMenu().open(p, car);
        else if (c instanceof Manufacturer mf) plugin.manufacturerMenu().open(p, mf);
        else if (c instanceof Isp isp) plugin.broadbandMenu().isp(p, isp);
    }

    // ================================================================ overview

    private void overview(Player p, Group gr) {
        Menu m = page(p, gr, OVERVIEW);
        List<Company> members = plugin.groups().members(gr);
        long net = 0;
        int carriers = 0, mfrs = 0, isps = 0;
        for (Company c : members) {
            net += plugin.companies().netPerDay(c);
            switch (c.kind()) {
                case CARRIER -> carriers++;
                case MANUFACTURER -> mfrs++;
                case ISP -> isps++;
            }
        }
        CompanyService.Valuation v = plugin.companies().valuation(gr);
        m.set(13, Items.of(Material.BEACON, "<b>" + gr.styled(),
                "<gray>Owner: <white>" + name(gr),
                "<gray>Companies: <white>" + members.size() + " <dark_gray>(" + carriers + " carriers, " + mfrs + " manufacturers, " + isps + " ISPs)"));
        m.set(20, Items.glow(Items.of(Material.GOLD_BLOCK, "<gold><b>Treasury " + money(gr.balance()), "<gray>Loans out: <white>" + money(gr.totalLoans()),
                "", "<yellow>Click · Money tab")), e -> {
            Frame.click(p);
            money(p, gr);
        });
        m.set(22, Items.of(net >= 0 ? Material.EMERALD : Material.REDSTONE, (net >= 0 ? "<green>+" : "<red>-") + money(Math.abs(net)) + " <gray>a day",
                "<gray>Net income of all companies together."));
        m.set(24, Items.of(Material.GOLD_INGOT, "<gold>Worth " + money(v.total()), plugin.companyMenu().valuationLore(v)));
        var g = plugin.settings().groups;
        m.set(31, Items.of(Material.SHIELD, "<aqua>What the parent company gives",
                "<gray>• Backing: if a company can't pay upkeep,",
                "<gray>  operations or repairs, the treasury lends it.",
                "<gray>• " + g.bankruptcyExtraDays() + " extra days before a company goes bankrupt.",
                "<gray>• Operations " + Math.round(g.opsDiscountPerMember() * 100) + "% cheaper per other company (up to " + Math.round(g.opsDiscountMax() * 100) + "%).",
                "<gray>• Transfers inside it cost " + g.transferFeePercent() + "% (wallets " + plugin.settings().transferFeePercent + "%).",
                "<gray>• Companies inside don't count towards", "<gray>  the per-player company limits."));
        int owing = 0;
        for (Company c : members) if (plugin.groups().owes(c) > 0) owing++;
        if (owing > 0) m.set(40, Items.of(Material.GOLD_NUGGET, "<gold>" + owing + " compan" + (owing == 1 ? "y owes" : "ies owe") + " the treasury",
                "<yellow>Click · Loans tab"), e -> {
            Frame.click(p);
            loans(p, gr, 0);
        });
        Frame.help(m, "Parent company", "Holds your carriers, manufacturers", "and ISPs. Its treasury backs them when",
                "they run short. Add companies in the", "Companies tab or with /company add.");
        m.open(p);
    }

    private String name(Group gr) {
        String n = Bukkit.getOfflinePlayer(gr.owner()).getName();
        return n == null ? "?" : n;
    }

    // ================================================================ companies

    private ItemStack memberItem(Group gr, Company c) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + c.kind().display + " · balance <white>" + money(c.balance()));
        long net = plugin.companies().netPerDay(c);
        lore.add("<gray>Net: " + (net >= 0 ? "<green>+" : "<red>-") + money(Math.abs(net)) + "<gray>/day");
        long owes = gr.loan(c.ref().key());
        if (owes > 0) lore.add("<gold>Owes the treasury " + money(owes));
        long bankrupt = c instanceof Company co ? plugin.companies().bankruptAt(co) : 0;
        if (bankrupt > 0) lore.add("<dark_red>Bankrupt in " + dev.mccomm.cell.economy.BillingService.until(bankrupt - System.currentTimeMillis()));
        lore.add("");
        lore.add("<yellow>Click · open");
        lore.add("<yellow>Right-click · send money from the treasury");
        lore.add("<yellow>Shift-left · send money to the treasury");
        lore.add("<yellow>Shift-right · take out of the group");
        return Items.of(icon(c), "<b>" + c.styled(), lore);
    }

    private void companies(Player p, Group gr, int pg) {
        Menu m = page(p, gr, COMPANIES);
        List<Company> members = plugin.groups().members(gr);
        if (members.isEmpty()) m.set(22, Frame.empty("No companies yet", "Add one below, or /company add <type> <name>."));
        Frame.page(m, p, members, pg, Frame.rows(1, 3), c -> memberItem(gr, c), (c, e) -> {
            Frame.click(p);
            if (e.isShiftClick() && e.isRightClick()) {
                String err = plugin.groups().remove(p, c);
                if (err != null) Msg.send(p, "<red>" + err);
                else Msg.send(p, "<yellow><c> left <white><g></white>.", Msg.p("c", c.name()), Msg.p("g", gr.name()));
                companies(p, gr, pg);
            } else if (e.isShiftClick()) {
                plugin.companyMenu().askAmount(p, "<aqua>Send how many credits from <white>" + Items.esc(c.name()) + "</white> to the treasury?", a -> {
                    plugin.groups().moveMember(p, gr, c, a, false);
                    companies(p, gr, pg);
                });
            } else if (e.isRightClick()) {
                plugin.companyMenu().askAmount(p, "<aqua>Send how many credits from the treasury to <white>" + Items.esc(c.name()) + "</white>?", a -> {
                    plugin.groups().moveMember(p, gr, c, a, true);
                    companies(p, gr, pg);
                });
            } else openCompany(p, c);
        }, n -> companies(p, gr, n));
        m.set(44, Items.of(Material.WRITABLE_BOOK, "<green>Add a company", "<gray>One of yours that isn't in a group.", "<yellow>Click · choose"), e -> {
            Frame.click(p);
            addPicker(p, gr, 0);
        });
        Frame.help(m, "Companies", "Everything this parent company owns.", "Money between it and its companies",
                "costs " + plugin.settings().groups.transferFeePercent() + "%. Money from a company that owes", "the treasury repays the loan first (free).");
        m.open(p);
    }

    private void addPicker(Player p, Group gr, int pg) {
        Menu m = Frame.create(p, "Add to " + Items.esc(gr.name()), "group");
        Frame.back(m, p, "Companies", () -> companies(p, gr, 0));
        List<Company> free = new ArrayList<>();
        for (Company c : plugin.groups().ownedCompanies(gr.owner())) {
            if (plugin.groups().groupOf(c) != null) continue;
            if (c instanceof Isp isp && isp.carrierBacked()) continue;
            free.add(c);
        }
        if (free.isEmpty()) m.set(22, Frame.empty("Nothing to add", "All your companies are in a group already."));
        Frame.page(m, p, free, pg, Frame.rows(1, 4), c -> Items.of(icon(c), "<b>" + c.styled(),
                "<gray>" + c.kind().display + " · balance <white>" + money(c.balance()), "<yellow>Click · add"), (c, e) -> {
            Frame.click(p);
            String err = plugin.groups().add(p, gr, c);
            if (err != null) Msg.send(p, "<red>" + err);
            else Msg.send(p, "<green><c> is now part of <white><g></white>.", Msg.p("c", c.name()), Msg.p("g", gr.name()));
            companies(p, gr, 0);
        }, n -> addPicker(p, gr, n));
        m.open(p);
    }

    // ================================================================ money

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

    private void money(Player p, Group gr) {
        Menu m = page(p, gr, MONEY);
        long now = System.currentTimeMillis();
        CompanyService.Valuation v = plugin.companies().valuation(gr);
        m.set(13, Items.glow(Items.of(Material.GOLD_BLOCK, "<gold><b>Worth " + money(v.total()), plugin.companyMenu().valuationLore(v))));
        List<String> hist = new ArrayList<>();
        for (LedgerEntry le : gr.ledger()) {
            if (hist.size() >= 15) break;
            hist.add(plugin.phone().ledgerLine(le, now));
        }
        if (hist.isEmpty()) hist.add("<dark_gray>Nothing yet");
        m.set(24, Items.of(Material.BOOK, "<white>Ledger", hist));
        double fee = plugin.settings().transferFeePercent;
        m.set(29, Items.of(Material.HOPPER_MINECART, "<green>Withdraw to your wallet",
                "<gray>Treasury: <white>" + money(gr.balance()) + " <gray>· fee " + fee + "%", "<yellow>Click · choose an amount"), e -> {
            Frame.click(p);
            plugin.companyMenu().askAmount(p, "<aqua>Withdraw how many credits from <white>" + Items.esc(gr.name()) + "</white>?", a -> {
                plugin.groups().moveWallet(p, gr, a, false);
                money(p, gr);
            });
        });
        m.set(31, Items.of(Material.CHEST_MINECART, "<aqua>Add from your wallet",
                "<gray>Yours: <white>" + money(plugin.store().profile(p.getUniqueId()).balance()) + " <gray>· fee " + fee + "%",
                "<yellow>Click · choose an amount"), e -> {
            Frame.click(p);
            plugin.companyMenu().askAmount(p, "<aqua>Move how many credits into <white>" + Items.esc(gr.name()) + "</white>?", a -> {
                plugin.groups().moveWallet(p, gr, a, true);
                money(p, gr);
            });
        });
        m.set(33, Items.of(Material.WRITABLE_BOOK, "<white>Sell the parent company", "<gray>Offer it, with every company in it,", "<gray>to an online player.",
                "<yellow>Click · make an offer"), e -> {
            Frame.click(p);
            plugin.companyMenu().askSale(p, gr.name(), (buyer, price) -> plugin.companies().offerSale(p, gr, buyer, price));
        });
        m.set(40, Items.of(Material.TNT, "<dark_red>Liquidate everything",
                "<gray>Liquidates every company into the", "<gray>treasury, then pays it to you minus", "<gray>the " + plugin.settings().liquidationFeePercent + "% fee.",
                "<red>Can't be undone.", "<yellow>Shift-right-click twice · liquidate"), e -> {
            if (!(e.isShiftClick() && e.isRightClick())) return;
            Frame.click(p);
            if (confirmed(p, "liq:group:" + gr.id())) {
                p.closeInventory();
                plugin.companies().liquidate(p, gr);
            } else {
                Msg.send(p, "<red>Shift-right-click again within 5 seconds to liquidate <white><g></white> and every company in it.", Msg.p("g", gr.name()));
            }
        });
        Frame.help(m, "Money", "The treasury pays its companies' bills", "when they run short, as loans they repay.",
                "Keep money in it to back them.");
        m.open(p);
    }

    // ================================================================ loans

    private void loans(Player p, Group gr, int pg) {
        Menu m = page(p, gr, LOANS);
        List<Company> owing = new ArrayList<>();
        for (Company c : plugin.groups().members(gr)) if (gr.loan(c.ref().key()) > 0) owing.add(c);
        m.set(4, Items.of(Material.GOLD_NUGGET, "<gold>Loans out: " + money(gr.totalLoans()),
                "<gray>Repaid automatically once a company has", "<gray>more than its next upkeep and an hour", "<gray>of operations."));
        if (owing.isEmpty()) m.set(22, Frame.empty("No loans", "Nobody owes the treasury."));
        Frame.page(m, p, owing, pg, Frame.rows(1, 4), c -> Items.of(icon(c), "<b>" + c.styled(),
                "<gold>Owes " + money(gr.loan(c.ref().key())), "<gray>Balance: <white>" + money(c.balance()), "<yellow>Click · open"), (c, e) -> {
            Frame.click(p);
            openCompany(p, c);
        }, n -> loans(p, gr, n));
        m.open(p);
    }
}
