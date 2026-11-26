package dev.mccomm.cell.economy;

import dev.mccomm.cell.CellPlugin;
import dev.mccomm.cell.Settings;
import dev.mccomm.cell.band.Band;
import dev.mccomm.cell.broadband.Isp;
import dev.mccomm.cell.broadband.Node;
import dev.mccomm.cell.model.Agreement;
import dev.mccomm.cell.model.Carrier;
import dev.mccomm.cell.model.Company;
import dev.mccomm.cell.model.Manufacturer;
import dev.mccomm.cell.model.Plan;
import dev.mccomm.cell.model.PlayerProfile;
import dev.mccomm.cell.model.Subscription;
import dev.mccomm.cell.model.Tower;
import dev.mccomm.cell.model.TowerModel;
import dev.mccomm.cell.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Moving money between players and their companies, company valuations, selling a company (or a parent company) to
 * another player, liquidation, and bankruptcy. Works the same for carriers, manufacturers and ISPs; members of a
 * parent company pay their liquidation money into its treasury.
 */
public final class CompanyService {
    private static final long DAY = 86_400_000L;
    private static final long OFFER_MS = 5 * 60_000L;

    private final CellPlugin plugin;

    public CompanyService(CellPlugin plugin) {
        this.plugin = plugin;
    }

    private Settings s() {
        return plugin.settings();
    }

    private PlayerProfile prof(UUID id) {
        return plugin.store().profile(id);
    }

    private GroupService groups() {
        return plugin.groups();
    }

    /** A fee: rounded up, so splitting into small amounts can't avoid it. */
    private long fee(long amount, double percent) {
        if (amount <= 0 || percent <= 0) return 0;
        return (long) Math.ceil(amount * percent / 100.0);
    }

    /** A payout share: rounded down, so nothing is ever created by rounding. */
    private long share(long amount, double percent) {
        if (amount <= 0 || percent <= 0) return 0;
        return (long) Math.floor(amount * percent / 100.0);
    }

    private void dirty() {
        plugin.store().markDirty();
        if (plugin.broadband() != null) plugin.broadband().store().markDirty();
    }

    public boolean isOwner(Player p, Company c) {
        return p.hasPermission("mccomm.admin") || (c.owner() != null && c.owner().equals(p.getUniqueId()));
    }

    // ============================================================== transfers

    /** Free transfer between two players' wallets. */
    public void pay(Player from, OfflinePlayer to, long amount) {
        if (amount <= 0) {
            Msg.send(from, "<red>Enter an amount above 0.");
            return;
        }
        if (to.getUniqueId().equals(from.getUniqueId())) {
            Msg.send(from, "<red>You can't pay yourself.");
            return;
        }
        PlayerProfile a = prof(from.getUniqueId());
        if (a.balance() < amount) {
            Msg.send(from, "<red>You only have <white><b></white>.", Msg.p("b", s().money(a.balance())));
            return;
        }
        PlayerProfile b = prof(to.getUniqueId());
        a.balance(a.balance() - amount);
        a.log(-amount, "Sent to " + to.getName());
        b.balance(b.balance() + amount);
        b.log(amount, "From " + from.getName());
        plugin.billing().payOverdue(to.getUniqueId());
        plugin.store().markDirty();
        Msg.send(from, "<green>Sent <white><a></white> to <white><p></white>.", Msg.p("a", s().money(amount)), Msg.p("p", to.getName()));
        plugin.billing().notify(to.getUniqueId(), "<green><p> sent you <white><a></white>.", Msg.p("p", from.getName()), Msg.p("a", s().money(amount)));
    }

    /** What a company keeps back on a withdrawal warning: its next upkeep bill. */
    private long upkeepOf(Company c) {
        if (c instanceof Carrier car) return plugin.billing().upkeepCost(car);
        if (c instanceof Isp isp) return plugin.broadband().upkeepDue(isp);
        return 0;
    }

