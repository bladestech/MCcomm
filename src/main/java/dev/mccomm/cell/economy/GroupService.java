package dev.mccomm.cell.economy;

import dev.mccomm.cell.CellPlugin;
import dev.mccomm.cell.broadband.Isp;
import dev.mccomm.cell.model.Carrier;
import dev.mccomm.cell.model.Company;
import dev.mccomm.cell.model.LedgerEntry;
import dev.mccomm.cell.model.Manufacturer;
import dev.mccomm.cell.model.PlayerProfile;
import dev.mccomm.cell.util.Msg;
import dev.mccomm.cell.util.Style;
import dev.mccomm.cell.util.YamlFiles;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Parent companies (groups): a treasury that owns carriers, manufacturers and ISPs. Members get cheaper transfers,
 * lower operations costs, extra bankruptcy grace, and loans from the treasury when they can't pay a bill.
 */
public final class GroupService {
    private final CellPlugin plugin;
    private final Map<String, Group> groups = new LinkedHashMap<>();
    private boolean dirty;

    public GroupService(CellPlugin plugin) {
        this.plugin = plugin;
    }

    private GroupSettings g() {
        return plugin.settings().groups;
    }

    private String money(long v) {
        return plugin.settings().money(v);
    }

    public boolean enabled() {
        return g().enabled();
    }

    // ================================================================ lookups

    public Collection<Group> groups() {
        return groups.values();
    }

    public Group group(String id) {
        return id == null ? null : groups.get(Group.idOf(id));
    }

    public List<Group> ownedBy(UUID player) {
        List<Group> out = new ArrayList<>();
        for (Group gr : groups.values()) if (gr.owner().equals(player)) out.add(gr);
        return out;
    }

    /** The company a ref points to, or null if it's gone. */
    public Company company(Company.Ref ref) {
        if (ref == null) return null;
        return switch (ref.kind()) {
            case CARRIER -> plugin.store().carrier(ref.id());
            case MANUFACTURER -> plugin.store().manufacturer(ref.id());
            case ISP -> plugin.broadband() == null ? null : plugin.broadband().store().isp(ref.id());
        };
    }

    /** Finds a company by kind and name. */
    public Company company(Company.Kind kind, String name) {
        return company(new Company.Ref(kind, name.toLowerCase(java.util.Locale.ROOT)));
    }

    public Group groupOf(Company c) {
        if (c == null || c.group() == null) return null;
        Group gr = groups.get(c.group());
        return gr != null && gr.members().contains(c.ref().key()) ? gr : null;
    }

    public List<Company> members(Group gr) {
        List<Company> out = new ArrayList<>();
        for (String k : gr.members()) {
            Company c = company(Company.Ref.parse(k));
            if (c != null) out.add(c);
        }
        return out;
    }

    /** Every company this player owns, in a group or not. */
    public List<Company> ownedCompanies(UUID player) {
        List<Company> out = new ArrayList<>();
        for (Carrier c : plugin.store().carriers()) if (player.equals(c.owner())) out.add(c);
        for (Manufacturer m : plugin.store().manufacturers()) if (!m.system() && player.equals(m.owner())) out.add(m);
        if (plugin.broadband() != null) for (Isp i : plugin.broadband().store().isps()) if (player.equals(i.owner())) out.add(i);
        return out;
    }

    /** Companies of one kind the player owns outside any group (what the per-player limits count). */
    public int ownedOutsideGroups(UUID player, Company.Kind kind) {
        int n = 0;
        for (Company c : ownedCompanies(player)) if (c.kind() == kind && groupOf(c) == null) n++;
        return n;
    }

    /**
     * How much of a member's own reserve its group stands behind: the treasury, which covers upkeep the moment it's
     * due. A member doesn't need to hold that much back for its next upkeep, so it can spend its own wallet first.
     */
    public long backing(Company c) {
        if (!enabled()) return 0;
        Group gr = groupOf(c);
        return gr == null ? 0 : Math.max(0, gr.balance());
    }

    /** The part of {@code reserve} a company must still hold back itself once its group's backing is counted. */
    public long ownReserve(Company c, long reserve) {
        return Math.max(0, reserve - backing(c));
    }

    /** What this member owes its group's treasury. */
    public long owes(Company c) {
        Group gr = groupOf(c);
        return gr == null ? 0 : gr.loan(c.ref().key());
    }

    // ================================================================ benefits

    /** Operations discount for a member: per other member, capped. */
    public double opsDiscount(Company c) {
        Group gr = groupOf(c);
        if (gr == null || !enabled()) return 0;
        return Math.min(g().opsDiscountMax(), g().opsDiscountPerMember() * Math.max(0, gr.members().size() - 1));
    }

