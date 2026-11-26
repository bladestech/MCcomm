package dev.mccomm.cell.economy;

import dev.mccomm.cell.CellPlugin;
import dev.mccomm.cell.broadband.InternetPlan;
import dev.mccomm.cell.broadband.Isp;
import dev.mccomm.cell.model.Carrier;
import dev.mccomm.cell.model.Company;
import dev.mccomm.cell.model.Plan;
import dev.mccomm.cell.model.Sale;
import dev.mccomm.cell.util.Msg;
import dev.mccomm.cell.util.YamlFiles;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Temporary plan sales, and the regulator: a company whose plans are on sale on too many days of the last month is
 * warned, then fined, more each time.
 */
public final class SaleService {
    private static final long HOUR = 3_600_000L, DAY = 86_400_000L;

    /** Per company: the days it had a plan on sale (one mark per day), its offences, and warnings sent. */
    private static final class Record {
        final List<Long> marks = new ArrayList<>();
        final List<Long> offences = new ArrayList<>();
        final Set<Integer> warned = new LinkedHashSet<>();
        long nextCheck;
        long lastFine;
    }

    private final CellPlugin plugin;
    private final Map<String, Record> records = new HashMap<>(); // company ref key -> record
    private boolean dirty;

    public SaleService(CellPlugin plugin) {
        this.plugin = plugin;
    }

    private SaleSettings c() {
        return plugin.settings().sales;
    }

    private String money(long v) {
        return plugin.settings().money(v);
    }

    // ================================================================ prices

    /** What a carrier plan costs right now (its sale price while a sale is on). */
    public long price(Plan p) {
        return p.sale().apply(p.price(), System.currentTimeMillis());
    }

    /** What an ISP plan costs right now (its sale price while a sale is on), before bundle discounts. */
    public long price(InternetPlan p) {
        return p.sale().apply(p.price(), System.currentTimeMillis());
    }

    /** True while the plan is on sale: its price can't be edited until the sale ends. */
    public boolean locked(Sale s) {
        return s.on(System.currentTimeMillis());
    }

    /** "~~12~~ 9 cr, 25% off until ..." for menus, or just the price. */
    public String label(long base, Sale s) {
        long now = System.currentTimeMillis();
        if (!s.on(now)) return money(base);
        return "<st>" + money(base) + "</st> <green>" + money(s.apply(base, now)) + " <gray>(" + s.percent() + "% off, "
                + BillingService.until(s.until() - now) + " left)";
    }

    // ================================================================ starting and ending

    /** Why a sale can't start on this plan now, or null. */
    public String problem(Sale s, int percent, int hours) {
        long now = System.currentTimeMillis();
        if (!c().enabled()) return "Sales are switched off on this server.";
        if (s.on(now)) return "That plan is already on sale.";
        if (percent < c().minPercent() || percent > c().maxPercent()) return "A sale is " + c().minPercent() + "-" + c().maxPercent() + "% off.";
        if (hours < 1 || hours > c().maxHours()) return "A sale lasts 1-" + c().maxHours() + " hours.";
        long clean = c().cleanPriceDays() * DAY;
        if (s.priceSince() > 0 && now - s.priceSince() < clean) {
            return "The price changed " + BillingService.until(now - s.priceSince()) + " ago. A sale needs the same price for "
                    + c().cleanPriceDays() + " days first, so the \"was\" price is real (" + BillingService.until(clean - (now - s.priceSince())) + " to go).";
        }
        return null;
    }

    /** Starts a sale. True if it started (the player is told either way). */
    public boolean start(Player p, Company co, String planName, Sale s, int percent, int hours) {
        String why = problem(s, percent, hours);
        if (why != null) {
            Msg.send(p, "<red>" + why);
            return false;
        }
        long now = System.currentTimeMillis();
        s.start(percent, now, now + hours * HOUR);
        mark(co, now);
        markDirty();
        int days = daysOnSale(co, now);
        Msg.send(p, "<green><n> is <white><pc>% off</white> for <white><h></white>.</green> <gray>Sale days this month: <white><d></white>/<a> allowed.",
                Msg.p("n", planName), Msg.p("pc", "" + percent), Msg.p("h", BillingService.until(hours * HOUR)),
                Msg.p("d", "" + days), Msg.p("a", "" + c().allowedDays()));
        return true;
    }

    public void end(Player p, String planName, Sale s) {
        s.end();
        markDirty();
        Msg.send(p, "<yellow>The sale on <white><n></white> has ended.", Msg.p("n", planName));
    }

    // ================================================================ the regulator

    private Record record(Company co) {
        return records.computeIfAbsent(co.ref().key(), k -> new Record());
    }

    /** Marks today as a sale day for this company (at most one mark per 24 h). */
    private void mark(Company co, long now) {
        Record r = record(co);
        if (r.marks.isEmpty() || now - r.marks.getLast() >= DAY) r.marks.add(now);
    }

    /** Days in the window on which this company had a plan on sale. */
    public int daysOnSale(Company co, long now) {
        Record r = records.get(co.ref().key());
        if (r == null) return 0;
        long from = now - c().windowDays() * DAY;
        int n = 0;
        for (long t : r.marks) if (t > from) n++;
        return n;
    }

    private boolean anyOnSale(Company co, long now) {
        if (co instanceof Carrier car) {
            for (Plan p : car.plans().values()) if (p.sale().on(now)) return true;
        } else if (co instanceof Isp isp) {
            for (InternetPlan p : isp.plans().values()) if (p.sale().on(now)) return true;
        }
        return false;
    }