    /** Company → owner's wallet (true) or wallet → company (false), with the transfer fee taken off what arrives. */
    public void move(Player p, Company c, long amount, boolean toOwner) {
        if ((c instanceof Manufacturer m && m.system()) || !isOwner(p, c)) {
            Msg.send(p, "<red>Only the owner can move money in and out of <white><c></white>.", Msg.p("c", c.name()));
            return;
        }
        PlayerProfile me = prof(p.getUniqueId());
        long have = toOwner ? c.balance() : me.balance();
        if (amount <= 0 || have < amount) {
            Msg.send(p, "<red>Not enough: <white><b></white> available.", Msg.p("b", s().money(Math.max(0, have))));
            return;
        }
        long owes = groups().owes(c);
        if (toOwner && owes > 0) {
            Msg.send(p, "<red><c> owes its parent company <white><o></white>. It can't pay out to a wallet until that's repaid.",
                    Msg.p("c", c.name()), Msg.p("o", s().money(owes)));
            return;
        }
        if (toOwner && c instanceof Isp isp && c.balance() - amount < plugin.broadband().upkeepDue(isp)) {
            Msg.send(p, "<red>You can withdraw up to <white><f></white>: the next upkeep bill stays in <c>.",
                    Msg.p("f", s().money(Math.max(0, c.balance() - plugin.broadband().upkeepDue(isp)))), Msg.p("c", c.name()));
            return;
        }
        long fee = fee(amount, s().transferFeePercent);
        long arrives = amount - fee;
        if (toOwner) {
            c.balance(c.balance() - amount);
            c.log(-amount, "Withdrawn by " + p.getName());
            me.balance(me.balance() + arrives);
            me.log(arrives, "From " + c.name() + " (fee " + s().money(fee) + ")");
            plugin.billing().payOverdue(p.getUniqueId());
            if (c instanceof Carrier && c.balance() < upkeepOf(c)) {
                Msg.send(p, "<gold>Careful: <white><c></white> no longer covers its next upkeep.", Msg.p("c", c.name()));
            }
        } else {
            me.balance(me.balance() - amount);
            me.log(-amount, "To " + c.name());
            c.balance(c.balance() + arrives);
            c.log(arrives, "From " + p.getName() + " (fee " + s().money(fee) + ")");
            if (c instanceof Carrier car) plugin.billing().retryUpkeep(car);
        }
        dirty();
        Msg.send(p, "<green>Moved <white><a></white> <dir> <white><c></white>; <white><r></white> arrived after the <white><f></white> fee.",
                Msg.p("a", s().money(amount)), Msg.p("dir", toOwner ? "out of" : "into"), Msg.p("c", c.name()),
                Msg.p("r", s().money(arrives)), Msg.p("f", s().money(fee)));
    }

    // ============================================================== valuation

    /** Value lines (label → credits) and a total. */
    public record Valuation(Map<String, Long> parts, long total) {
        static Valuation of(Map<String, Long> parts) {
            return new Valuation(parts, parts.values().stream().mapToLong(Long::longValue).sum());
        }
    }

    /** What building this tower new would cost (same generation and bands). */
    private long towerCost(Tower t) {
        TowerModel tm = new TowerModel("tmp", "tmp");
        tm.generation(t.generation());
        tm.small(t.small());
        if (t.modelBands().isEmpty()) {
            for (Band b : Band.values()) if (s().supports(t.generation(), b)) tm.bands().add(b);
        } else tm.bands().addAll(t.modelBands());
        return plugin.manufacturers().cost(tm);
    }

    private long stockValue(Carrier c) {
        long v = 0;
        for (var e : c.stock().entrySet()) {
            TowerModel tm = plugin.manufacturers().model(e.getKey());
            if (tm != null) v += plugin.manufacturers().price(tm) * e.getValue();
        }
        return v;
    }

    /** What the carrier's unused stock cost to make (liquidation uses this, not the sale price). */
    private long stockCost(Carrier c) {
        long v = 0;
        for (var e : c.stock().entrySet()) {
            TowerModel tm = plugin.manufacturers().model(e.getKey());
            if (tm != null) v += plugin.manufacturers().cost(tm) * e.getValue();
        }
        return v;
    }

    /** What building this node new would cost, tier upgrades included. */
    private long nodeCost(Node n) {
        var spec = plugin.broadband().cfg().spec(n.tech());
        long v = spec.nodeCost();
        for (int t = 1; t < n.tier(); t++) v += Math.round(spec.nodeCost() * plugin.broadband().cfg().tierUpgradePercent() * t);
        return v;
    }