    /** Extra days before a member in default is declared bankrupt. */
    public int bankruptcyExtraDays(Company c) {
        return groupOf(c) != null && enabled() ? g().bankruptcyExtraDays() : 0;
    }

    /**
     * Financial backing: if a member is {@code shortfall} credits short of paying a bill that's due, the treasury lends
     * it (no fee). The money goes straight into the member to be spent on that bill.
     *
     * @return how much was lent
     */
    public long backBill(Company c, long shortfall, String what) {
        if (shortfall <= 0 || !enabled()) return 0;
        Group gr = groupOf(c);
        if (gr == null || gr.balance() <= 0) return 0;
        long lend = Math.min(shortfall, gr.balance());
        boolean first = gr.loan(c.ref().key()) == 0;
        gr.balance(gr.balance() - lend);
        gr.loans().merge(c.ref().key(), lend, Long::sum);
        gr.log(-lend, "Loan to " + c.name() + " (" + what + ")");
        c.balance(c.balance() + lend);
        c.log(lend, "Loan from " + gr.name() + " (" + what + ")");
        if (first) plugin.billing().notify(gr.owner(), "<gold><g> is backing <white><c></white>: it lent <white><a></white> for its <w>. It's repaid from <c>'s income.",
                Msg.p("g", gr.name()), Msg.p("c", c.name()), Msg.p("a", money(lend)), Msg.p("w", what.toLowerCase(java.util.Locale.ROOT)));
        markAll();
        return lend;
    }

    /** What a member keeps back before repaying: its next upkeep and the next hour of operations. */
    private long reserve(Company c) {
        long r = (long) Math.ceil(plugin.workforce().hourlyBill(c));
        if (c instanceof Carrier car) r += plugin.workforce().upkeepReserve(car);
        if (c instanceof Isp isp) r += plugin.broadband().upkeepDue(isp);
        return r;
    }

    /** Members repay their loans from what they have above their reserve. Runs with the billing tick. */
    public void tick() {
        syncMembers();
        for (Group gr : groups.values()) {
            for (Company c : members(gr)) {
                long owed = gr.loan(c.ref().key());
                if (owed <= 0) continue;
                long free = c.balance() - reserve(c);
                if (free <= 0) continue;
                long pay = Math.min(owed, free);
                c.balance(c.balance() - pay);
                c.log(-pay, "Loan repayment to " + gr.name());
                gr.balance(gr.balance() + pay);
                gr.log(pay, "Repaid by " + c.name());
                if (owed - pay <= 0) gr.loans().remove(c.ref().key());
                else gr.loans().put(c.ref().key(), owed - pay);
                markAll();
            }
        }
    }

    /** Keeps members owned by the group's owner, and drops members (and their loans) that no longer exist. */
    private void syncMembers() {
        for (Group gr : groups.values()) {
            for (String k : new ArrayList<>(gr.members())) {
                Company c = company(Company.Ref.parse(k));
                if (c == null) {
                    gr.members().remove(k);
                    Long lost = gr.loans().remove(k);
                    if (lost != null && lost > 0) gr.log(0, "Loan of " + money(lost) + " lost: member closed");
                    dirty = true;
                    continue;
                }
                if (!gr.id().equals(c.group())) {
                    c.group(gr.id());
                    markAll();
                }
                if (!gr.owner().equals(c.owner())) {
                    c.owner(gr.owner());
                    c.employees().remove(gr.owner());
                    markAll();
                }
            }
        }
    }

    // ================================================================ membership

    /** Null if it worked, else why not. */
    public String create(Player p, String name) {
        if (!enabled()) return "Parent companies are switched off on this server.";
        if (name == null || !name.matches("[A-Za-z0-9_\\-]{2,24}")) return "Names are 2-24 letters, digits, - or _ (no spaces).";
        if (groups.containsKey(Group.idOf(name))) return "A parent company called " + name + " already exists.";
        int max = g().maxPerPlayer();
        if (max >= 0 && ownedBy(p.getUniqueId()).size() >= max && !p.hasPermission("mccomm.admin")) return "You can own at most " + max + " parent companies.";
        Group gr = new Group(name, p.getUniqueId(), System.currentTimeMillis());
        groups.put(gr.id(), gr);
        dirty = true;
        return null;
    }

    /** Puts a company the group's owner owns into the group. Null if it worked, else why not. */
    public String add(Player p, Group gr, Company c) {
        if (!canManage(p, gr)) return "Only the owner of " + gr.name() + " can do that.";
        if (c instanceof Manufacturer m && m.system()) return "That manufacturer can't join a group.";
        if (!gr.owner().equals(c.owner())) return c.name() + " isn't owned by " + gr.name() + "'s owner.";
        Group cur = groupOf(c);
        if (cur == gr) return c.name() + " is already in " + gr.name() + ".";
        if (cur != null) return c.name() + " is in " + cur.name() + ". Take it out first.";
        if (c instanceof Isp isp && isp.carrierBacked()) return "A 5G Home ISP belongs with its carrier: it follows the carrier's owner.";
        int max = g().maxMembers();
        if (max >= 0 && gr.members().size() >= max) return gr.name() + " already has " + max + " companies.";
        gr.members().add(c.ref().key());
        c.group(gr.id());
        markAll();
        return null;
    }

