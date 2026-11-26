package dev.mccomm.cell.economy;

import dev.mccomm.cell.CellPlugin;
import dev.mccomm.cell.Settings;
import dev.mccomm.cell.model.Carrier;
import dev.mccomm.cell.model.Plan;
import dev.mccomm.cell.model.PlayerProfile;
import dev.mccomm.cell.model.Subscription;
import dev.mccomm.cell.model.Tower;
import dev.mccomm.cell.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Wallets, phone purchases, plan billing (with warnings and overdue debt) and tower upkeep
 * (with decay when a carrier can't pay). Runs on real time, also for offline players.
 */
public final class BillingService implements Listener {
    private static final long HOUR = 3_600_000L;

    private final CellPlugin plugin;
    private final Map<String, Integer> lastStage = new HashMap<>();

    public BillingService(CellPlugin plugin) {
        this.plugin = plugin;
    }

    private Settings s() {
        return plugin.settings();
    }

    private PlayerProfile prof(UUID id) {
        return plugin.store().profile(id);
    }

    // ================================================================ setup

    /** Gives carriers a default plan and an upkeep schedule; puts pre-billing subscribers on a plan. */
    public void ensureDefaults() {
        long now = System.currentTimeMillis();
        for (Carrier c : plugin.store().carriers()) {
            if (c.plans().isEmpty()) addDefaultPlan(c);
            if (c.nextUpkeep() == 0) c.nextUpkeep(now + s().upkeepPeriodHours * HOUR);
        }
        for (PlayerProfile p : plugin.store().profiles()) {
            for (String cid : allSims(p)) {
                if (p.sub(cid) != null) continue;
                Carrier c = plugin.store().carrier(cid);
                if (c == null || c.plans().isEmpty()) continue;
                Plan first = c.plans().values().iterator().next();
                p.subs().put(cid, new Subscription(first.id(), now + first.periodMillis())); // first period on the house
            }
        }
        plugin.store().markDirty();
    }

    public Plan addDefaultPlan(Carrier c) {
        Plan p = new Plan(newPlanId(c), s().defaultPlanName, s().defaultPlanPrice, s().defaultPlanPeriod);
        c.plans().put(p.id(), p);
        plugin.store().markDirty();
        return p;
    }

    public String newPlanId(Carrier c) {
        int i = 1;
        while (c.plans().containsKey("p" + i)) i++;
        return "p" + i;
    }

    private static List<String> allSims(PlayerProfile p) {
        List<String> out = new ArrayList<>(p.active());
        out.addAll(p.inactive());
        return out;
    }

    // ============================================================== deposits

    /** Credits an item stack is worth (0 if it isn't a currency ore). */
    public long valueOf(ItemStack it) {
        if (it == null) return 0;
        return s().oreValues.getOrDefault(it.getType(), 0L) * it.getAmount();
    }

    public void depositPlayer(Player p, long amount) {
        if (amount <= 0) return;
        PlayerProfile prof = prof(p.getUniqueId());
        prof.balance(prof.balance() + amount);
        prof.log(amount, "Deposit");
        Msg.send(p, "<green>Deposited <white><a></white>. Balance: <white><b></white>.",
                Msg.p("a", s().money(amount)), Msg.p("b", s().money(prof.balance())));
        payOverdue(p.getUniqueId());
        plugin.store().markDirty();
    }

    /** Pays overdue debts from the balance, as far as it goes. */
    public void payOverdue(UUID player) {
        PlayerProfile prof = prof(player);
        if (prof.overdue().isEmpty()) return;
        for (String cid : new ArrayList<>(prof.overdue().keySet())) {
            long owed = prof.overdue().get(cid);
            long pay = Math.min(owed, prof.balance());
            if (pay <= 0) break;
            prof.balance(prof.balance() - pay);
            prof.log(-pay, "Overdue payment");
            Carrier c = plugin.store().carrier(cid);
            if (c != null) {
                c.balance(c.balance() + pay);
                c.log(pay, "Overdue payment from " + name(player));
            }
            if (pay >= owed) {
                prof.overdue().remove(cid);
                for (String phoneId : new ArrayList<>(prof.blacklist().keySet())) {
                    if (!cid.equals(prof.blacklist().get(phoneId))) continue;
                    prof.blacklist().remove(phoneId);
                    PhoneModel ph = s().phone(phoneId);
                    Player on = Bukkit.getPlayer(player);
                    if (on != null) Msg.send(on, "<green>Your <white><n></white> is no longer blacklisted. It's yours now.",
                            Msg.p("n", ph == null ? phoneId : ph.name()));
                }
            } else {
                prof.overdue().put(cid, owed - pay);
            }
        }
        Player online = Bukkit.getPlayer(player);
        if (online != null) {
            if (prof.overdue().isEmpty()) Msg.send(online, "<green>Your overdue balance is paid off. You can choose a plan again.");
            else Msg.send(online, "<yellow>Paid part of your overdue balance. Still owed: <white><o></white>.",
                    Msg.p("o", s().money(prof.totalOverdue())));
        }
        plugin.store().markDirty();
    }

    public void depositCarrier(Player who, Carrier c, long amount) {
        if (amount <= 0) return;
        c.balance(c.balance() + amount);
        c.log(amount, "Deposit by " + who.getName());
        Msg.send(who, "<green>Deposited <white><a></white> into <white><c></white>. Balance: <white><b></white>.",
                Msg.p("a", s().money(amount)), Msg.p("c", c.name()), Msg.p("b", s().money(c.balance())));
        retryUpkeep(c);
        plugin.store().markDirty();
    }

    /** If a carrier is behind on upkeep, tries to pay it now (after money came in) and repairs its towers. */
    public void retryUpkeep(Carrier c) {
        if (c.unpaidSince() != 0) {
            chargeUpkeep(c, System.currentTimeMillis(), true);
            if (c.unpaidSince() == 0) {
                lastStage.put(c.id(), 0);
                for (Tower t : plugin.store().towersOf(c.id())) plugin.towers().refresh(t);
            }
        }
    }

    // ================================================================ phones

    public void buyOrUse(Player p, PhoneModel m) {
        PlayerProfile prof = prof(p.getUniqueId());
        if (!prof.ownedPhones().contains(m.id())) {
            if (prof.balance() < m.price()) {
                Msg.send(p, "<red>The <white><n></white> costs <white><price></white>; you have <white><b></white>. Deposit ores in your wallet.",
                        Msg.p("n", m.name()), Msg.p("price", s().money(m.price())), Msg.p("b", s().money(prof.balance())));
                return;
            }
            prof.balance(prof.balance() - m.price());
            prof.log(-m.price(), "Bought " + m.name());
            prof.ownedPhones().add(m.id());
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
            Msg.send(p, "<green>You bought the <white><n></white>!", Msg.p("n", m.name()));
        }
        usePhone(p, m);
    }

    /** Switches the phone in use. Extra active SIMs beyond its slots move to inactive. */
    public void usePhone(Player p, PhoneModel m) {
        PlayerProfile prof = prof(p.getUniqueId());
        prof.phone(m.id());
        List<String> moved = new ArrayList<>();
        while (prof.active().size() > m.sims()) {
            String cid = prof.active().removeLast();
            prof.inactive().add(cid); // may exceed max-inactive briefly; that's fine
            moved.add(cid);
        }
        if (prof.dataSim() != null && !prof.active().contains(prof.dataSim())) prof.dataSim(null);
        plugin.store().markDirty();
        Msg.send(p, "<green>Now using the <white><n></white> (up to <white><band></white>, <white><sims></white> SIM<plural>).",
                Msg.p("n", m.name()), Msg.p("band", s().spec(m.maxBand()).name()), Msg.p("sims", "" + m.sims()),
                Msg.p("plural", m.sims() == 1 ? "" : "s"));
        for (String cid : moved) {
            Carrier c = plugin.store().carrier(cid);
            Msg.send(p, "<yellow><c> moved to inactive SIMs (this phone has fewer slots).", Msg.p("c", c == null ? cid : c.name()));
        }
    }

    /** Why this phone can't be sold or traded in, or null if it can. */
    public String cantPart(PlayerProfile prof, PhoneModel m) {
        if (!prof.ownedPhones().contains(m.id())) return "You don't own a " + m.name() + ".";
        if (paperweight(prof, m)) return null; // worthless, but you can get rid of it
        if (prof.phoneLocks().containsKey(m.id())) return "You're still paying off your " + m.name() + ".";
        if (prof.blacklist().containsKey(m.id())) return "Your " + m.name() + " is blacklisted.";
        return null;
    }

    /** A phone locked or blacklisted by a carrier that no longer exists: it'll never work again. */
    public boolean paperweight(PlayerProfile prof, PhoneModel m) {
        String lock = prof.phoneLocks().get(m.id());
        String bl = prof.blacklist().get(m.id());
        return (lock != null && plugin.store().carrier(lock) == null) || (bl != null && plugin.store().carrier(bl) == null);
    }

    /** What selling this phone back pays: the resale value, or almost nothing for a paperweight. */
    public long sellValue(PlayerProfile prof, PhoneModel m) {
        if (prof.giftedPhones().contains(m.id())) return 0; // you didn't pay for it
        return paperweight(prof, m) ? (long) Math.floor(m.price() * s().paperweightPercent / 100.0) : s().resaleValue(m);
    }

    private void removePhone(Player p, PlayerProfile prof, PhoneModel m) {
        prof.ownedPhones().remove(m.id());
        if (m.id().equals(prof.phone())) {
            prof.phone(null);
            Msg.send(p, "<yellow>That was the phone you were using. Switch to another in the eShop to get service.");
        }
        plugin.store().markDirty();
    }

    /** Sells an owned phone back to the eShop for the resale percentage. */
    public void sellPhone(Player p, PhoneModel m) {
        PlayerProfile prof = prof(p.getUniqueId());
        String why = cantPart(prof, m);
        if (why != null) {
            Msg.send(p, "<red>" + Msg.escape(why));
            return;
        }
        long value = sellValue(prof, m);
        removePhone(p, prof, m);
        prof.phoneLocks().remove(m.id());
        prof.blacklist().remove(m.id());
        prof.giftedPhones().remove(m.id());
        prof.balance(prof.balance() + value);
        prof.log(value, "Sold " + m.name());
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.2f);
        Msg.send(p, "<green>Sold your <white><n></white> for <white><v></white>.", Msg.p("n", m.name()), Msg.p("v", s().money(value)));
    }

    /** What a carrier's trade-in program credits for this phone. */
    public long tradeInValue(Carrier c, PhoneModel m) {
        return Math.round(m.price() * c.tradeInPercent() / 100.0);
    }

    /**
     * Trades an owned phone in to a carrier you're paying a phone off with. Its value comes off the
     * loan and the instalment is spread over the same number of remaining payments. The carrier gets
     * the resale value for the phone.
     */
    public void tradeIn(Player p, Carrier c, PhoneModel m) {
        PlayerProfile prof = prof(p.getUniqueId());
        Subscription sub = prof.sub(c.id());
        if (!c.tradeIn()) {
            Msg.send(p, "<red><c> doesn't run a trade-in program.", Msg.p("c", c.name()));
            return;
        }
        if (sub == null || !sub.financing()) {
            Msg.send(p, "<red>You're not paying off a phone with <white><c></white>.", Msg.p("c", c.name()));
            return;
        }
        String why = cantPart(prof, m);
        if (why == null && paperweight(prof, m)) why = "A paperweight phone is worthless as a trade-in.";
        if (why != null) {
            Msg.send(p, "<red>" + Msg.escape(why));
            return;
        }
        long value = Math.min(tradeInValue(c, m), sub.financeRemaining());
        long paymentsLeft = Math.max(1, (sub.financeRemaining() + sub.financeInstallment() - 1) / Math.max(1, sub.financeInstallment()));
        removePhone(p, prof, m);
        long resale = prof.giftedPhones().remove(m.id()) ? 0 : s().resaleValue(m);
        c.balance(c.balance() + resale);
        c.log(resale, "Resold traded-in " + m.name());
        c.log(-value, "Trade-in credit · " + p.getName());
        prof.log(value, "Traded in " + m.name() + " to " + c.name());
        sub.financeRemaining(sub.financeRemaining() - value);
        if (sub.financeRemaining() <= 0) {
            PhoneModel financed = s().phone(sub.financePhone());
            finishFinancing(prof, sub);
            Msg.send(p, "<green>Traded in your <white><n></white>: that paid off your <white><f></white> completely. It's unlocked and yours.",
                    Msg.p("n", m.name()), Msg.p("f", financed == null ? "phone" : financed.name()));
        } else {
            sub.financeInstallment((sub.financeRemaining() + paymentsLeft - 1) / paymentsLeft);
            Msg.send(p, "<green>Traded in your <white><n></white> for <white><v></white> off your phone loan. Still owed <white><r></white>, now <white><i></white> per period.",
                    Msg.p("n", m.name()), Msg.p("v", s().money(value)), Msg.p("r", s().money(sub.financeRemaining())),
                    Msg.p("i", s().money(sub.financeInstallment())));
        }
        plugin.store().markDirty();
    }

    public PhoneModel phoneOf(Player p) {
        return s().phone(prof(p.getUniqueId()).phone());
    }

    /** Active SIM slots on the player's phone (1 without a phone, so data stays sensible). */
    public int simSlots(Player p) {
        PhoneModel m = phoneOf(p);
        return m == null ? 1 : m.sims();
    }

    // ================================================================= plans

    /** The terms the subscriber is on right now: their grandfathered terms if a change is pending, else the live plan. */
    public Plan planOf(PlayerProfile prof, String carrierId) {
        Subscription sub = prof.sub(carrierId);
        Plan live = livePlan(prof, carrierId);
        if (live == null) return null;
        return sub.grandfathered() != null ? sub.grandfathered() : live;
    }

    /** The carrier's current version of the subscriber's plan (what applies from their next renewal). */
    public Plan livePlan(PlayerProfile prof, String carrierId) {
        Subscription sub = prof.sub(carrierId);
        Carrier c = plugin.store().carrier(carrierId);
        return sub == null || c == null || sub.planId() == null ? null : c.plans().get(sub.planId());
    }

    /** True if this SIM's plan is prepaid and has run out. */
    public boolean expired(PlayerProfile prof, String carrierId) {
        Plan plan = planOf(prof, carrierId);
        Subscription sub = prof.sub(carrierId);
        return plan != null && plan.prepaid() && System.currentTimeMillis() >= sub.nextBill();
    }

    /** What the next automatic charge will be (plan + phone instalment). 0 for prepaid. */
    public long nextCharge(PlayerProfile prof, String carrierId) {
        Plan plan = livePlan(prof, carrierId); // renewal moves them onto the live terms
        Subscription sub = prof.sub(carrierId);
        if (plan == null || plan.prepaid()) return 0;
        long price = plugin.sales().price(plan);
        return price - plugin.broadband().carrierDiscount(prof.player(), carrierId, price) + (sub.financing() ? Math.min(sub.financeInstallment(), sub.financeRemaining()) : 0);
    }

    private boolean blockIfOverdue(Player p) {
        PlayerProfile prof = prof(p.getUniqueId());
        if (prof.overdue().isEmpty()) return false;
        Msg.send(p, "<red>You have an overdue balance of <white><o></white>. Pay it off (deposit in your wallet) first.",
                Msg.p("o", s().money(prof.totalOverdue())));
        return true;
    }

    private boolean afford(Player p, long cost, String what) {
        PlayerProfile prof = prof(p.getUniqueId());
        if (prof.balance() >= cost) return true;
        Msg.send(p, "<red><w> costs <white><price></white>; you have <white><b></white>. Deposit ores in your wallet first.",
                Msg.p("w", what), Msg.p("price", s().money(cost)), Msg.p("b", s().money(prof.balance())));
        return false;
    }

    /** Charges the player and credits the carrier, with ledger lines on both sides. */
    private void transfer(UUID player, Carrier c, long amount, String note) {
        if (amount <= 0) return;
        PlayerProfile prof = prof(player);
        prof.balance(prof.balance() - amount);
        prof.log(-amount, c.name() + " · " + note);
        c.balance(c.balance() + amount);
        c.log(amount, note + " · " + name(player));
    }

    /** Plan revenue: like transfer, and an MVNO also accrues its host's revenue share. */
    private void collect(UUID player, Carrier c, long amount, String note) {
        transfer(player, c, amount, note);
        if (amount > 0 && c.mvno()) {
            var deal = plugin.partners().mvnoHost(c);
            if (deal != null) c.mvnoOwed(c.mvnoOwed() + (long) Math.ceil(amount * deal.agreement().sharePercent() / 100.0));
        }
    }

    private final Map<String, Double> roamingAccrual = new HashMap<>();

    /** Data use on a specific SIM: plan allowance, plus roaming charges if it's on a partner's tower. */
    public DataResult useDataOn(UUID player, dev.mccomm.cell.signal.SignalService.SimStatus sim, double mb) {
        if (plugin.broadband().offload(player, mb)) return DataResult.OK; // on home Wi-Fi: no mobile data used
        DataResult r = useData(player, sim.carrier().id(), mb);
        if (r != DataResult.BLOCKED && sim.link() != null) plugin.workforce().addTraffic(sim.link().tower(), mb);
        Carrier host = sim.roamingOn();
        if (r == DataResult.BLOCKED || host == null) return r;
        Carrier home = sim.carrier();
        var deal = plugin.partners().roamingDeal(host, home);
        if (deal != null && deal.perGb() > 0) {
            // Home carrier pays the host wholesale. Fractions of a credit are carried over.
            String key = "w|" + home.id() + "|" + host.id();
            double owed = roamingAccrual.getOrDefault(key, 0.0) + mb / 1000.0 * deal.perGb();
            long whole = (long) Math.floor(owed);
            if (whole > 0) {
                long pay = Math.min(whole, home.balance());
                home.balance(home.balance() - pay);
                home.log(-pay, "Roaming on " + host.name());
                host.balance(host.balance() + pay);
                host.log(pay, "Roaming from " + home.name());
            }
            roamingAccrual.put(key, owed - whole);
        }
        Plan plan = sim.plan();
        if (plan != null && plan.roamingPerGb() > 0) {
            String key = "s|" + player + "|" + home.id();
            double owed = roamingAccrual.getOrDefault(key, 0.0) + mb / 1000.0 * plan.roamingPerGb();
            long whole = (long) Math.floor(owed);
            if (whole > 0) {
                PlayerProfile prof = prof(player);
                long pay = Math.min(whole, prof.balance());
                if (pay > 0) transfer(player, home, pay, "Roaming surcharge");
            }
            roamingAccrual.put(key, owed - whole);
        }
        plugin.store().markDirty();
        return r;
    }

    /** True if this data use would be refused on this SIM (plan rules, or a roaming surcharge they can't pay). */
    public boolean dataBlockedOn(PlayerProfile prof, dev.mccomm.cell.signal.SignalService.SimStatus sim, double mb) {
        Player onWifi = Bukkit.getPlayer(prof.player());
        if (onWifi != null && plugin.broadband().onWifi(onWifi)) return false;
        if (dataBlocked(prof, sim.carrier().id(), mb)) return true;
        Plan plan = sim.plan();
        if (sim.roamingOn() != null && plan != null && plan.roamingPerGb() > 0) {
            return prof.balance() < (long) Math.floor(mb / 1000.0 * plan.roamingPerGb());
        }
        return false;
    }

    /** Checks whether this financed plan can start for the player, returning the phone or null. */
    private PhoneModel financeCheck(Player p, Carrier c, Plan plan) {
        PhoneModel phone = s().phone(plan.financePhone());
        PlayerProfile prof = prof(p.getUniqueId());
        if (phone == null) {
            Msg.send(p, "<red>This plan has no phone set up yet.");
            return null;
        }
        if (prof.ownedPhones().contains(phone.id())) {
            Msg.send(p, "<red>You already own a <white><n></white>. Pick a plan without a phone.", Msg.p("n", phone.name()));
            return null;
        }
        if (c.balance() < phone.price()) {
            Msg.send(p, "<red><c> can't finance a phone right now. Try again later.", Msg.p("c", c.name()));
            return null;
        }
        return phone;
    }

    /** Carrier buys the phone, player gets it (locked to the carrier) and owes the repayments. */
    private void startFinancing(Player p, Carrier c, Plan plan, PhoneModel phone, Subscription sub) {
        PlayerProfile prof = prof(p.getUniqueId());
        c.balance(c.balance() - phone.price());
        c.log(-phone.price(), "Financed " + phone.name() + " for " + p.getName());
        sub.financePhone(phone.id());
        sub.financeRemaining(plan.financeTotal(phone.price()));
        sub.financeInstallment(plan.installment(phone.price()));
        prof.ownedPhones().add(phone.id());
        prof.phoneLocks().put(phone.id(), c.id());
        usePhone(p, phone);
        Msg.send(p, "<green>You got a <white><n></white> through <white><c></white>! It's locked to them until paid off: <white><inst></white> per period for <white><k></white> periods.",
                Msg.p("n", phone.name()), Msg.p("c", c.name()), Msg.p("inst", s().money(sub.financeInstallment())),
                Msg.p("k", "" + plan.installments()));
    }

    /** First payment for a new subscription. Returns false (and tells the player) if they can't. */
    /** True, after telling the player, if this plan is for the other kind of account (personal or business). */
    private boolean wrongAccount(Player p, PlayerProfile prof, Plan plan) {
        if (plan.business() == prof.business()) return false;
        Msg.send(p, plan.business() ? "<red>That's a business plan. Switch to a business account first: <white>/cell account business</white> (or in Settings)."
                : "<red>That's a personal plan. Your account is a business one: <white>/cell account personal</white> switches it.");
        return true;
    }

    /** Switches the player's account between personal and business. Only possible with no SIMs, since plans are for one or the other. */
    public boolean setAccount(Player p, boolean business) {
        PlayerProfile prof = prof(p.getUniqueId());
        if (prof.business() == business) return true;
        if (!prof.subs().isEmpty()) {
            Msg.send(p, "<red>Unsubscribe from all your SIMs first: personal and business plans can't be swapped on a live line.");
            return false;
        }
        prof.business(business);
        plugin.store().markDirty();
        Msg.send(p, "<green>Your account is now a <t> one.", Msg.p("t", business ? "business" : "personal"));
        return true;
    }

    public boolean startPlan(Player p, Carrier c, Plan plan) {
        if (blockIfOverdue(p)) return false;
        PlayerProfile prof = prof(p.getUniqueId());
        if (wrongAccount(p, prof, plan)) return false;
        PhoneModel phone = null;
        long cost = plugin.sales().price(plan);
        if (plan.financed()) {
            phone = financeCheck(p, c, plan);
            if (phone == null) return false;
            cost += plan.downPayment();
        }
        if (!afford(p, cost, plan.name())) return false;
        long now = System.currentTimeMillis();
        Subscription sub = new Subscription(plan.id(), now + plan.periodMillis());
        sub.dataLeftMB(Math.max(0, plan.dataMB()));
        collect(p.getUniqueId(), c, plugin.sales().price(plan), plan.prepaid() ? plan.name() + " top-up" : plan.name());
        if (phone != null) {
            transfer(p.getUniqueId(), c, plan.downPayment(), "Down payment " + phone.name());
            startFinancing(p, c, plan, phone, sub);
        }
        prof.subs().put(c.id(), sub);
        plugin.store().markDirty();
        return true;
    }

    /** Changes the plan on an existing SIM. */
    public void changePlan(Player p, Carrier c, Plan plan) {
        PlayerProfile prof = prof(p.getUniqueId());
        Subscription sub = prof.sub(c.id());
        if (sub == null || sub.planId() == null) {
            if (startPlan(p, c, plan)) {
                Subscription fresh = prof.sub(c.id());
                if (sub != null && sub.financing() && fresh != null) { // keep an unfinished phone loan
                    fresh.financePhone(sub.financePhone());
                    fresh.financeRemaining(sub.financeRemaining());
                    fresh.financeInstallment(sub.financeInstallment());
                }
                Msg.send(p, "<green>Plan <white><n></white> started on <white><c></white>.", Msg.p("n", plan.name()), Msg.p("c", c.name()));
            }
            return;
        }
        if (wrongAccount(p, prof, plan)) return;
        if (blockIfOverdue(p)) return;
        Plan current = planOf(prof, c.id());
        long now = System.currentTimeMillis();

        if (plan.financed()) {
            if (sub.financing()) {
                Msg.send(p, "<red>You're still paying off a phone with <white><c></white>. Pay it off first.", Msg.p("c", c.name()));
                return;
            }
            PhoneModel phone = financeCheck(p, c, plan);
            if (phone == null || !afford(p, plan.downPayment(), "The down payment")) return;
            transfer(p.getUniqueId(), c, plan.downPayment(), "Down payment " + phone.name());
            startFinancing(p, c, plan, phone, sub);
        }

        boolean prepaidSwitch = plan.prepaid() || (current != null && current.prepaid());
        if (prepaidSwitch) {
            // Switching to or from prepaid starts the new plan now.
            if (!afford(p, plugin.sales().price(plan), plan.name())) return;
            collect(p.getUniqueId(), c, plugin.sales().price(plan), plan.prepaid() ? plan.name() + " top-up" : plan.name());
            sub.nextBill(now + plan.periodMillis());
            sub.dataLeftMB(Math.max(0, plan.dataMB()));
            sub.expiredNotice(false);
        } else if (!plan.unlimited()) {
            sub.dataLeftMB(current == null || current.unlimited() ? plan.dataMB() : Math.min(sub.dataLeftMB(), plan.dataMB()));
        }
        sub.planId(plan.id());
        sub.grandfathered(null);
        sub.warned(false);
        plugin.store().markDirty();
        Msg.send(p, prepaidSwitch ? "<green>Switched to <white><n></white>, starting now."
                : "<green>Switched to <white><n></white>. The new price applies from your next charge.", Msg.p("n", plan.name()));
    }

    /** Prepaid top-up: extends the expiry and adds the plan's data (an expired plan starts fresh). */
    public void topUp(Player p, Carrier c) {
        PlayerProfile prof = prof(p.getUniqueId());
        Plan plan = livePlan(prof, c.id());
        Subscription sub = prof.sub(c.id());
        if (plan == null || !plan.prepaid()) {
            Msg.send(p, "<red>That SIM isn't on a prepaid plan.");
            return;
        }
        if (!afford(p, plugin.sales().price(plan), "A top-up")) return;
        if (sub.grandfathered() != null) {
            sub.grandfathered(null);
            Msg.send(p, "<gray>Your plan's updated terms apply from this top-up.");
        }
        collect(p.getUniqueId(), c, plugin.sales().price(plan), plan.name() + " top-up");
        long now = System.currentTimeMillis();
        if (now >= sub.nextBill()) {
            sub.nextBill(now + plan.periodMillis());
            sub.dataLeftMB(Math.max(0, plan.dataMB()));
        } else {
            sub.nextBill(sub.nextBill() + plan.periodMillis());
            if (!plan.unlimited()) sub.dataLeftMB(sub.dataLeftMB() + plan.dataMB());
        }
        sub.warned(false);
        sub.expiredNotice(false);
        plugin.store().markDirty();
        Msg.send(p, "<green>Topped up <white><c></white>. Valid for <white><t></white>, data left: <white><d></white>.",
                Msg.p("c", c.name()), Msg.p("t", until(sub.nextBill() - now)), Msg.p("d", Settings.data(plan.unlimited() ? -1 : sub.dataLeftMB())));
    }

    public void buyDataPack(Player p, Carrier c) {
        PlayerProfile prof = prof(p.getUniqueId());
        Plan plan = planOf(prof, c.id());
        Subscription sub = prof.sub(c.id());
        if (plan == null || plan.unlimited() || plan.packMB() <= 0) {
            Msg.send(p, "<red>That plan doesn't offer data packs.");
            return;
        }
        if (!afford(p, plan.packPrice(), "A data pack")) return;
        collect(p.getUniqueId(), c, plan.packPrice(), "Data pack " + Settings.data(plan.packMB()));
        sub.dataLeftMB(sub.dataLeftMB() + plan.packMB());
        plugin.store().markDirty();
        Msg.send(p, "<green>Added <white><d></white>. Data left: <white><left></white>.",
                Msg.p("d", Settings.data(plan.packMB())), Msg.p("left", Settings.data(sub.dataLeftMB())));
    }

    public void payOffPhone(Player p, Carrier c) {
        PlayerProfile prof = prof(p.getUniqueId());
        Subscription sub = prof.sub(c.id());
        if (sub == null || !sub.financing()) {
            Msg.send(p, "<red>You're not paying off a phone with that carrier.");
            return;
        }
        PhoneModel phone = s().phone(sub.financePhone());
        String name = phone == null ? sub.financePhone() : phone.name();
        if (!afford(p, sub.financeRemaining(), "Paying off the " + name)) return;
        transfer(p.getUniqueId(), c, sub.financeRemaining(), "Phone payoff " + name);
        finishFinancing(prof, sub);
        Msg.send(p, "<green>Your <white><n></white> is paid off and unlocked. It's all yours.", Msg.p("n", name));
    }

    private void finishFinancing(PlayerProfile prof, Subscription sub) {
        if (sub.financePhone() != null) prof.phoneLocks().remove(sub.financePhone());
        sub.financePhone(null);
        sub.financeRemaining(0);
        sub.financeInstallment(0);
        plugin.store().markDirty();
    }

    /** Unsubscribing is blocked while a phone is being paid off with that carrier. */
    public boolean canLeave(Player p, Carrier c) {
        Subscription sub = prof(p.getUniqueId()).sub(c.id());
        if (sub != null && sub.financing()) {
            Msg.send(p, "<red>You still owe <white><o></white> on a phone from <white><c></white>. Pay it off in your wallet first.",
                    Msg.p("o", s().money(sub.financeRemaining())), Msg.p("c", c.name()));
            return false;
        }
        return true;
    }

    // ============================================================ plan edits

    private record PendingNotice(UUID player, String carrierId, String planId, boolean later, long lastEdit) {
    }

    private final Map<String, PendingNotice> planNotices = new HashMap<>();

    /**
     * A carrier edited a plan. Changes that are at least as good for subscribers apply now; anything
     * worse is held back: current subscribers keep their old terms until their next renewal.
     * Subscribers get one summary a little after the carrier stops editing.
     */
    public void planEdited(Carrier c, Plan before, Plan after) {
        if (sameTerms(before, after)) return;
        boolean better = atLeastAsGood(before, after);
        long now = System.currentTimeMillis();
        for (PlayerProfile p : plugin.store().profiles()) {
            Subscription sub = p.sub(c.id());
            if (sub == null || !after.id().equals(sub.planId())) continue;
            boolean later;
            if (sub.grandfathered() != null && atLeastAsGood(sub.grandfathered(), after)) {
                // The live plan is now at least as good as the terms they kept: move them over now.
                Plan kept = sub.grandfathered();
                sub.grandfathered(null);
                if (!kept.unlimited() && !after.unlimited() && after.dataMB() > kept.dataMB()) {
                    sub.dataLeftMB(sub.dataLeftMB() + (after.dataMB() - kept.dataMB()));
                }
                later = false;
            } else if (better) {
                if (sub.grandfathered() == null && !before.unlimited() && !after.unlimited() && after.dataMB() > before.dataMB()) {
                    sub.dataLeftMB(sub.dataLeftMB() + (after.dataMB() - before.dataMB())); // extra data now
                }
                later = sub.grandfathered() != null;
            } else {
                if (sub.grandfathered() == null) sub.grandfathered(before.copy());
                later = true;
            }
            String key = p.player() + "|" + c.id();
            PendingNotice old = planNotices.get(key);
            planNotices.put(key, new PendingNotice(p.player(), c.id(), after.id(), later || (old != null && old.later()), now));
        }
        plugin.store().markDirty();
    }

    private void flushPlanNotices(long now) {
        for (var it = planNotices.entrySet().iterator(); it.hasNext(); ) {
            PendingNotice n = it.next().getValue();
            if (now - n.lastEdit() < 45_000) continue;
            it.remove();
            Carrier c = plugin.store().carrier(n.carrierId());
            Plan plan = c == null ? null : c.plans().get(n.planId());
            PlayerProfile prof = prof(n.player());
            Subscription sub = prof.sub(n.carrierId());
            if (plan == null || sub == null || !plan.id().equals(sub.planId())) continue;
            if (n.later() && sub.grandfathered() != null) {
                String when = plan.prepaid() ? "your next top-up" : "your next renewal in " + until(sub.nextBill() - now);
                notify(n.player(), "<gold><c> changed your <p> plan.</gold> <gray>You keep your current terms until " + when + ". New terms: <t>",
                        Msg.c("c", Msg.mm(c.styled())), Msg.c("p", Msg.mm(plan.styled(c))), Msg.c("t", Msg.mm(shortTerms(plan))));
            } else {
                notify(n.player(), "<green><c> improved your <p> plan, effective now:</green> <t>",
                        Msg.c("c", Msg.mm(c.styled())), Msg.c("p", Msg.mm(plan.styled(c))), Msg.c("t", Msg.mm(shortTerms(plan))));
            }
        }
    }

    /** "20 cr/day · 5.0 GB · up to 4G+ · 50.0 Mbps cap · no texts" (MiniMessage, gray). */
    public String shortTerms(Plan p) {
        List<String> parts = new ArrayList<>();
        parts.add(plugin.sales().label(p.price(), p.sale()) + (p.prepaid() ? " per top-up (" + period(p.periodHours()) + ")" : "/" + period(p.periodHours())));
        parts.add(p.unlimited() ? "unlimited data" : Settings.data(p.dataMB()) + " data");
        if (p.maxBand() != null) parts.add("up to " + s().spec(p.maxBand()).name());
        if (p.speedCapMbps() > 0) parts.add(Msg.speed(p.speedCapMbps()) + " cap");
        if (!p.texts()) parts.add("no texts");
        if (!p.calls()) parts.add("no calls");
        if (!p.roaming()) parts.add("no roaming");
        else if (p.roamingPerGb() > 0) parts.add("roaming " + s().money(p.roamingPerGb()) + "/GB");
        return "<white>" + Msg.escape(String.join(" · ", parts));
    }

    /** True if nothing that affects existing subscribers changed (name, style, packs and phone deals don't). */
    private static boolean sameTerms(Plan a, Plan b) {
        return a.price() == b.price() && a.periodHours() == b.periodHours() && a.maxBand() == b.maxBand()
                && a.speedCapMbps() == b.speedCapMbps() && a.texts() == b.texts() && a.calls() == b.calls()
                && a.dataMB() == b.dataMB() && a.outOfData() == b.outOfData() && a.overagePerGb() == b.overagePerGb()
                && a.roaming() == b.roaming() && a.roamingPerGb() == b.roamingPerGb() && a.type() == b.type();
    }

    /** True if {@code after} is at least as good as {@code before} for a subscriber in every way. */
    private static boolean atLeastAsGood(Plan before, Plan after) {
        if (after.type() != before.type() || after.periodHours() != before.periodHours()) return false;
        if (after.price() > before.price()) return false;
        if (!after.unlimited() && (before.unlimited() || after.dataMB() < before.dataMB())) return false;
        if (after.maxBand() != null && (before.maxBand() == null || after.maxBand().ordinal() < before.maxBand().ordinal())) return false;
        if (after.speedCapMbps() > 0 && (before.speedCapMbps() == 0 || after.speedCapMbps() < before.speedCapMbps())) return false;
        if (before.texts() && !after.texts()) return false;
        if (before.calls() && !after.calls()) return false;
        if (before.roaming() && !after.roaming()) return false;
        if (after.roamingPerGb() > before.roamingPerGb()) return false;
        if (after.outOfData() != before.outOfData() && after.outOfData() != Plan.OutOfData.THROTTLE) return false;
        return after.overagePerGb() <= before.overagePerGb();
    }

    /** Called when a carrier deletes a plan: its subscribers drop to no plan, without debt. */
    public void planDeleted(Carrier c, Plan plan) {
        for (PlayerProfile p : plugin.store().profiles()) {
            Subscription sub = p.sub(c.id());
            if (sub != null && plan.id().equals(sub.planId())) {
                sub.planId(null);
                notify(p.player(), "<yellow><c> retired the <white><n></white> plan. Choose a new plan to get service again.",
                        Msg.p("c", c.name()), Msg.p("n", plan.name()));
            }
        }
        plugin.store().markDirty();
    }

    public int subscribers(Carrier c, Plan plan) {
        int n = 0;
        for (PlayerProfile p : plugin.store().profiles()) {
            Subscription sub = p.sub(c.id());
            if (sub != null && plan.id().equals(sub.planId())) n++;
        }
        return n;
    }

    // ================================================================== data

    public enum DataResult { OK, THROTTLED, BLOCKED }

    /** True if using {@code mb} now would be refused (blocked plan out of data, or overage they can't pay). */
    public boolean dataBlocked(PlayerProfile prof, String carrierId, double mb) {
        Plan plan = planOf(prof, carrierId);
        Subscription sub = prof.sub(carrierId);
        if (plan == null) return true;
        if (plan.unlimited() || sub.dataLeftMB() >= mb) return false;
        return switch (plan.outOfData()) {
            case THROTTLE -> false;
            case BLOCK -> true;
            case OVERAGE -> prof.balance() < overageCost(plan, mb - sub.dataLeftMB());
        };
    }

    private static long overageCost(Plan plan, double mb) {
        return (long) Math.ceil(mb / 1000.0 * plan.overagePerGb());
    }

    /** Uses data on a SIM, charging overage if the plan does that. */
    public DataResult useData(UUID player, String carrierId, double mb) {
        PlayerProfile prof = prof(player);
        Plan plan = planOf(prof, carrierId);
        Subscription sub = prof.sub(carrierId);
        if (plan == null) return DataResult.BLOCKED;
        if (plan.unlimited()) return DataResult.OK;
        if (sub.dataLeftMB() >= mb) {
            sub.dataLeftMB(sub.dataLeftMB() - mb);
            plugin.store().markDirty();
            return DataResult.OK;
        }
        double over = mb - sub.dataLeftMB();
        switch (plan.outOfData()) {
            case THROTTLE -> {
                sub.dataLeftMB(0);
                plugin.store().markDirty();
                return DataResult.THROTTLED;
            }
            case OVERAGE -> {
                long cost = overageCost(plan, over);
                Carrier c = plugin.store().carrier(carrierId);
                if (prof.balance() < cost || c == null) return DataResult.BLOCKED;
                sub.dataLeftMB(0);
                collect(player, c, cost, "Data overage " + Settings.data(over));
                plugin.store().markDirty();
                return DataResult.OK;
            }
            default -> {
                return DataResult.BLOCKED;
            }
        }
    }

    /** True if this SIM is out of data on a throttling plan. */
    public boolean throttled(PlayerProfile prof, String carrierId) {
        Plan plan = planOf(prof, carrierId);
        Subscription sub = prof.sub(carrierId);
        return plan != null && !plan.unlimited() && plan.outOfData() == Plan.OutOfData.THROTTLE && sub.dataLeftMB() <= 0;
    }

    /** "3.2 GB left", "Unlimited". */
    public String dataLeftText(PlayerProfile prof, String carrierId) {
        Plan plan = planOf(prof, carrierId);
        if (plan == null) return "-";
        if (plan.unlimited()) return "Unlimited";
        double left = prof.sub(carrierId).dataLeftMB();
        return left <= 0 ? "Out of data" : Settings.data(left) + " left";
    }

    // ================================================================ upkeep

    public long upkeepCost(Carrier c) {
        long sum = 0;
        for (Tower t : plugin.store().towersOf(c.id())) sum += s().upkeepCost(t);
        return sum;
    }

    /** 0 = healthy; 1..n = decaying; {@link Settings#silentStage()} = silent. */
    public int decayStage(Carrier c) {
        if (c == null || c.unpaidSince() == 0) return 0;
        long steps = (System.currentTimeMillis() - c.unpaidSince()) / (s().decayStepHours * HOUR);
        return (int) Math.min(s().silentStage(), 1 + steps);
    }

    public int decayStage(Tower t) {
        return decayStage(plugin.store().carrier(t.carrierId()));
    }

    /** Speed multiplier from decay (1.0 when healthy). */
    public double decaySpeed(Tower t) {
        int st = decayStage(t);
        if (st <= 0) return 1.0;
        if (st >= s().silentStage()) return 0;
        return s().decaySpeed[st - 1];
    }

    /** How many of the tower's top enabled bands are shut off by decay. */
    public int decayDroppedBands(Tower t) {
        return Math.max(0, decayStage(t) - 1);
    }

    private void chargeUpkeep(Carrier c, long now, boolean repair) {
        long towers = upkeepCost(c);
        var host = c.mvno() ? plugin.partners().mvnoHost(c) : null;
        if (c.mvno() && host == null) c.mvnoOwed(0); // no host any more: nothing to settle
        long share = host == null ? 0 : c.mvnoOwed();
        long cost = towers + share;
        if (c.balance() < cost) plugin.groups().backBill(c, cost - c.balance(), "Upkeep"); // a parent company covers it
        if (c.balance() >= cost) {
            c.balance(c.balance() - cost);
            if (towers > 0) {
                c.log(-towers, "Tower upkeep (" + plugin.store().towersOf(c.id()).size() + " towers)");
                plugin.manufacturers().payUpkeepShares(c);
            }
            if (share > 0) {
                c.log(-share, "Revenue share to " + host.host().name());
                host.host().balance(host.host().balance() + share);
                host.host().log(share, "Revenue share from " + c.name());
                c.mvnoOwed(0);
            }
            if (c.unpaidSince() != 0) {
                c.unpaidSince(0);
                notify(c.owner(), c.mvno() ? "<green><c> paid its host. Service is back for your subscribers."
                        : "<green>Upkeep for <white><c></white> is paid. Your towers are repaired.", Msg.p("c", c.name()));
            }
            if (repair) c.nextUpkeep(now + s().upkeepPeriodHours * HOUR);
            else c.nextUpkeep(c.nextUpkeep() + s().upkeepPeriodHours * HOUR);
        } else if (!repair) {
            if (c.unpaidSince() == 0) {
                c.unpaidSince(now);
                notify(c.owner(), c.mvno()
                                ? "<red><c> couldn't pay its host (<white><cost></white>, balance <white><b></white>). Subscribers lose service in " + s().mvnoGraceHours + " h unless you deposit."
                                : "<red><c> couldn't pay tower upkeep (<white><cost></white>, balance <white><b></white>). Your towers will decay until you deposit.",
                        Msg.p("c", c.name()), Msg.p("cost", s().money(cost)), Msg.p("b", s().money(c.balance())));
            }
            c.nextUpkeep(c.nextUpkeep() + s().upkeepPeriodHours * HOUR);
        } else {
            notify(c.owner(), "<yellow>Still not enough for upkeep: <white><cost></white> needed, balance <white><b></white>.",
                    Msg.p("cost", s().money(cost)), Msg.p("b", s().money(c.balance())));
        }
        plugin.store().markDirty();
    }

    // ================================================================== tick

    public void tick() {
        long now = System.currentTimeMillis();

        plugin.partners().tick();
        plugin.market().tick(now);
        plugin.simulation().payRevenue();
        flushPlanNotices(now);
        for (Carrier c : new ArrayList<>(plugin.store().carriers())) {
            if (c.nextUpkeep() == 0) c.nextUpkeep(now + s().upkeepPeriodHours * HOUR);
            for (int guard = 0; guard < 60 && now >= c.nextUpkeep(); guard++) chargeUpkeep(c, now, false);
            int stage = decayStage(c);
            Integer before = lastStage.put(c.id(), stage);
            if (before != null && before != stage) {
                for (Tower t : plugin.store().towersOf(c.id())) plugin.towers().refresh(t);
                if (stage >= s().silentStage() && !c.mvno()) {
                    notify(c.owner(), "<dark_red><c>'s towers have gone silent. Deposit to repair them.", Msg.p("c", c.name()));
                }
            }
        }
        // Bills in order of importance: upkeep (above) first, then operations, then repairs.
        plugin.workforce().tick(now);
        plugin.groups().tick();
        plugin.sales().tick(now);
        plugin.companies().checkBankruptcies(now);

        for (PlayerProfile p : new ArrayList<>(plugin.store().profiles())) {
            for (Map.Entry<String, Subscription> e : new ArrayList<>(p.subs().entrySet())) {
                Subscription sub = e.getValue();
                if (sub.planId() == null) continue;
                Carrier c = plugin.store().carrier(e.getKey());
                Plan plan = c == null ? null : c.plans().get(sub.planId());
                if (plan == null) {
                    sub.planId(null);
                    continue;
                }
                if (plan.prepaid()) {
                    if (!sub.warned() && now < sub.nextBill() && now >= sub.nextBill() - s().warnHours * HOUR) {
                        sub.warned(true);
                        notify(p.player(), "<gold>Your prepaid <white><c></white> plan expires in <white><t></white>. Top up in your wallet to keep service.",
                                Msg.p("c", c.name()), Msg.p("t", until(sub.nextBill() - now)));
                    }
                    if (now >= sub.nextBill() && !sub.expiredNotice()) {
                        sub.expiredNotice(true);
                        sub.dataLeftMB(0);
                        notify(p.player(), "<red>Your prepaid <white><c></white> plan has expired. Top up to get service back.", Msg.p("c", c.name()));
                    }
                    continue;
                }
                long charge = nextCharge(p, c.id());
                if (!sub.warned() && p.balance() < charge && now >= sub.nextBill() - s().warnHours * HOUR) {
                    sub.warned(true);
                    notify(p.player(), "<gold>Heads up: your <white><c></white> plan renews in <white><t></white> for <white><price></white>, but your balance is <white><b></white>. Deposit ores in your wallet or the plan will be cancelled with a <white><m>×</white> overdue charge"
                                    + (sub.financing() ? " and your phone will be blacklisted." : "."),
                            Msg.p("c", c.name()), Msg.p("t", until(sub.nextBill() - now)), Msg.p("price", s().money(charge)),
                            Msg.p("b", s().money(p.balance())), Msg.p("m", "" + s().overdueMultiplier));
                }
                for (int guard = 0; guard < 60 && sub.planId() != null && now >= sub.nextBill(); guard++) {
                    if (sub.grandfathered() != null) {
                        sub.grandfathered(null);
                        notify(p.player(), "<gray>Your <white><c></white> plan's updated terms start now: <t>",
                                Msg.p("c", c.name()), Msg.c("t", Msg.mm(shortTerms(plan))));
                    }
                    charge = nextCharge(p, c.id());
                    if (p.balance() >= charge) {
                        long sale = plugin.sales().price(plan);
                        collect(p.player(), c, sale - plugin.broadband().carrierDiscount(p.player(), c.id(), sale), plan.name());
                        if (sub.financing()) {
                            long inst = Math.min(sub.financeInstallment(), sub.financeRemaining());
                            transfer(p.player(), c, inst, "Phone instalment");
                            sub.financeRemaining(sub.financeRemaining() - inst);
                            if (sub.financeRemaining() <= 0) {
                                PhoneModel ph = s().phone(sub.financePhone());
                                finishFinancing(p, sub);
                                notify(p.player(), "<green>Your <white><n></white> is fully paid off and unlocked!",
                                        Msg.p("n", ph == null ? "phone" : ph.name()));
                            }
                        }
                        if (!plan.unlimited()) sub.dataLeftMB(plan.dataMB());
                        sub.nextBill(sub.nextBill() + plan.periodMillis());
                        sub.warned(false);
                    } else {
                        defaulted(p, c, plan, sub, charge);
                    }
                }
            }
        }
        plugin.store().markDirty();
    }

    /** A missed charge: plan cancelled, charge x multiplier overdue; a financed phone's balance comes due and it's blacklisted. */
    private void defaulted(PlayerProfile p, Carrier c, Plan plan, Subscription sub, long charge) {
        sub.planId(null);
        long owed = charge * s().overdueMultiplier;
        String phoneNote = "";
        if (sub.financing()) {
            long inst = Math.min(sub.financeInstallment(), sub.financeRemaining());
            owed += sub.financeRemaining() - inst;
            PhoneModel ph = s().phone(sub.financePhone());
            p.blacklist().put(sub.financePhone(), c.id());
            p.phoneLocks().remove(sub.financePhone());
            phoneNote = " Your " + (ph == null ? "phone" : ph.name()) + " is blacklisted until it's paid.";
            sub.financePhone(null);
            sub.financeRemaining(0);
            sub.financeInstallment(0);
        }
        p.overdue().merge(c.id(), owed, Long::sum);
        p.log(0, "Missed payment: " + c.name() + " (" + s().money(owed) + " overdue)");
        notify(p.player(), "<red>Your <white><c></white> plan was cancelled: not enough funds. You now owe <white><o></white>." + phoneNote
                        + " Deposit to pay it off; you can't subscribe anywhere until you do.",
                Msg.p("c", c.name()), Msg.p("o", s().money(owed)));
        plugin.store().markDirty();
    }

    // ============================================================== timewarp

    /**
     * Admin/testing: fast-forwards the economy by {@code ms}, in steps, as if that time had passed.
     * Every schedule (plan charges, upkeep, decay, agreements, simulated revenue) is moved back by
     * each step and a billing tick runs.
     */
    /** A running timewarp: it advances a slice of steps every server tick until it's done. */
    private static final class Warp {
        final org.bukkit.command.CommandSender by;
        final double days;
        final long step;
        final long startedAt = System.currentTimeMillis();
        long left;
        int steps;
        long reportAt;
        org.bukkit.scheduler.BukkitTask task;

        Warp(org.bukkit.command.CommandSender by, double days, long ms, long step) {
            this.by = by;
            this.days = days;
            this.left = ms;
            this.step = step;
            this.reportAt = startedAt + 5_000;
        }
    }

    /** Milliseconds of each server tick a timewarp may use, so the server keeps running while it works. */
    private static final long WARP_BUDGET_NANOS = 20_000_000L;
    private Warp warp;

    /** True while a timewarp is running (tower signs and screens wait until it's done). */
    public boolean warping() {
        return warp != null;
    }

    /** How far the running timewarp is, as "3.2 of 30 days", or null. */
    public String warpProgress() {
        if (warp == null) return null;
        double done = warp.days - warp.left / 86_400_000.0;
        return String.format(java.util.Locale.ROOT, "%.1f of %s days", done, fmtDays(warp.days));
    }

    private static String fmtDays(double d) {
        return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    /**
     * Admin/testing: fast-forwards the economy by {@code ms}, in steps of {@code step}, as if that time had passed.
     * It runs a few steps per server tick (about 20 ms of work each) instead of all at once, so long warps don't
     * freeze or crash the server. False if one is already running.
     */
    public boolean startWarp(org.bukkit.command.CommandSender by, double days, long ms, long step) {
        if (warp != null || ms <= 0 || step <= 0) return false;
        Warp w = new Warp(by, days, ms, step);
        warp = w;
        w.task = Bukkit.getScheduler().runTaskTimer(plugin, this::warpTick, 1L, 1L);
        return true;
    }

    /** Stops a running timewarp where it is. False if none was running. */
    public boolean stopWarp() {
        if (warp == null) return false;
        finishWarp(true);
        return true;
    }

    private void warpTick() {
        Warp w = warp;
        if (w == null) return;
        long until = System.nanoTime() + WARP_BUDGET_NANOS;
        try {
            do {
                long d = Math.min(w.step, w.left);
                warpStep(d);
                w.left -= d;
                w.steps++;
            } while (w.left > 0 && System.nanoTime() < until);
        } catch (RuntimeException ex) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Timewarp failed at step " + w.steps + "; stopping it.", ex);
            finishWarp(true);
            return;
        }
        if (w.left <= 0) {
            finishWarp(false);
            return;
        }
        long now = System.currentTimeMillis();
        if (now >= w.reportAt) {
            w.reportAt = now + 5_000;
            warpMessage(w, "<gray>Timewarp: <white>" + warpProgress() + "</white> done…");
        }
    }

    private void finishWarp(boolean stopped) {
        Warp w = warp;
        if (w == null) return;
        warp = null;
        if (w.task != null) w.task.cancel();
        plugin.store().markDirty();
        if (plugin.broadband() != null) plugin.broadband().store().save();
        plugin.groups().save();
        plugin.sales().save();
        plugin.store().save();
        plugin.towers().refreshAll();
        double done = w.days - w.left / 86_400_000.0;
        String took = String.format(java.util.Locale.ROOT, "%.1f", (System.currentTimeMillis() - w.startedAt) / 1000.0);
        warpMessage(w, (stopped ? "<yellow>Timewarp stopped after <white>" : "<green>Fast-forwarded the economy <white>")
                + String.format(java.util.Locale.ROOT, "%.1f", done) + "</white> days (<white>" + w.steps + "</white> steps, "
                + took + " s).");
    }

    /** Tells whoever started the warp (if they're still there) and the server log. */
    private void warpMessage(Warp w, String mm) {
        if (w.by instanceof Player p) {
            if (p.isOnline()) Msg.send(p, mm);
        } else if (w.by instanceof org.bukkit.command.ConsoleCommandSender) {
            Msg.send(w.by, mm);
            return; // the console already logs it
        }
        plugin.getLogger().info(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(mm)));
    }

    /** One warp step: every schedule moves back by {@code d}, then a billing tick runs. */
    private void warpStep(long d) {
        {
            for (Carrier c : plugin.store().carriers()) {
                if (c.nextUpkeep() > 0) c.nextUpkeep(c.nextUpkeep() - d);
                if (c.unpaidSince() > 0) c.unpaidSince(c.unpaidSince() - d);
                for (var a : c.agreements().values()) if (a.endsAt() > 0) a.endsAt(a.endsAt() - d);
            }
            for (PlayerProfile p : plugin.store().profiles()) {
                for (Subscription sub : p.subs().values()) sub.nextBill(sub.nextBill() - d);
            }
            plugin.simulation().shift(d);
            plugin.workforce().shift(d);
            plugin.market().shift(d);
            plugin.broadband().shift(d);
            plugin.sales().shift(d);
            tick();
            plugin.broadband().tick();
        }
    }

    // =============================================================== helpers

    /** Sends now if online, otherwise saves it for their next join. */
    public void notify(UUID player, String mm, net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... r) {
        Player p = Bukkit.getPlayer(player);
        if (p != null) {
            Msg.send(p, mm, r);
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 0.7f, 0.8f);
        } else {
            prof(player).notices().add(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                    .serialize(Msg.mm(mm, r)));
            plugin.store().markDirty();
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        PlayerProfile prof = prof(p.getUniqueId());
        if (s().starterPhone != null && prof.ownedPhones().isEmpty() && !prof.starterGiven()) {
            prof.starterGiven(true);
            prof.giftedPhones().add(s().starterPhone);
            prof.ownedPhones().add(s().starterPhone);
            prof.phone(s().starterPhone);
            plugin.store().markDirty();
        }
        if (!prof.notices().isEmpty()) {
            List<String> list = new ArrayList<>(prof.notices());
            prof.notices().clear();
            plugin.store().markDirty();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline()) return;
                Msg.send(p, "<aqua>While you were away:");
                for (String n : list) Msg.raw(p, " " + n);
            }, 40L);
        }
    }

    public static String period(int hours) {
        if (hours % 24 == 0) return hours == 24 ? "day" : (hours / 24) + " days";
        return hours == 1 ? "hour" : hours + " hours";
    }

    public static String until(long ms) {
        if (ms <= 0) return "now";
        long m = ms / 60000;
        if (m < 60) return m + " min";
        long h = m / 60;
        if (h < 48) return h + " h " + (m % 60) + " min";
        return (h / 24) + " days";
    }

    private static String name(UUID id) {
        String n = Bukkit.getOfflinePlayer(id).getName();
        return n == null ? id.toString().substring(0, 8) : n;
    }
}