    /** Net income per day: plan income minus upkeep and operations (manufacturers: their upkeep share). */
    public long netPerDay(Company co) {
        if (co instanceof Carrier c) {
            double income = 0;
            for (PlayerProfile pr : plugin.store().profiles()) {
                Subscription sub = pr.sub(c.id());
                if (sub == null || sub.planId() == null) continue;
                Plan plan = c.plans().get(sub.planId());
                if (plan == null || plan.prepaid()) continue;
                income += plugin.sales().price(plan) * 24.0 / plan.periodHours();
            }
            income += plugin.simulation().revenuePerDay(c);
            double cost = (plugin.billing().upkeepCost(c) + c.mvnoOwed()) * 24.0 / s().upkeepPeriodHours;
            cost += plugin.workforce().dailyCost(c);
            return Math.round(income - cost);
        }
        if (co instanceof Isp isp) {
            double income = plugin.broadband().incomePerDay(isp);
            double cost = plugin.broadband().upkeepDue(isp) * 24.0 / Math.max(1, plugin.broadband().cfg().upkeepPeriodHours());
            return Math.round(income - cost - plugin.workforce().dailyCost(isp));
        }
        if (co instanceof Manufacturer m) return Math.round(plugin.manufacturers().upkeepIncome(m) * 24.0 / s().upkeepPeriodHours);
        return 0;
    }

    public Valuation valuation(Company co) {
        Map<String, Long> parts = new LinkedHashMap<>();
        parts.put("Wallet", co.balance());
        if (co instanceof Carrier c) {
            long towers = 0;
            for (Tower t : plugin.store().towersOf(c.id())) towers += towerCost(t);
            parts.put("Towers (" + plugin.store().towersOf(c.id()).size() + ")", towers);
            parts.put("Tower stock", stockValue(c));
            parts.put(s().valuationDays + " days of net income", Math.max(0, netPerDay(c)) * s().valuationDays);
        } else if (co instanceof Manufacturer m) {
            long research = 0;
            for (int g = 2; g <= m.researchedGen(); g++) research += s().researchGenCost.getOrDefault(g, 0L);
            for (Band b : m.researchedBands()) research += s().researchBandCost.getOrDefault(b, 0L);
            parts.put("Research", Math.round(research / Math.max(0.01, s().rpPerCredit)));
            parts.put(s().valuationDays + " days of upkeep share", netPerDay(m) * s().valuationDays);
        } else if (co instanceof Isp isp) {
            long nodes = 0;
            List<Node> list = plugin.broadband().store().nodesOf(isp.id());
            for (Node n : list) nodes += nodeCost(n);
            parts.put("Nodes (" + list.size() + ")", nodes);
            parts.put(s().valuationDays + " days of net income", Math.max(0, netPerDay(isp)) * s().valuationDays);
        }
        long owes = groups().owes(co);
        if (owes > 0) parts.put("Owed to parent company", -owes);
        return Valuation.of(parts);
    }

    /** A parent company: its treasury, the loans its members owe it, and every member's own valuation. */
    public Valuation valuation(Group gr) {
        Map<String, Long> parts = new LinkedHashMap<>();
        parts.put("Treasury", gr.balance());
        if (gr.totalLoans() > 0) parts.put("Loans to members", gr.totalLoans());
        for (Company c : groups().members(gr)) parts.put(c.kind().display + " " + c.name(), valuation(c).total());
        return Valuation.of(parts);
    }

    // ============================================================ liquidation

    /**
     * What closing a company would pay out. {@code parts} are the signed lines (wallet, scrap, refunds, farewell
     * money...), then the fee, then what's left. {@code toGroup} is set when the payout goes into a parent company.
     */
    public record Liquidation(Map<String, Long> parts, long fee, long payout, Group toGroup, String effect) {
    }

