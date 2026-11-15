package dev.mccomm.cell.command;

import dev.mccomm.cell.CellPlugin;
import dev.mccomm.cell.model.Carrier;
import dev.mccomm.cell.model.Tower;
import dev.mccomm.cell.util.Msg;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** /carrier — create and run a telecom company. */
public final class CarrierCommand extends Cmd {
    private static final List<String> SUBS = List.of("manage", "create", "delete", "info", "list", "color", "employee");

    public CarrierCommand(CellPlugin plugin) {
        super(plugin);
    }

    @Override
    public String permission() {
        return "mccomm.use";
    }

    private boolean canManage(CommandSender s, Carrier c) {
        if (s.hasPermission("mccomm.admin")) return true;
        if (s instanceof Player p && c.isStaff(p.getUniqueId())) return true;
        Msg.send(s, "<red>You don't manage <white><c></white>.", Msg.p("c", c.name()));
        return false;
    }

    private boolean isOwner(CommandSender s, Carrier c) {
        if (s.hasPermission("mccomm.admin")) return true;
        if (s instanceof Player p && c.owner().equals(p.getUniqueId())) return true;
        Msg.send(s, "<red>Only the owner of <white><c></white> can do that.", Msg.p("c", c.name()));
        return false;
    }

    @Override
    public void execute(CommandSourceStack src, String[] args) {
        CommandSender s = src.getSender();
        if (args.length == 0) {
            help(s);
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "manage", "wallet", "plans" -> {
                Player p = player(s);
                if (p == null) return;
                Carrier c;
                if (args.length >= 2) c = carrierArg(p, args[1]);
                else {
                    c = null;
                    for (Carrier x : plugin.store().carriers()) if (x.isStaff(p.getUniqueId())) { c = x; break; }
                    if (c == null) Msg.send(p, "<red>Usage: /carrier manage <name>");
                }
                if (c != null) plugin.carrierMenu().open(p, c);
            }
            case "create" -> create(s, args);
            case "delete" -> delete(s, args);
            case "info" -> info(s, args);
            case "list" -> Bukkit.dispatchCommand(s, "cell carriers");
            case "color" -> color(s, args);
            case "employee" -> employee(s, args);
            default -> help(s);
        }
    }

    private void help(CommandSender s) {
        Msg.raw(s, "<dark_gray>━━━ <aqua>/carrier</aqua> ━━━");
        Msg.raw(s, "<white>/carrier manage [name] <gray>— wallet, upkeep, plans, towers");
        Msg.raw(s, "<white>/carrier create <name>");
        Msg.raw(s, "<white>/carrier delete <name> confirm");
        Msg.raw(s, "<white>/carrier info [name]");
        Msg.raw(s, "<white>/carrier list");
        Msg.raw(s, "<white>/carrier color <name> <colours...> <gray>— solid, gradient or preset (or use Branding in /carrier manage)");
        Msg.raw(s, "<white>/carrier employee add|remove <name> <player>");
    }

    private void create(CommandSender s, String[] args) {
        Player p = player(s);
        if (p == null) return;
        if (!p.hasPermission("mccomm.carrier.create")) {
            Msg.send(p, "<red>You can't create carriers.");
            return;
        }
        if (args.length < 2) {
            Msg.send(p, "<red>Usage: /carrier create <name>");
            return;
        }
        String name = args[1];
        if (!plugin.settings().carrierNamePattern.matcher(name).matches()) {
            Msg.send(p, "<red>Carrier names must be 2-20 characters: letters, numbers, _ - & .");
            return;
        }
        if (plugin.store().carrier(name) != null) {
            Msg.send(p, "<red>That carrier name is taken.");
            return;
        }
        int max = plugin.settings().maxCarriersPerPlayer;
        if (max >= 0 && !p.hasPermission("mccomm.admin") && plugin.store().carriersOwnedBy(p.getUniqueId()).size() >= max) {
            Msg.send(p, "<red>You already own the maximum number of carriers (<n>).", Msg.p("n", "" + max));
            return;
        }
        Carrier c = new Carrier(name, p.getUniqueId(), NamedTextColor.AQUA, System.currentTimeMillis());
        plugin.store().addCarrier(c);
        plugin.billing().ensureDefaults();
        Msg.send(p, "<green>Carrier <white><c></white> created. Look at a wall sign and run <white>/tower create <c></white> to build your first tower.",
                Msg.p("c", name));
    }

    private void delete(CommandSender s, String[] args) {
        if (args.length < 2) {
            Msg.send(s, "<red>Usage: /carrier delete <name> confirm");
            return;
        }
        Carrier c = carrierArg(s, args[1]);
        if (c == null || !isOwner(s, c)) return;
        List<Tower> towers = plugin.store().towersOf(c.id());
        if (args.length < 3 || !args[2].equalsIgnoreCase("confirm")) {
            Msg.send(s, "<yellow>This deletes <white><c></white>, its <n> tower(s) and every subscription. Run <white>/carrier delete <c> confirm</white>.",
                    Msg.p("c", c.name()), Msg.p("n", "" + towers.size()));
            return;
        }
        for (Tower t : towers) plugin.towers().remove(t, true);
        plugin.store().removeCarrier(c);
        Msg.send(s, "<yellow>Carrier <white><c></white> deleted.", Msg.p("c", c.name()));
    }

