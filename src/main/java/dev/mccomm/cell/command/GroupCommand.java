package dev.mccomm.cell.command;

import dev.mccomm.cell.CellPlugin;
import dev.mccomm.cell.economy.Group;
import dev.mccomm.cell.model.Company;
import dev.mccomm.cell.util.Msg;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/** /company (alias /group) — parent companies that own carriers, manufacturers and ISPs. */
public final class GroupCommand extends Cmd {
    private static final List<String> SUBS = List.of("manage", "create", "list", "info", "add", "remove", "deposit", "withdraw",
            "give", "take", "liquidate", "color");
    private static final List<String> KINDS = List.of("carrier", "mfr", "isp");

    public GroupCommand(CellPlugin plugin) {
        super(plugin);
    }

    @Override
    public String permission() {
        return "mccomm.use";
    }

    private String money(long v) {
        return plugin.settings().money(v);
    }

    /** The group named, or the player's only one. */
    private Group groupArg(CommandSender s, String name) {
        if (name != null) {
            Group gr = plugin.groups().group(name);
            if (gr == null) Msg.send(s, "<red>No parent company named <white><n></white>.", Msg.p("n", name));
            return gr;
        }
        if (s instanceof Player p) {
            List<Group> mine = plugin.groups().ownedBy(p.getUniqueId());
            if (mine.size() == 1) return mine.getFirst();
            Msg.send(s, mine.isEmpty() ? "<red>You don't own a parent company. <white>/company create <name>"
                    : "<red>You own several parent companies: name one.");
        } else Msg.send(s, "<red>Name the parent company.");
        return null;
    }

    private Company companyArg(CommandSender s, String kind, String name) {
        Company.Kind k = Company.Kind.of(kind);
        if (k == null) {
            Msg.send(s, "<red>The type is <white>carrier</white>, <white>mfr</white> or <white>isp</white>.");
            return null;
        }
        Company c = plugin.groups().company(k, name);
        if (c == null) Msg.send(s, "<red>No <t> named <white><n></white>.", Msg.p("t", k.display.toLowerCase(Locale.ROOT)), Msg.p("n", name));
        return c;
    }

    private Long amount(CommandSender s, String in) {
        try {
            long v = Long.parseLong(in.replace(",", ""));
            if (v > 0) return v;
        } catch (NumberFormatException ignored) {
        }
        Msg.send(s, "<red>Enter a whole number above 0.");
        return null;
    }