    public Liquidation previewLiquidation(Company co) {
        Map<String, Long> parts = new LinkedHashMap<>();
        long gross;
        String effect;
        if (co instanceof Carrier c) {
            long scrap = 0;
            for (Tower t : plugin.store().towersOf(c.id())) scrap += share(towerCost(t), s().towerScrapPercent);
            long stock = share(stockCost(c), s().stockRefundPercent);
            parts.put("Wallet", Math.max(0, c.balance()));
            parts.put("Tower scrap", scrap);
            parts.put("Unused stock", stock);
            gross = Math.max(0, c.balance()) + scrap + stock;
            long farewell = hostedMvnos(c).isEmpty() ? 0 : share(gross, s().mvnoFarewellPercent);
            if (farewell > 0) parts.put("MVNO farewell", -farewell);
            gross -= farewell;
            effect = "Towers come down, subscribers lose their SIMs.";
        } else if (co instanceof Manufacturer m) {
            long wallet = Math.max(0, m.balance());
            long refunds = Math.min(wallet, stockOwed(m).values().stream().mapToLong(Long::longValue).sum());
            parts.put("Wallet", wallet);
            if (refunds > 0) parts.put("Refunds for carriers' unsold stock", -refunds);
            gross = wallet - refunds;
            effect = "Models and research are lost. Its towers keep working.";
        } else if (co instanceof Isp isp) {
            long scrap = 0;
            for (Node n : plugin.broadband().store().nodesOf(isp.id())) scrap += share(nodeCost(n), s().towerScrapPercent);
            parts.put("Wallet", Math.max(0, isp.balance()));
            parts.put("Node scrap", scrap);
            gross = Math.max(0, isp.balance()) + scrap;
            effect = "Nodes come down, every customer loses their line.";
        } else {
            gross = 0;
            effect = "";
        }
        long fee = fee(gross, s().liquidationFeePercent);
        return new Liquidation(parts, fee, gross - fee, groups().groupOf(co), effect);
    }

    private List<Carrier> hostedMvnos(Carrier c) {
        List<Carrier> mvnos = new ArrayList<>();
        for (Agreement a : c.agreements().values()) {
            if (a.kind() != Agreement.Kind.MVNO) continue;
            Carrier m = plugin.store().carrier(a.partnerId());
            if (m != null) mvnos.add(m);
        }
        return mvnos;
    }

    /** What each carrier holds of this manufacturer's models, at their sale price (refunded on liquidation). */
    private Map<String, Long> stockOwed(Manufacturer m) {
        Map<String, Long> owed = new LinkedHashMap<>();
        for (Carrier c : plugin.store().carriers()) {
            long v = 0;
            for (var e : c.stock().entrySet()) {
                if (!e.getKey().startsWith(m.id() + ":")) continue;
                TowerModel tm = plugin.manufacturers().model(e.getKey());
                if (tm != null) v += plugin.manufacturers().price(tm) * e.getValue();
            }
            if (v > 0) owed.put(c.id(), v);
        }
        return owed;
    }

    /** Closes a company for good, after the owner asked. */
    public void liquidate(Player p, Company co) {
        if ((co instanceof Manufacturer m && m.system()) || !isOwner(p, co)) {
            Msg.send(p, "<red>Only the owner can liquidate <white><c></white>.", Msg.p("c", co.name()));
            return;
        }
        Liquidation l = close(co, "Liquidated " + co.name());
        Msg.send(p, "<yellow><c> is liquidated.</yellow> <gray><white><a></white> was paid into <w> (after the <white><f></white> fee).",
                Msg.p("c", co.name()), Msg.p("a", s().money(l.payout())), Msg.p("f", s().money(l.fee())),
                Msg.p("w", l.toGroup() == null ? "your wallet" : l.toGroup().name()));
    }

    /** Closes a company: pays creditors, takes everything down, pays the rest to the owner (or its parent company). */
    private Liquidation close(Company co, String note) {
        Liquidation l = previewLiquidation(co);
        UUID owner = co.owner();
        if (co instanceof Carrier c) closeCarrier(c, l);
        else if (co instanceof Manufacturer m) closeManufacturer(m);
        else if (co instanceof Isp isp) {
            isp.balance(0); // paid out below, not by deleteIsp
            plugin.broadband().deleteIsp(isp);
        }
        plugin.workforce().companyRemoved(co.ref());
        if (l.toGroup() != null) {
            groups().detach(l.toGroup(), co);
            groups().creditTreasury(l.toGroup(), l.payout(), note);
        } else if (owner != null) {
            PlayerProfile pr = prof(owner);
            pr.balance(pr.balance() + l.payout());
            pr.log(l.payout(), note);
            plugin.billing().payOverdue(owner);
        }
        dirty();
        return l;
    }