    private void info(CommandSender s, String[] args) {
        Carrier c;
        if (args.length >= 2) {
            c = carrierArg(s, args[1]);
        } else if (s instanceof Player p && !plugin.store().carriersOwnedBy(p.getUniqueId()).isEmpty()) {
            c = plugin.store().carriersOwnedBy(p.getUniqueId()).getFirst();
        } else {
            Msg.send(s, "<red>Usage: /carrier info <name>");
            return;
        }
        if (c == null) return;
        List<Tower> towers = plugin.store().towersOf(c.id());
        long online = towers.stream().filter(Tower::online).count();
        List<String> staff = new ArrayList<>();
        for (UUID id : c.employees()) staff.add(name(id));
        Msg.raw(s, "<dark_gray>━━━ " + c.styled() + " <dark_gray>━━━");
        Msg.raw(s, "<gray>Owner: <white><o>", Msg.p("o", name(c.owner())));
        Msg.raw(s, "<gray>Employees: <white><e>", Msg.p("e", staff.isEmpty() ? "none" : String.join(", ", staff)));
        Msg.raw(s, "<gray>Towers: <white><n></white> <dark_gray>(<on> online)", Msg.p("n", "" + towers.size()), Msg.p("on", "" + online));
    }

    private static String name(UUID id) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(id);
        return op.getName() == null ? id.toString().substring(0, 8) : op.getName();
    }

    private void color(CommandSender s, String[] args) {
        if (args.length < 3) {
            Msg.send(s, "<red>Usage: /carrier color <name> <color> [color...] <gray>(2-4 colours = gradient, or a preset like sunset)");
            return;
        }
        Carrier c = carrierArg(s, args[1]);
        if (c == null || !canManage(s, c)) return;
        java.util.List<TextColor> style = dev.mccomm.cell.util.Style.parse(join(args, 2));
        if (style == null) {
            Msg.send(s, "<red>Unknown style. Use colour names or hex codes, 1 for solid or 2-4 for a gradient, e.g. <white>#ff5f6d #ffc371</white>, or a preset like <white>sunset</white>.");
            return;
        }
        c.colors(style);
        plugin.store().markDirty();
        for (Tower t : plugin.store().towersOf(c.id())) plugin.towers().refresh(t);
        Msg.send(s, "<green>Style updated: " + c.styled());
    }

    private void employee(CommandSender s, String[] args) {
        if (args.length < 4) {
            Msg.send(s, "<red>Usage: /carrier employee add|remove <name> <player>");
            return;
        }
        Carrier c = carrierArg(s, args[2]);
        if (c == null || !isOwner(s, c)) return;
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(args[3]);
        if (target == null) {
            Msg.send(s, "<red>Unknown player <white><p></white>.", Msg.p("p", args[3]));
            return;
        }
        if (args[1].equalsIgnoreCase("add")) {
            if (target.getUniqueId().equals(c.owner()) || !c.employees().add(target.getUniqueId())) {
                Msg.send(s, "<gray>They already work for <c>.", Msg.p("c", c.name()));
                return;
            }
            Msg.send(s, "<green>Added <white><p></white> to <c>.", Msg.p("p", args[3]), Msg.p("c", c.name()));
        } else if (args[1].equalsIgnoreCase("remove")) {
            if (!c.employees().remove(target.getUniqueId())) {
                Msg.send(s, "<gray>They don't work for <c>.", Msg.p("c", c.name()));
                return;
            }
            Msg.send(s, "<yellow>Removed <white><p></white> from <c>.", Msg.p("p", args[3]), Msg.p("c", c.name()));
        } else {
            Msg.send(s, "<red>Usage: /carrier employee add|remove <name> <player>");
            return;
        }
        plugin.store().markDirty();
    }

    @Override
    public Collection<String> suggest(CommandSourceStack src, String[] args) {
        if (args.length <= 1) return filter(SUBS, args.length == 0 ? "" : args[0]);
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("employee")) {
            if (args.length == 2) return filter(List.of("add", "remove"), args[1]);
            if (args.length == 3) return filter(carrierNames(), args[2]);
            if (args.length == 4) return filter(onlineNames(), args[3]);
            return List.of();
        }
        if (args.length == 2 && List.of("delete", "info", "color", "manage", "wallet", "plans").contains(sub)) return filter(carrierNames(), args[1]);
        if (args.length >= 3 && sub.equals("color")) {
            java.util.List<String> opts = new java.util.ArrayList<>(NamedTextColor.NAMES.keys());
            if (args.length == 3) for (String g : dev.mccomm.cell.util.Style.GRADIENTS.keySet()) opts.add(g.toLowerCase(Locale.ROOT));
            return filter(opts, args[args.length - 1]);
        }
        if (args.length == 3 && sub.equals("delete")) return filter(List.of("confirm"), args[2]);
        return List.of();
    }
}