    /** Takes a company out of its group (its loan must be repaid first). Null if it worked, else why not. */
    public String remove(Player p, Company c) {
        Group gr = groupOf(c);
        if (gr == null) return c.name() + " isn't in a parent company.";
        if (!canManage(p, gr)) return "Only the owner of " + gr.name() + " can do that.";
        long owed = gr.loan(c.ref().key());
        if (owed > 0) return c.name() + " still owes " + gr.name() + " " + money(owed) + ". Repay it first.";
        detach(gr, c);
        return null;
    }

    /** Takes a company out without checks (it's being sold or closed). Its loan, if any, is written off. */
    public void detach(Group gr, Company c) {
        gr.members().remove(c.ref().key());
        Long lost = gr.loans().remove(c.ref().key());
        if (lost != null && lost > 0) gr.log(0, "Loan of " + money(lost) + " to " + c.name() + " written off");
        c.group(null);
        markAll();
    }

    public boolean canManage(Player p, Group gr) {
        return gr.owner().equals(p.getUniqueId()) || p.hasPermission("mccomm.admin");
    }

    // ================================================================ money

    private long fee(long amount, double percent) {
        if (amount <= 0 || percent <= 0) return 0;
        return (long) Math.ceil(amount * percent / 100.0);
    }

    /** Owner's wallet → treasury (true) or treasury → wallet (false), with the normal transfer fee. */
    public void moveWallet(Player p, Group gr, long amount, boolean intoGroup) {
        if (!canManage(p, gr)) {
            Msg.send(p, "<red>Only the owner can move money in and out of <white><g></white>.", Msg.p("g", gr.name()));
            return;
        }
        PlayerProfile me = plugin.store().profile(p.getUniqueId());
        long have = intoGroup ? me.balance() : gr.balance();
        if (amount <= 0 || have < amount) {
            Msg.send(p, "<red>Not enough: <white><b></white> available.", Msg.p("b", money(have)));
            return;
        }
        long fee = fee(amount, plugin.settings().transferFeePercent), arrives = amount - fee;
        if (intoGroup) {
            me.balance(me.balance() - amount);
            me.log(-amount, "To " + gr.name());
            gr.balance(gr.balance() + arrives);
            gr.log(arrives, "From " + p.getName() + " (fee " + money(fee) + ")");
        } else {
            gr.balance(gr.balance() - amount);
            gr.log(-amount, "Withdrawn by " + p.getName());
            me.balance(me.balance() + arrives);
            me.log(arrives, "From " + gr.name() + " (fee " + money(fee) + ")");
            plugin.billing().payOverdue(p.getUniqueId());
        }
        markAll();
        Msg.send(p, "<green>Moved <white><a></white> <dir> <white><g></white>; <white><r></white> arrived after the <white><f></white> fee.",
                Msg.p("a", money(amount)), Msg.p("dir", intoGroup ? "into" : "out of"), Msg.p("g", gr.name()),
                Msg.p("r", money(arrives)), Msg.p("f", money(fee)));
    }