    private void closeCarrier(Carrier c, Liquidation l) {
        List<Carrier> mvnos = hostedMvnos(c);
        long farewell = -l.parts().getOrDefault("MVNO farewell", 0L);
        if (!mvnos.isEmpty() && farewell > 0) {
            long each = farewell / mvnos.size();
            for (Carrier m : mvnos) {
                m.balance(m.balance() + each);
                m.log(each, "Farewell from " + c.name());
                plugin.billing().notify(m.owner(), "<gold><c> has shut down and no longer hosts <m>.</gold> <gray>It paid <white><a></white> farewell money. Find a new host in Partners.",
                        Msg.p("c", c.name()), Msg.p("m", m.name()), Msg.p("a", s().money(each)));
            }
        }
        // Subscribers: SIM gone, number released, debts forgiven. Financed phones stay locked to the dead carrier.
        for (PlayerProfile pr : new ArrayList<>(plugin.store().profiles())) {
            boolean had = pr.has(c.id());
            boolean owed = pr.overdue().remove(c.id()) != null;
            if (had) plugin.store().unsubscribe(pr, c.id());
            if (had || owed) {
                boolean paperweight = pr.phoneLocks().containsValue(c.id());
                plugin.billing().notify(pr.player(), "<red><c> has shut down.</red> <gray>Your SIM and number are gone"
                        + (owed ? ", and your debt to them is cancelled" : "") + "."
                        + (paperweight ? " The phone you were paying off is locked to them for good." : ""), Msg.p("c", c.name()));
            }
        }
        for (Tower t : new ArrayList<>(plugin.store().towersOf(c.id()))) plugin.towers().remove(t, true);
        plugin.store().removeCarrier(c);
    }

    private void closeManufacturer(Manufacturer m) {
        Map<String, Long> owed = stockOwed(m);
        long total = owed.values().stream().mapToLong(Long::longValue).sum();
        long refunds = Math.min(Math.max(0, m.balance()), total);
        for (Carrier c : plugin.store().carriers()) {
            boolean had = c.stock().keySet().removeIf(k -> k.startsWith(m.id() + ":"));
            long refund = total == 0 ? 0 : owed.getOrDefault(c.id(), 0L) * refunds / total; // scaled down if short
            if (refund > 0) {
                c.balance(c.balance() + refund);
                c.log(refund, "Stock refund · " + m.name() + " closed");
            }
            if (had) plugin.billing().notify(c.owner(), "<gold><m> has closed.</gold> <gray>Your unsold towers from them were refunded <white><a></white>.",
                    Msg.p("m", m.name()), Msg.p("a", s().money(refund)));
        }
        plugin.store().removeManufacturer(m);
    }

    /** Closes a parent company: liquidates every member into the treasury, then pays the treasury out minus the fee. */
    public void liquidate(Player p, Group gr) {
        if (!groups().canManage(p, gr)) {
            Msg.send(p, "<red>Only the owner can liquidate <white><g></white>.", Msg.p("g", gr.name()));
            return;
        }
        int n = 0;
        for (Company c : groups().members(gr)) {
            close(c, "Liquidated " + c.name());
            n++;
        }
        long fee = fee(Math.max(0, gr.balance()), s().liquidationFeePercent);
        long payout = Math.max(0, gr.balance()) - fee;
        groups().removeGroup(gr);
        PlayerProfile pr = prof(gr.owner());
        pr.balance(pr.balance() + payout);
        pr.log(payout, "Liquidated " + gr.name());
        plugin.billing().payOverdue(gr.owner());
        dirty();
        Msg.send(p, "<yellow><g> is liquidated</yellow> <gray>with its <white><n></white> companies. <white><a></white> was paid into your wallet (after the <white><f></white> fee).",
                Msg.p("g", gr.name()), Msg.p("n", "" + n), Msg.p("a", s().money(payout)), Msg.p("f", s().money(fee)));
    }

    // ============================================================= bankruptcy

