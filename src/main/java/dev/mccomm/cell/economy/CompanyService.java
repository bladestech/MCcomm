package dev.mccomm.cell.economy;

import dev.mccomm.cell.CellPlugin;
import dev.mccomm.cell.Settings;
import dev.mccomm.cell.band.Band;
import dev.mccomm.cell.model.Agreement;
import dev.mccomm.cell.model.Carrier;
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
 * Moving money between players and their companies, company valuations, selling a company to
 * another player, and liquidation (closing a carrier or manufacturer for good).
 */
public final class CompanyService {
    private static final long HOUR = 3_600_000L;
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

    /** What the carrier's unused stock cost to make (liquidation uses this, not the sale price). */
    private long stockCost(Carrier c) {
        long v = 0;
        for (var e : c.stock().entrySet()) {
            TowerModel tm = plugin.manufacturers().model(e.getKey());
            if (tm != null) v += plugin.manufacturers().cost(tm) * e.getValue();
        }
        return v;
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

    /** Company wallet → owner (true) or owner → company wallet (false), with the transfer fee taken off what arrives. */
    public void moveCarrier(Player p, Carrier c, long amount, boolean toOwner) {
        if (!c.owner().equals(p.getUniqueId()) && !p.hasPermission("mccomm.admin")) {
            Msg.send(p, "<red>Only the owner can move money in and out of <white><c></white>.", Msg.p("c", c.name()));
            return;
        }
        PlayerProfile me = prof(p.getUniqueId());
        long have = toOwner ? c.balance() : me.balance();
        if (amount <= 0 || have < amount) {
            Msg.send(p, "<red>Not enough: <white><b></white> available.", Msg.p("b", s().money(have)));
            return;
        }
        long owedWages = plugin.workforce().owedWages(c.id());
        if (toOwner && owedWages > 0) {
            Msg.send(p, "<red><c> owes <white><o></white> in wages and rent. Pay your staff before taking money out.",
                    Msg.p("c", c.name()), Msg.p("o", s().money(owedWages)));
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
            if (c.balance() < plugin.billing().upkeepCost(c)) {
                Msg.send(p, "<gold>Careful: <white><c></white> no longer covers its next upkeep.", Msg.p("c", c.name()));
            }
        } else {
            me.balance(me.balance() - amount);
            me.log(-amount, "To " + c.name());
            c.balance(c.balance() + arrives);
            c.log(arrives, "From " + p.getName() + " (fee " + s().money(fee) + ")");
            plugin.billing().retryUpkeep(c);
        }
        plugin.store().markDirty();
        Msg.send(p, "<green>Moved <white><a></white> <dir> <white><c></white>; <white><r></white> arrived after the <white><f></white> fee.",
                Msg.p("a", s().money(amount)), Msg.p("dir", toOwner ? "out of" : "into"), Msg.p("c", c.name()),
                Msg.p("r", s().money(arrives)), Msg.p("f", s().money(fee)));
    }

    public void moveManufacturer(Player p, Manufacturer m, long amount, boolean toOwner) {
        if (m.system() || (!p.getUniqueId().equals(m.owner()) && !p.hasPermission("mccomm.admin"))) {
            Msg.send(p, "<red>Only the owner can move money in and out of <white><m></white>.", Msg.p("m", m.name()));
            return;
        }
        PlayerProfile me = prof(p.getUniqueId());
        long have = toOwner ? m.balance() : me.balance();
        if (amount <= 0 || have < amount) {
            Msg.send(p, "<red>Not enough: <white><b></white> available.", Msg.p("b", s().money(have)));
            return;
        }
        long fee = fee(amount, s().transferFeePercent);
        long arrives = amount - fee;
        if (toOwner) {
            m.balance(m.balance() - amount);
            m.log(-amount, "Withdrawn by " + p.getName());
            me.balance(me.balance() + arrives);
            me.log(arrives, "From " + m.name() + " (fee " + s().money(fee) + ")");
            plugin.billing().payOverdue(p.getUniqueId());
        } else {
            me.balance(me.balance() - amount);
            me.log(-amount, "To " + m.name());
            m.balance(m.balance() + arrives);
            m.log(arrives, "From " + p.getName() + " (fee " + s().money(fee) + ")");
        }
        plugin.store().markDirty();
        Msg.send(p, "<green>Moved <white><a></white> <dir> <white><m></white>; <white><r></white> arrived after the <white><f></white> fee.",
                Msg.p("a", s().money(amount)), Msg.p("dir", toOwner ? "out of" : "into"), Msg.p("m", m.name()),
                Msg.p("r", s().money(arrives)), Msg.p("f", s().money(fee)));
    }

    // ============================================================== valuation

    /** Value lines (label → credits) and a total. */
    public record Valuation(Map<String, Long> parts, long total) {
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

    /** Net plan income minus upkeep per day. */
    public long netPerDay(Carrier c) {
        double income = 0;
        for (PlayerProfile pr : plugin.store().profiles()) {
            Subscription sub = pr.sub(c.id());
            if (sub == null || sub.planId() == null) continue;
            Plan plan = c.plans().get(sub.planId());
            if (plan == null || plan.prepaid()) continue;
            income += plan.price() * 24.0 / plan.periodHours();
        }
        income += plugin.simulation().revenuePerDay(c);
        double upkeep = (plugin.billing().upkeepCost(c) + c.mvnoOwed()) * 24.0 / s().upkeepPeriodHours;
        upkeep += plugin.workforce().dailyCost(c.id());
        return Math.round(income - upkeep);
    }

    public Valuation valuation(Carrier c) {
        Map<String, Long> parts = new LinkedHashMap<>();
        parts.put("Wallet", c.balance());
        long towers = 0;
        for (Tower t : plugin.store().towersOf(c.id())) towers += towerCost(t);
        parts.put("Towers (" + plugin.store().towersOf(c.id()).size() + ")", towers);
        parts.put("Tower stock", stockValue(c));
        long owed = plugin.workforce().owedWages(c.id());
        if (owed > 0) parts.put("Wages owed", -owed);
        long net = netPerDay(c);
        parts.put(s().valuationDays + " days of net income", Math.max(0, net) * s().valuationDays);
        return new Valuation(parts, parts.values().stream().mapToLong(Long::longValue).sum());
    }

    public Valuation valuation(Manufacturer m) {
        Map<String, Long> parts = new LinkedHashMap<>();
        parts.put("Wallet", m.balance());
        long research = 0;
        for (int g = 2; g <= m.researchedGen(); g++) research += s().researchGenCost.getOrDefault(g, 0L);
        for (Band b : m.researchedBands()) research += s().researchBandCost.getOrDefault(b, 0L);
        parts.put("Research", Math.round(research / Math.max(0.01, s().rpPerCredit)));
        long perDay = Math.round(plugin.manufacturers().upkeepIncome(m) * 24.0 / s().upkeepPeriodHours);
        parts.put(s().valuationDays + " days of upkeep share", perDay * s().valuationDays);
        return new Valuation(parts, parts.values().stream().mapToLong(Long::longValue).sum());
    }

    // ============================================================ liquidation

    /** What closing a carrier would pay out. */
    public record CarrierLiquidation(long wallet, long scrap, long stock, long staff, long farewell, List<Carrier> mvnos, long fee, long payout) {
        public long gross() { return Math.max(0, wallet + scrap + stock - staff); }
    }

    public CarrierLiquidation previewLiquidation(Carrier c) {
        long scrap = 0;
        for (Tower t : plugin.store().towersOf(c.id())) scrap += share(towerCost(t), s().towerScrapPercent);
        long stock = share(stockCost(c), s().stockRefundPercent);
        // Wages and rent owed, plus severance for every employee, are settled first.
        long staff = plugin.workforce().liquidationCost(c.id());
        long gross = Math.max(0, Math.max(0, c.balance()) + scrap + stock - staff);
        List<Carrier> mvnos = new ArrayList<>();
        for (Agreement a : c.agreements().values()) {
            if (a.kind() != Agreement.Kind.MVNO) continue;
            Carrier m = plugin.store().carrier(a.partnerId());
            if (m != null) mvnos.add(m);
        }
        long farewell = mvnos.isEmpty() ? 0 : share(gross, s().mvnoFarewellPercent);
        long fee = fee(gross - farewell, s().liquidationFeePercent);
        return new CarrierLiquidation(c.balance(), scrap, stock, staff, farewell, mvnos, fee, gross - farewell - fee);
    }

    /** Closes a carrier for good. */
    public void liquidate(Player p, Carrier c) {
        if (!c.owner().equals(p.getUniqueId()) && !p.hasPermission("mccomm.admin")) {
            Msg.send(p, "<red>Only the owner can liquidate <white><c></white>.", Msg.p("c", c.name()));
            return;
        }
        CarrierLiquidation l = close(c, "Liquidated " + c.name());
        Msg.send(p, "<yellow><c> is liquidated.</yellow> <gray><white><a></white> was paid into your wallet (after the <white><f></white> fee"
                        + (l.farewell() > 0 ? " and <white><fw></white> farewell money to MVNOs" : "") + ").",
                Msg.p("c", c.name()), Msg.p("a", s().money(l.payout())), Msg.p("f", s().money(l.fee())), Msg.p("fw", s().money(l.farewell())));
    }

    /** Closes a carrier for good: pays MVNOs and creditors, drops subscribers and towers, pays the rest to the owner. */
    private CarrierLiquidation close(Carrier c, String note) {
        CarrierLiquidation l = previewLiquidation(c);
        // Farewell money to hosted MVNOs.
        if (!l.mvnos().isEmpty() && l.farewell() > 0) {
            long each = l.farewell() / l.mvnos().size();
            for (Carrier m : l.mvnos()) {
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
        // Towers come down.
        for (Tower t : new ArrayList<>(plugin.store().towersOf(c.id()))) plugin.towers().remove(t, true);
        plugin.store().removeCarrier(c);
        PlayerProfile owner = prof(c.owner());
        owner.balance(owner.balance() + l.payout());
        owner.log(l.payout(), note);
        plugin.billing().payOverdue(c.owner());
        plugin.store().markDirty();
        return l;
    }

    // ============================================================= bankruptcy

    private final Map<String, Integer> bankruptcyWarned = new java.util.HashMap<>();

    /** When a carrier went into default (upkeep or wages unpaid), or 0 if it's paid up. */
    public long defaultSince(Carrier c) {
        long since = c.unpaidSince();
        long owed = plugin.workforce().owedSince(c.id());
        if (owed > 0 && (since == 0 || owed < since)) since = owed;
        return since;
    }

    /** When this carrier will be declared bankrupt, or 0 if it isn't in default (or bankruptcy is off). */
    public long bankruptAt(Carrier c) {
        var st = plugin.workforce().state(c.id());
        if (st.bankruptcyClock() == 0 || defaultSince(c) == 0 || s().bankruptcyDays <= 0) return 0;
        return st.bankruptcyClock() + s().bankruptcyDays * 86_400_000L;
    }

    /**
     * Starts or stops the bankruptcy countdown. It starts when the carrier went into default, but
     * never so far back that it has under two days left: a carrier that was already in default
     * (e.g. before an update) still gets both warnings.
     */
    private void updateClock(Carrier c, long now) {
        var st = plugin.workforce().state(c.id());
        long since = defaultSince(c);
        if (since == 0) {
            if (st.bankruptcyClock() != 0) {
                st.bankruptcyClock(0);
                plugin.store().markDirty();
            }
            return;
        }
        if (st.bankruptcyClock() == 0) {
            long latestFair = now - Math.max(0, s().bankruptcyDays - 2) * 86_400_000L;
            st.bankruptcyClock(Math.max(since, latestFair));
            plugin.store().markDirty();
        }
    }

    /**
     * A carrier that stays in default (upkeep or wages unpaid) for {@code companies.bankruptcy-days}
     * is declared bankrupt and closed like a liquidation: creditors (staff) first, then MVNOs, the
     * rest to the owner. The owner is warned two days and one day before.
     */
    public void checkBankruptcies(long now) {
        for (Carrier c : new ArrayList<>(plugin.store().carriers())) {
            updateClock(c, now);
            long at = bankruptAt(c);
            if (at == 0) {
                bankruptcyWarned.remove(c.id());
                continue;
            }
            long left = at - now;
            int level = bankruptcyWarned.getOrDefault(c.id(), 0);
            if (left <= 0) {
                bankruptcyWarned.remove(c.id());
                String name = c.name();
                java.util.UUID owner = c.owner();
                long days = Math.max(s().bankruptcyDays, (now - defaultSince(c)) / 86_400_000L);
                CarrierLiquidation l = close(c, "Bankruptcy of " + name);
                plugin.billing().notify(owner, "<dark_red><b><c> has been declared bankrupt</b></dark_red> <gray>after <white><d></white> days without paying its bills. Its towers were sold off, staff and MVNOs paid, and <white><a></white> was left for you.",
                        Msg.p("c", name), Msg.p("d", "" + days), Msg.p("a", s().money(l.payout())));
                plugin.getLogger().info("Carrier " + name + " went bankrupt.");
            } else if (left <= 86_400_000L && level < 2) {
                bankruptcyWarned.put(c.id(), 2);
                plugin.billing().notify(c.owner(), "<dark_red><b>Final warning:</b> <c> will be declared bankrupt in <white><t></white> unless its bills are paid. Deposit to save it.",
                        Msg.p("c", c.name()), Msg.p("t", dev.mccomm.cell.economy.BillingService.until(left)));
            } else if (left <= 2 * 86_400_000L && level < 1) {
                bankruptcyWarned.put(c.id(), 1);
                plugin.billing().notify(c.owner(), "<red><c> is in default and will be declared bankrupt in <white><t></white> if its bills stay unpaid.",
                        Msg.p("c", c.name()), Msg.p("t", dev.mccomm.cell.economy.BillingService.until(left)));
            }
        }
    }

    /** What closing a manufacturer would pay out. Unsold stock carriers hold is refunded first. */
    public record ManufacturerLiquidation(long wallet, long refunds, Map<String, Long> refundTo, long fee, long payout) {
    }

    public ManufacturerLiquidation previewLiquidation(Manufacturer m) {
        Map<String, Long> owed = new LinkedHashMap<>();
        long total = 0;
        for (Carrier c : plugin.store().carriers()) {
            long v = 0;
            for (var e : c.stock().entrySet()) {
                if (!e.getKey().startsWith(m.id() + ":")) continue;
                TowerModel tm = plugin.manufacturers().model(e.getKey());
                if (tm != null) v += plugin.manufacturers().price(tm) * e.getValue();
            }
            if (v > 0) {
                owed.put(c.id(), v);
                total += v;
            }
        }
        long wallet = Math.max(0, m.balance());
        long refunds = Math.min(wallet, total);
        // Scale refunds down if the wallet can't cover them all.
        Map<String, Long> refundTo = new HashMap<>();
        for (var e : owed.entrySet()) refundTo.put(e.getKey(), total == 0 ? 0 : e.getValue() * refunds / total);
        long rest = wallet - refunds;
        long fee = fee(rest, s().liquidationFeePercent);
        return new ManufacturerLiquidation(wallet, refunds, refundTo, fee, rest - fee);
    }

    public void liquidate(Player p, Manufacturer m) {
        if (m.system() || (!p.getUniqueId().equals(m.owner()) && !p.hasPermission("mccomm.admin"))) {
            Msg.send(p, "<red>Only the owner can liquidate <white><m></white>.", Msg.p("m", m.name()));
            return;
        }
        ManufacturerLiquidation l = previewLiquidation(m);
        for (Carrier c : plugin.store().carriers()) {
            boolean had = c.stock().keySet().removeIf(k -> k.startsWith(m.id() + ":"));
            long refund = l.refundTo().getOrDefault(c.id(), 0L);
            if (refund > 0) {
                c.balance(c.balance() + refund);
                c.log(refund, "Stock refund · " + m.name() + " closed");
            }
            if (had) plugin.billing().notify(c.owner(), "<gold><m> has closed.</gold> <gray>Your unsold towers from them were refunded <white><a></white>.",
                    Msg.p("m", m.name()), Msg.p("a", s().money(refund)));
        }
        plugin.store().removeManufacturer(m);
        PlayerProfile owner = prof(m.owner());
        owner.balance(owner.balance() + l.payout());
        owner.log(l.payout(), "Liquidated " + m.name());
        plugin.billing().payOverdue(m.owner());
        plugin.store().markDirty();
        Msg.send(p, "<yellow><m> is liquidated.</yellow> <gray><white><a></white> was paid into your wallet (after <white><r></white> in stock refunds and the <white><f></white> fee). Its towers keep working.",
                Msg.p("m", m.name()), Msg.p("a", s().money(l.payout())), Msg.p("r", s().money(l.refunds())), Msg.p("f", s().money(l.fee())));
    }

    // ================================================================== sales

    private record Offer(boolean carrier, String companyId, UUID seller, long price, long expires) {
    }

    private final Map<UUID, Offer> offers = new HashMap<>(); // buyer -> offer

    public void offerSale(Player seller, boolean carrier, String companyId, String companyName, Player buyer, long price) {
        if (buyer.equals(seller)) {
            Msg.send(seller, "<red>You can't sell to yourself.");
            return;
        }
        if (price < 0) {
            Msg.send(seller, "<red>Enter a price of 0 or more.");
            return;
        }
        offers.put(buyer.getUniqueId(), new Offer(carrier, companyId, seller.getUniqueId(), price, System.currentTimeMillis() + OFFER_MS));
        Msg.send(seller, "<green>Offered <white><c></white> to <white><b></white> for <white><p></white>. The offer lasts 5 minutes.",
                Msg.p("c", companyName), Msg.p("b", buyer.getName()), Msg.p("p", s().money(price)));
        Msg.send(buyer, "<aqua><s></aqua> <gray>offers to sell you the " + (carrier ? "carrier" : "manufacturer")
                        + " <white><c></white> for <white><p></white>. <green><click:run_command:'/cell acceptsale'>[Accept]</click></green> <red><click:run_command:'/cell declinesale'>[Decline]</click></red>",
                Msg.p("s", seller.getName()), Msg.p("c", companyName), Msg.p("p", s().money(price)));
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
        String name;
        if (o.carrier()) {
            Carrier c = plugin.store().carrier(o.companyId());
            if (c == null || !c.owner().equals(o.seller())) {
                Msg.send(buyer, "<red>That offer is no longer valid.");
                return;
            }
            c.owner(buyer.getUniqueId());
            c.employees().remove(buyer.getUniqueId());
            name = c.name();
        } else {
            Manufacturer m = plugin.store().manufacturer(o.companyId());
            if (m == null || !o.seller().equals(m.owner())) {
                Msg.send(buyer, "<red>That offer is no longer valid.");
                return;
            }
            m.owner(buyer.getUniqueId());
            m.employees().remove(buyer.getUniqueId());
            name = m.name();
        }
        PlayerProfile seller = prof(o.seller());
        b.balance(b.balance() - o.price());
        b.log(-o.price(), "Bought " + name);
        seller.balance(seller.balance() + o.price());
        seller.log(o.price(), "Sold " + name);
        plugin.billing().payOverdue(o.seller());
        plugin.store().markDirty();
        Msg.send(buyer, "<green>You now own <white><c></white>.", Msg.p("c", name));
        plugin.billing().notify(o.seller(), "<green><b> bought <white><c></white> for <white><p></white>.",
                Msg.p("b", buyer.getName()), Msg.p("c", name), Msg.p("p", s().money(o.price())));
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