    @Override
    public void execute(CommandSourceStack src, String[] args) {
        CommandSender s = src.getSender();
        if (!plugin.groups().enabled()) {
            Msg.send(s, "<red>Parent companies are switched off on this server.");
            return;
        }
        String sub = args.length == 0 ? "manage" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "manage", "open" -> {
                Player p = player(s);
                if (p == null) return;
                if (args.length < 2 && plugin.groups().ownedBy(p.getUniqueId()).isEmpty()) {
                    help(s);
                    return;
                }
                Group gr = groupArg(s, args.length >= 2 ? args[1] : null);
                if (gr != null) plugin.groupMenu().open(p, gr);
            }
            case "create" -> {
                Player p = player(s);
                if (p == null) return;
                if (args.length < 2) {
                    Msg.send(s, "<red>Usage: /company create <name>");
                    return;
                }
                String err = plugin.groups().create(p, args[1]);
                if (err != null) {
                    Msg.send(s, "<red>" + err);
                    return;
                }
                Msg.send(s, "<green>Parent company <white><n></white> created.</green> <gray>Add your companies: <white>/company add <carrier|mfr|isp> <name></white>, or from their Money tab.",
                        Msg.p("n", args[1]));
            }
            case "list" -> {
                Msg.raw(s, "<gold>━━━ Parent companies ━━━");
                if (plugin.groups().groups().isEmpty()) Msg.raw(s, "<gray>None yet.");
                for (Group gr : plugin.groups().groups()) {
                    String owner = Bukkit.getOfflinePlayer(gr.owner()).getName();
                    Msg.raw(s, "<gray> · " + gr.styled() + " <gray>· " + (owner == null ? "?" : owner) + " · " + gr.members().size() + " companies");
                }
            }
            case "info" -> {
                Group gr = groupArg(s, args.length >= 2 ? args[1] : null);
                if (gr == null) return;
                String owner = Bukkit.getOfflinePlayer(gr.owner()).getName();
                Msg.raw(s, "<gold>━━━ " + gr.styled() + " <gold>━━━");
                Msg.raw(s, "<gray>Owner: <white>" + (owner == null ? "?" : owner));
                Msg.raw(s, "<gray>Treasury: <white>" + money(gr.balance()) + " <gray>· Loans out: <white>" + money(gr.totalLoans()));
                for (Company c : plugin.groups().members(gr)) {
                    long owes = gr.loan(c.ref().key());
                    Msg.raw(s, "<gray> · " + c.kind().display + " " + c.styled() + " <gray>· " + money(c.balance())
                            + (owes > 0 ? " <gold>(owes " + money(owes) + ")" : ""));
                }
                Msg.raw(s, "<gray>Worth: <white>" + money(plugin.companies().valuation(gr).total()));
            }
            case "add", "remove" -> {
                Player p = player(s);
                if (p == null) return;
                if (args.length < 3) {
                    Msg.send(s, "<red>Usage: /company " + sub + " <carrier|mfr|isp> <name>" + (sub.equals("add") ? " [group]" : ""));
                    return;
                }
                Company c = companyArg(s, args[1], args[2]);
                if (c == null) return;
                if (sub.equals("add")) {
                    Group gr = groupArg(s, args.length >= 4 ? args[3] : null);
                    if (gr == null) return;
                    String err = plugin.groups().add(p, gr, c);
                    if (err != null) Msg.send(s, "<red>" + err);
                    else Msg.send(s, "<green><c> is now part of <white><g></white>.", Msg.p("c", c.name()), Msg.p("g", gr.name()));
                } else {
                    String err = plugin.groups().remove(p, c);
                    if (err != null) Msg.send(s, "<red>" + err);
                    else Msg.send(s, "<yellow><c> left its parent company.", Msg.p("c", c.name()));
                }
            }
            case "deposit", "withdraw" -> {
                Player p = player(s);
                if (p == null) return;
                if (args.length < 2) {
                    Msg.send(s, "<red>Usage: /company " + sub + " <amount> [group]");
                    return;
                }
                Long a = amount(s, args[1]);
                Group gr = a == null ? null : groupArg(s, args.length >= 3 ? args[2] : null);
                if (gr != null) plugin.groups().moveWallet(p, gr, a, sub.equals("deposit"));
            }
            case "give", "take" -> {
                Player p = player(s);
                if (p == null) return;
                if (args.length < 4) {
                    Msg.send(s, "<red>Usage: /company " + sub + " <carrier|mfr|isp> <name> <amount> <gray>(" + (sub.equals("give")
                            ? "treasury → company" : "company → treasury") + ")");
                    return;
                }
                Company c = companyArg(s, args[1], args[2]);
                Long a = c == null ? null : amount(s, args[3]);
                if (a == null) return;
                Group gr = plugin.groups().groupOf(c);
                if (gr == null) {
                    Msg.send(s, "<red><c> isn't in a parent company.", Msg.p("c", c.name()));
                    return;
                }
                plugin.groups().moveMember(p, gr, c, a, sub.equals("give"));
            }
            case "liquidate" -> {
                Player p = player(s);
                if (p == null) return;
                if (args.length < 2) {
                    Msg.send(s, "<red>Usage: /company liquidate <group> confirm");
                    return;
                }
                Group gr = groupArg(s, args[1]);
                if (gr == null) return;
                if (args.length < 3 || !args[2].equalsIgnoreCase("confirm")) {
                    Msg.send(s, "<yellow>This liquidates <white><g></white> and its <white><n></white> companies for good, then pays the treasury to you minus the fee. Run <white>/company liquidate <g> confirm</white>.",
                            Msg.p("g", gr.name()), Msg.p("n", "" + gr.members().size()));
                    return;
                }
                plugin.companies().liquidate(p, gr);
            }
            case "color" -> {
                if (args.length < 3) {
                    Msg.send(s, "<red>Usage: /company color <group> <color> [color...]");
                    return;
                }
                Group gr = groupArg(s, args[1]);
                if (gr == null) return;
                if (s instanceof Player p && !plugin.groups().canManage(p, gr)) {
                    Msg.send(s, "<red>Only the owner can do that.");
                    return;
                }
                List<TextColor> style = dev.mccomm.cell.util.Style.parse(join(args, 2));
                if (style == null) {
                    Msg.send(s, "<red>Unknown style. Use colour names or hex codes, 1 for solid or 2-4 for a gradient.");
                    return;
                }
                gr.colors(style);
                plugin.groups().save();
                Msg.send(s, "<green>Style updated: " + gr.styled());
            }
            default -> help(s);
        }
    }

    private void help(CommandSender s) {
        Msg.raw(s, "<gold>━━━ Parent companies ━━━");
        Msg.raw(s, "<gray>A parent company owns your carriers, manufacturers and ISPs, backs them with loans when they run short, and makes their operations cheaper.");
        Msg.raw(s, "<white>/company create <name> <gray>· <white>/company <gray>(open) · <white>/company list|info [group]");
        Msg.raw(s, "<white>/company add <carrier|mfr|isp> <name> [group] <gray>· <white>/company remove <carrier|mfr|isp> <name>");
        Msg.raw(s, "<white>/company deposit|withdraw <amount> [group] <gray>(wallet, " + plugin.settings().transferFeePercent + "% fee)");
        Msg.raw(s, "<white>/company give|take <carrier|mfr|isp> <name> <amount> <gray>(treasury ↔ company, " + plugin.settings().groups.transferFeePercent() + "% fee)");
        Msg.raw(s, "<white>/company color <group> <colors...> <gray>· <white>/company liquidate <group> confirm");
    }

    private List<String> groupNames() {
        List<String> out = new ArrayList<>();
        for (Group gr : plugin.groups().groups()) out.add(gr.name());
        return out;
    }

    private List<String> companyNames(String kind) {
        Company.Kind k = Company.Kind.of(kind);
        List<String> out = new ArrayList<>();
        if (k == null) return out;
        switch (k) {
            case CARRIER -> out.addAll(carrierNames());
            case MANUFACTURER -> {
                for (var m : plugin.store().manufacturers()) if (!m.system()) out.add(m.name());
            }
            case ISP -> {
                for (var i : plugin.broadband().store().isps()) out.add(i.name());
            }
        }
        return out;
    }

    @Override
    public Collection<String> suggest(CommandSourceStack src, String[] args) {
        if (args.length <= 1) return filter(SUBS, args.length == 0 ? "" : args[0]);
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "manage", "info", "liquidate", "color" -> {
                if (args.length == 2) return filter(groupNames(), args[1]);
                if (args.length == 3 && sub.equals("liquidate")) return filter(List.of("confirm"), args[2]);
                if (args.length >= 3 && sub.equals("color")) return filter(new ArrayList<>(NamedTextColor.NAMES.keys()), args[args.length - 1]);
            }
            case "add", "remove", "give", "take" -> {
                if (args.length == 2) return filter(KINDS, args[1]);
                if (args.length == 3) return filter(companyNames(args[1]), args[2]);
                if (args.length == 4 && sub.equals("add")) return filter(groupNames(), args[3]);
            }
            case "deposit", "withdraw" -> {
                if (args.length == 3) return filter(groupNames(), args[2]);
            }
            default -> {
            }
        }
        return List.of();
    }
}