    private final Map<String, Integer> bankruptcyWarned = new HashMap<>();

    /** When a company went into default (upkeep unpaid), or 0 if it's paid up. */
    public long defaultSince(Company c) {
        if (c instanceof Carrier car) return car.unpaidSince();
        if (c instanceof Isp isp) return isp.unpaidSince();
        return 0;
    }

    /** When this company will be declared bankrupt, or 0 if it isn't in default (or bankruptcy is off). */
    public long bankruptAt(Company c) {
        var st = plugin.workforce().state(c);
        if (st.bankruptcyClock() == 0 || defaultSince(c) == 0 || s().bankruptcyDays <= 0) return 0;
        return st.bankruptcyClock() + (s().bankruptcyDays + (long) groups().bankruptcyExtraDays(c)) * DAY;
    }

    /**
     * Starts or stops the bankruptcy countdown. It starts when the company went into default, but never so far back
     * that it has under two days left: a company that was already in default (e.g. before an update) still gets both
     * warnings.
     */
    private void updateClock(Company c, long now) {
        var st = plugin.workforce().state(c);
        long since = defaultSince(c);
        if (since == 0) {
            if (st.bankruptcyClock() != 0) {
                st.bankruptcyClock(0);
                plugin.store().markDirty();
            }
            return;
        }
        if (st.bankruptcyClock() == 0) {
            long latestFair = now - Math.max(0, s().bankruptcyDays - 2) * DAY;
            st.bankruptcyClock(Math.max(since, latestFair));
            plugin.store().markDirty();
        }
    }

    /**
     * A carrier or ISP that stays in default (upkeep unpaid) for {@code companies.bankruptcy-days} (plus the parent
     * company's grace) is declared bankrupt and closed like a liquidation. The owner is warned two days and one day
     * before.
     */
    public void checkBankruptcies(long now) {
        for (Company c : plugin.workforce().budgeted()) {
            updateClock(c, now);
            long at = bankruptAt(c);
            String key = c.ref().key();
            if (at == 0) {
                bankruptcyWarned.remove(key);
                continue;
            }
            long left = at - now;
            int level = bankruptcyWarned.getOrDefault(key, 0);
            if (left <= 0) {
                bankruptcyWarned.remove(key);
                String name = c.name();
                UUID owner = c.owner();
                long days = Math.max(s().bankruptcyDays, (now - defaultSince(c)) / DAY);
                Liquidation l = close(c, "Bankruptcy of " + name);
                plugin.billing().notify(owner, "<dark_red><b><c> has been declared bankrupt</b></dark_red> <gray>after <white><d></white> days without paying its bills. Everything was sold off and <white><a></white> was left for <w>.",
                        Msg.p("c", name), Msg.p("d", "" + days), Msg.p("a", s().money(l.payout())),
                        Msg.p("w", l.toGroup() == null ? "you" : l.toGroup().name()));
                plugin.getLogger().info(c.kind().display + " " + name + " went bankrupt.");
            } else if (left <= DAY && level < 2) {
                bankruptcyWarned.put(key, 2);
                plugin.billing().notify(c.owner(), "<dark_red><b>Final warning:</b> <c> will be declared bankrupt in <white><t></white> unless its bills are paid. Deposit to save it.",
                        Msg.p("c", c.name()), Msg.p("t", BillingService.until(left)));
            } else if (left <= 2 * DAY && level < 1) {
                bankruptcyWarned.put(key, 1);
                plugin.billing().notify(c.owner(), "<red><c> is in default and will be declared bankrupt in <white><t></white> if its bills stay unpaid.",
                        Msg.p("c", c.name()), Msg.p("t", BillingService.until(left)));
            }
        }
    }

    // ================================================================== sales

    /** A company (ref key) or a parent company ("group:id") offered to a buyer. */
    private record Offer(String what, String name, UUID seller, long price, long expires) {
    }

    private final Map<UUID, Offer> offers = new HashMap<>(); // buyer -> offer