    /**
     * Treasury ↔ member at the group rate. Money from a member that owes the treasury repays the loan first (no fee
     * on repayments). Money leaving a member keeps its next upkeep and an hour of operations.
     */
    public void moveMember(Player p, Group gr, Company c, long amount, boolean toMember) {
        if (!canManage(p, gr)) {
            Msg.send(p, "<red>Only the owner can do that.");
            return;
        }
        if (groupOf(c) != gr) {
            Msg.send(p, "<red><c> isn't in <g>.", Msg.p("c", c.name()), Msg.p("g", gr.name()));
            return;
        }
        if (amount <= 0) {
            Msg.send(p, "<red>Enter an amount above 0.");
            return;
        }
        if (toMember) {
            if (gr.balance() < amount) {
                Msg.send(p, "<red><g> only has <white><b></white>.", Msg.p("g", gr.name()), Msg.p("b", money(gr.balance())));
                return;
            }
            long fee = fee(amount, g().transferFeePercent()), arrives = amount - fee;
            gr.balance(gr.balance() - amount);
            gr.log(-amount, "To " + c.name());
            c.balance(c.balance() + arrives);
            c.log(arrives, "From " + gr.name() + " (fee " + money(fee) + ")");
            if (c instanceof Carrier car) plugin.billing().retryUpkeep(car);
            Msg.send(p, "<green>Moved <white><a></white> from <white><g></white> to <white><c></white> (<white><f></white> fee).",
                    Msg.p("a", money(amount)), Msg.p("g", gr.name()), Msg.p("c", c.name()), Msg.p("f", money(fee)));
        } else {
            long free = c.balance() - reserve(c);
            if (amount > free) {
                Msg.send(p, "<red><c> can send up to <white><f></white> (its next upkeep and an hour of operations stay in it).",
                        Msg.p("c", c.name()), Msg.p("f", money(Math.max(0, free))));
                return;
            }
            long owed = gr.loan(c.ref().key());
            long repay = Math.min(owed, amount), rest = amount - repay;
            long fee = fee(rest, g().transferFeePercent());
            c.balance(c.balance() - amount);
            c.log(-amount, "To " + gr.name() + (repay > 0 ? " (repays " + money(repay) + ")" : ""));
            gr.balance(gr.balance() + amount - fee);
            gr.log(amount - fee, "From " + c.name() + (fee > 0 ? " (fee " + money(fee) + ")" : ""));
            if (repay > 0) {
                if (owed - repay <= 0) gr.loans().remove(c.ref().key());
                else gr.loans().put(c.ref().key(), owed - repay);
            }
            Msg.send(p, "<green>Moved <white><a></white> from <white><c></white> to <white><g></white><r>.",
                    Msg.p("a", money(amount)), Msg.p("c", c.name()), Msg.p("g", gr.name()),
                    Msg.p("r", repay > 0 ? " (" + money(repay) + " repaid the loan, " + money(fee) + " fee)" : " (" + money(fee) + " fee)"));
        }
        markAll();
    }

    /** Liquidation or sale money for a member goes into its treasury instead of a wallet. */
    public void creditTreasury(Group gr, long amount, String note) {
        gr.balance(gr.balance() + amount);
        gr.log(amount, note);
        markAll();
    }

    public void removeGroup(Group gr) {
        for (Company c : members(gr)) c.group(null);
        groups.remove(gr.id());
        markAll();
    }

    private void markAll() {
        dirty = true;
        plugin.store().markDirty();
        if (plugin.broadband() != null) plugin.broadband().store().markDirty();
    }

    // ================================================================ storage

    public void load() {
        File f = new File(plugin.getDataFolder(), "groups.yml");
        if (!f.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        ConfigurationSection gs = y.getConfigurationSection("groups");
        if (gs == null) return;
        for (String id : gs.getKeys(false)) {
            ConfigurationSection s = gs.getConfigurationSection(id);
            if (s == null) continue;
            try {
                Group gr = new Group(s.getString("name", id), UUID.fromString(s.getString("owner")), s.getLong("created-at"));
                gr.colors(Style.fromHex(s.getStringList("colors")));
                gr.balance(s.getLong("balance"));
                gr.members().addAll(s.getStringList("members"));
                ConfigurationSection ls = s.getConfigurationSection("loans");
                if (ls != null) for (String k : ls.getKeys(false)) gr.loans().put(k.replace('~', ':'), ls.getLong(k));
                for (Map<?, ?> m : s.getMapList("ledger")) {
                    Object t = m.get("time"), a = m.get("amount"), n = m.get("note");
                    if (t instanceof Number tn && a instanceof Number an) gr.ledger().add(new LedgerEntry(tn.longValue(), an.longValue(), String.valueOf(n)));
                }
                groups.put(gr.id(), gr);
            } catch (Exception ex) {
                plugin.getLogger().log(Level.WARNING, "Skipping broken parent company " + id, ex);
            }
        }
    }

    public void saveIfDirty() {
        if (dirty) save();
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Group gr : groups.values()) {
            String p = "groups." + gr.id() + ".";
            y.set(p + "name", gr.name());
            y.set(p + "owner", gr.owner().toString());
            y.set(p + "colors", Style.toHex(gr.colors()));
            y.set(p + "balance", gr.balance());
            y.set(p + "created-at", gr.createdAt());
            y.set(p + "members", new ArrayList<>(gr.members()));
            for (var e : gr.loans().entrySet()) y.set(p + "loans." + e.getKey().replace(':', '~'), e.getValue());
            List<Map<String, Object>> led = new ArrayList<>();
            for (LedgerEntry e : gr.ledger()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("time", e.time());
                m.put("amount", e.amount());
                m.put("note", e.note());
                led.add(m);
            }
            if (!led.isEmpty()) y.set(p + "ledger", led);
        }
        YamlFiles.save(y, new File(plugin.getDataFolder(), "groups.yml"), plugin.getLogger());
        dirty = false;
    }
}