    private int customers(Company co) {
        if (co instanceof Carrier car) return plugin.workforce().subscribers(car);
        if (co instanceof Isp isp) return plugin.workforce().customers(isp);
        return 0;
    }

    /** Ends expired sales and checks every company once an hour. Runs with the billing tick. */
    public void tick(long now) {
        for (Company co : plugin.workforce().budgeted()) {
            endExpired(co, now);
            Record r = record(co);
            if (r.nextCheck == 0) r.nextCheck = now;
            if (now < r.nextCheck) continue;
            r.nextCheck = now + HOUR;
            if (anyOnSale(co, now)) mark(co, now);
            long memory = c().offenceMemoryDays() * DAY;
            r.marks.removeIf(t -> now - t > Math.max(c().windowDays() * DAY, memory));
            r.offences.removeIf(t -> now - t > memory);
            int days = daysOnSale(co, now);
            if (days < c().warnAt().getFirst()) r.warned.clear();
            for (int w : c().warnAt()) {
                if (days >= w && r.warned.add(w) && days <= c().allowedDays()) {
                    plugin.billing().notify(co.owner(), "<gold>Regulator:</gold> <gray><c>'s plans have been on sale on <white><d></white> of the last <white><w></white> days. More than <white><a></white> days of \"sales\" counts as misleading pricing and is fined.",
                            Msg.p("c", co.name()), Msg.p("d", "" + days), Msg.p("w", "" + c().windowDays()), Msg.p("a", "" + c().allowedDays()));
                }
            }
            // A fine for every sale day beyond the allowance, at most once a day.
            if (days > c().allowedDays() && anyOnSale(co, now) && now - r.lastFine >= DAY) {
                int earlier = r.offences.size();
                long base = Math.max(c().fineMin(), Math.round(c().finePerCustomer() * customers(co)));
                long fine = base * (1L << Math.min(earlier, 10));
                r.offences.add(now);
                r.lastFine = now;
                co.balance(co.balance() - fine);
                co.log(-fine, "Regulator fine: misleading sales");
                plugin.billing().notify(co.owner(), "<red><b>Regulator fine:</b> <white><f></white> from <c></red> <gray>for keeping plans on sale on <white><d></white> of the last <white><w></white> days (allowed: <white><a></white>)."
                                + (earlier > 0 ? " It doubled for each of its <white><e></white> earlier offences." : "") + " End the sale to stop the fines.",
                        Msg.p("f", money(fine)), Msg.p("c", co.name()), Msg.p("d", "" + days), Msg.p("w", "" + c().windowDays()),
                        Msg.p("a", "" + c().allowedDays()), Msg.p("e", "" + earlier));
                plugin.store().markDirty();
                if (plugin.broadband() != null) plugin.broadband().store().markDirty();
            }
            markDirty();
        }
    }

    private void endExpired(Company co, long now) {
        if (co instanceof Carrier car) {
            for (Plan p : car.plans().values()) expire(co, p.name(), p.sale(), now);
        } else if (co instanceof Isp isp) {
            for (InternetPlan p : isp.plans().values()) expire(co, p.name(), p.sale(), now);
        }
    }

    private void expire(Company co, String name, Sale s, long now) {
        if (s.percent() <= 0 || s.on(now)) return;
        s.end();
        plugin.billing().notify(co.owner(), "<gray>The sale on <white><n></white> (<c>) has ended: it's back to its normal price.",
                Msg.p("n", name), Msg.p("c", co.name()));
        plugin.store().markDirty();
        if (plugin.broadband() != null) plugin.broadband().store().markDirty();
    }

    /** Moves every schedule back by {@code ms} (timewarp). */
    public void shift(long ms) {
        for (Carrier car : plugin.store().carriers()) for (Plan p : car.plans().values()) p.sale().shift(ms);
        if (plugin.broadband() != null) {
            for (Isp isp : plugin.broadband().store().isps()) for (InternetPlan p : isp.plans().values()) p.sale().shift(ms);
        }
        for (Record r : records.values()) {
            r.marks.replaceAll(t -> t - ms);
            r.offences.replaceAll(t -> t - ms);
            if (r.nextCheck > 0) r.nextCheck -= ms;
            if (r.lastFine > 0) r.lastFine -= ms;
        }
        markDirty();
    }

    private void markDirty() {
        dirty = true;
    }

    // ================================================================ storage

    public void load() {
        File f = new File(plugin.getDataFolder(), "sales.yml");
        if (!f.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        ConfigurationSection rs = y.getConfigurationSection("companies");
        if (rs == null) return;
        for (String key : rs.getKeys(false)) {
            ConfigurationSection s = rs.getConfigurationSection(key);
            if (s == null) continue;
            Record r = new Record();
            r.marks.addAll(s.getLongList("marks"));
            r.offences.addAll(s.getLongList("offences"));
            r.warned.addAll(s.getIntegerList("warned"));
            r.nextCheck = s.getLong("next-check");
            r.lastFine = s.getLong("last-fine");
            records.put(key.replace('~', ':'), r);
        }
    }

    public void saveIfDirty() {
        if (dirty) save();
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (var e : records.entrySet()) {
            Record r = e.getValue();
            if (r.marks.isEmpty() && r.offences.isEmpty()) continue;
            String p = "companies." + e.getKey().replace(':', '~') + ".";
            y.set(p + "marks", r.marks);
            y.set(p + "offences", r.offences);
            y.set(p + "warned", new ArrayList<>(r.warned));
            y.set(p + "next-check", r.nextCheck);
            y.set(p + "last-fine", r.lastFine);
        }
        YamlFiles.save(y, new File(plugin.getDataFolder(), "sales.yml"), plugin.getLogger());
        dirty = false;
    }
}
