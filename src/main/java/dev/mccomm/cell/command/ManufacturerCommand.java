package dev.mccomm.cell.command;

import dev.mccomm.cell.CellPlugin;
import dev.mccomm.cell.model.Manufacturer;
import dev.mccomm.cell.util.Msg;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/** /manufacturer — create and run a tower manufacturer. */
public final class ManufacturerCommand extends Cmd {
    private static final List<String> SUBS = List.of("manage", "create", "delete", "list", "color", "employee");

    public ManufacturerCommand(CellPlugin plugin) {
        super(plugin);
    }

    @Override
    public String permission() {
        return "mccomm.use";
    }

    private List<String> names() {
        List<String> out = new ArrayList<>();
        for (Manufacturer m : plugin.store().manufacturers()) if (!m.system()) out.add(m.name());
        return out;
    }

    private Manufacturer arg(CommandSender s, String name) {
        Manufacturer m = plugin.store().manufacturer(name);
        if (m == null || m.system()) {
            Msg.send(s, "<red>No manufacturer named <white><n></white>.", Msg.p("n", name));
            return null;
        }
        return m;
    }

    private boolean isOwner(CommandSender s, Manufacturer m) {
        if (s.hasPermission("mccomm.admin") || (s instanceof Player p && m.owner().equals(p.getUniqueId()))) return true;
        Msg.send(s, "<red>Only the owner of <white><m></white> can do that.", Msg.p("m", m.name()));
        return false;
    }

    @Override
    public void execute(CommandSourceStack src, String[] args) {
        CommandSender s = src.getSender();
        String sub = args.length == 0 ? "manage" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "manage" -> {
                Player p = player(s);
                if (p == null) return;
                Manufacturer m = null;
                if (args.length >= 2) m = arg(p, args[1]);
                else {
                    for (Manufacturer x : plugin.store().manufacturers()) if (x.isStaff(p.getUniqueId())) { m = x; break; }
                    if (m == null) {
                        help(p);
                        return;
                    }
                }
                if (m != null) plugin.manufacturerMenu().open(p, m);
            }
            case "create" -> {
                Player p = player(s);
                if (p == null) return;
                if (args.length < 2) {
                    Msg.send(p, "<red>Usage: /manufacturer create <name>");
                    return;
                }
                String name = args[1];
                if (!plugin.settings().carrierNamePattern.matcher(name).matches()) {
                    Msg.send(p, "<red>Names must be 2-20 characters: letters, numbers, _ - & .");
                    return;
                }
                if (plugin.store().manufacturer(name) != null) {
                    Msg.send(p, "<red>That name is taken.");
                    return;
                }
                int max = plugin.settings().maxManufacturersPerPlayer;
                long owned = plugin.store().manufacturers().stream().filter(m -> p.getUniqueId().equals(m.owner())).count();
                if (max >= 0 && !p.hasPermission("mccomm.admin") && owned >= max) {
                    Msg.send(p, "<red>You already own the maximum number of manufacturers (<n>).", Msg.p("n", "" + max));
                    return;
                }
                plugin.store().addManufacturer(new Manufacturer(name, p.getUniqueId(), false));
                Msg.send(p, "<green>Manufacturer <white><n></white> founded. Open <white>/manufacturer manage</white> to research and design towers.",
                        Msg.p("n", name));
            }
            case "delete" -> {
                if (args.length < 2) {
                    Msg.send(s, "<red>Usage: /manufacturer delete <name> confirm");
                    return;
                }
                Manufacturer m = arg(s, args[1]);
                if (m == null || !isOwner(s, m)) return;
                if (args.length < 3 || !args[2].equalsIgnoreCase("confirm")) {
                    Msg.send(s, "<yellow>This deletes <white><m></white> and its models. Installed towers keep working. Run <white>/manufacturer delete <m> confirm</white>.",
                            Msg.p("m", m.name()));
                    return;
                }
                plugin.store().removeManufacturer(m);
                Msg.send(s, "<yellow>Manufacturer <white><m></white> deleted.", Msg.p("m", m.name()));
            }
            case "list" -> {
                Msg.raw(s, "<dark_gray>━━━ <gold>Tower manufacturers</gold> ━━━");
                for (Manufacturer m : plugin.store().manufacturers()) {
                    long models = m.models().values().stream().filter(tm -> tm.published()).count();
                    Msg.raw(s, m.styled() + " <gray>· Gen " + m.researchedGen() + " · " + models + " model(s) for sale"
                            + (m.system() ? " <dark_gray>(server)" : ""));
                }
            }
            case "color" -> {
                if (args.length < 3) {
                    Msg.send(s, "<red>Usage: /manufacturer color <name> <colours...>");
                    return;
                }
                Manufacturer m = arg(s, args[1]);
                if (m == null || !isOwner(s, m)) return;
                var style = dev.mccomm.cell.util.Style.parse(join(args, 2));
                if (style == null) {
                    Msg.send(s, "<red>Unknown style. Try <white>#ff5f6d #ffc371</white> or <white>sunset</white>.");
                    return;
                }
                m.colors(style);
                plugin.store().markDirty();
                Msg.send(s, "<green>Style updated: " + m.styled());
            }
            case "employee" -> {
                if (args.length < 4) {
                    Msg.send(s, "<red>Usage: /manufacturer employee add|remove <name> <player>");
                    return;
                }
                Manufacturer m = arg(s, args[2]);
                if (m == null || !isOwner(s, m)) return;
                OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(args[3]);
                if (target == null) {
                    Msg.send(s, "<red>Unknown player <white><p></white>.", Msg.p("p", args[3]));
                    return;
                }
                if (args[1].equalsIgnoreCase("add")) m.employees().add(target.getUniqueId());
                else m.employees().remove(target.getUniqueId());
                plugin.store().markDirty();
                Msg.send(s, "<green>Updated staff of <white><m></white>.", Msg.p("m", m.name()));
            }
            default -> help(s);
        }
    }

    private void help(CommandSender s) {
        Msg.raw(s, "<dark_gray>━━━ <gold>/manufacturer</gold> ━━━");
        Msg.raw(s, "<white>/manufacturer create <name> <gray>— found a tower manufacturer");
        Msg.raw(s, "<white>/manufacturer manage [name] <gray>— research, models, wallet");
        Msg.raw(s, "<white>/manufacturer list · color · employee · delete");
    }

    @Override
    public Collection<String> suggest(CommandSourceStack src, String[] args) {
        if (args.length <= 1) return filter(SUBS, args.length == 0 ? "" : args[0]);
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && List.of("manage", "delete", "color").contains(sub)) return filter(names(), args[1]);
        if (sub.equals("employee")) {
            if (args.length == 2) return filter(List.of("add", "remove"), args[1]);
            if (args.length == 3) return filter(names(), args[2]);
            if (args.length == 4) return filter(onlineNames(), args[3]);
        }
        return List.of();
    }
}