    public void offerSale(Player seller, Company c, Player buyer, long price) {
        long owes = groups().owes(c);
        if (owes > 0) {
            Msg.send(seller, "<red><c> owes its parent company <white><o></white>. Repay it before selling.",
                    Msg.p("c", c.name()), Msg.p("o", s().money(owes)));
            return;
        }
        offer(seller, c.ref().key(), c.name(), "the " + c.kind().display.toLowerCase(java.util.Locale.ROOT), buyer, price);
    }

    public void offerSale(Player seller, Group gr, Player buyer, long price) {
        offer(seller, "group:" + gr.id(), gr.name(), "the parent company (with its " + gr.members().size() + " companies)", buyer, price);
    }

    private void offer(Player seller, String what, String name, String kind, Player buyer, long price) {
        if (buyer.equals(seller)) {
            Msg.send(seller, "<red>You can't sell to yourself.");
            return;
        }
        if (price < 0) {
            Msg.send(seller, "<red>Enter a price of 0 or more.");
            return;
        }
        offers.put(buyer.getUniqueId(), new Offer(what, name, seller.getUniqueId(), price, System.currentTimeMillis() + OFFER_MS));
        Msg.send(seller, "<green>Offered <white><c></white> to <white><b></white> for <white><p></white>. The offer lasts 5 minutes.",
                Msg.p("c", name), Msg.p("b", buyer.getName()), Msg.p("p", s().money(price)));
        Msg.send(buyer, "<aqua><s></aqua> <gray>offers to sell you " + kind
                        + " <white><c></white> for <white><p></white>. <green><click:run_command:'/cell acceptsale'>[Accept]</click></green> <red><click:run_command:'/cell declinesale'>[Decline]</click></red>",
                Msg.p("s", seller.getName()), Msg.p("c", name), Msg.p("p", s().money(price)));
    }

    public void acceptSale(Player buyer) {
        Offer o = offers.remove(buyer.getUniqueId());
        if (o == null || o.expires() < System.currentTimeMillis()) {
            Msg.send(buyer, "<red>You have no open company offer.");
            return;
        }
        PlayerProfile b = prof(buyer.getUniqueId());
        if (b.balance() < o.price()) {
            Msg.send(buyer, "<red>The price is <white><p></white>; you have <white><b></white>.", Msg.p("p", s().money(o.price())),
                    Msg.p("b", s().money(b.balance())));
            return;
        }
        if (o.what().startsWith("group:")) {
            Group gr = groups().group(o.what().substring(6));
            if (gr == null || !gr.owner().equals(o.seller())) {
                Msg.send(buyer, "<red>That offer is no longer valid.");
                return;
            }
            gr.owner(buyer.getUniqueId());
            for (Company c : groups().members(gr)) {
                c.owner(buyer.getUniqueId());
                c.employees().remove(buyer.getUniqueId());
            }
        } else {
            Company c = groups().company(Company.Ref.parse(o.what()));
            if (c == null || !o.seller().equals(c.owner()) || groups().owes(c) > 0) {
                Msg.send(buyer, "<red>That offer is no longer valid.");
                return;
            }
            Group gr = groups().groupOf(c);
            if (gr != null) groups().detach(gr, c); // a single company leaves its parent when it's sold
            c.owner(buyer.getUniqueId());
            c.employees().remove(buyer.getUniqueId());
        }
        PlayerProfile seller = prof(o.seller());
        b.balance(b.balance() - o.price());
        b.log(-o.price(), "Bought " + o.name());
        seller.balance(seller.balance() + o.price());
        seller.log(o.price(), "Sold " + o.name());
        plugin.billing().payOverdue(o.seller());
        dirty();
        Msg.send(buyer, "<green>You now own <white><c></white>.", Msg.p("c", o.name()));
        plugin.billing().notify(o.seller(), "<green><b> bought <white><c></white> for <white><p></white>.",
                Msg.p("b", buyer.getName()), Msg.p("c", o.name()), Msg.p("p", s().money(o.price())));
    }

    public void declineSale(Player buyer) {
        Offer o = offers.remove(buyer.getUniqueId());
        Msg.send(buyer, o == null ? "<red>You have no open company offer." : "<gray>Declined.");
        if (o != null) {
            Player seller = Bukkit.getPlayer(o.seller());
            if (seller != null) Msg.send(seller, "<gray><b> declined your offer.", Msg.p("b", buyer.getName()));
        }
    }
}
