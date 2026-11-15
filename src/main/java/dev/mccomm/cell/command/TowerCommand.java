package dev.mccomm.cell.command;

import dev.mccomm.cell.CellPlugin;
import dev.mccomm.cell.band.Band;
import dev.mccomm.cell.model.Carrier;
import dev.mccomm.cell.model.Tower;
import dev.mccomm.cell.tower.TowerManager;
import dev.mccomm.cell.util.Msg;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/** /tower — build and manage towers. Most subcommands act on the wall sign you are looking at. */
public final class TowerCommand extends Cmd {
    private static final List<String> SUBS = List.of("create", "rename", "remove", "info", "manage", "list", "setgen", "tp");

    public TowerCommand(CellPlugin plugin) {
        super(plugin);
    }

    @Override
    public String permission() {
        return "mccomm.use";
    }

    private Block target(Player p) {
        return p.getTargetBlockExact(6);
    }

    private Tower lookedAt(Player p) {
        Tower t = plugin.towers().towerAt(target(p));
        if (t == null) Msg.send(p, "<red>Look at a cell tower sign.");
        return t;
    }

    @Override
    public void execute(CommandSourceStack src, String[] args) {
        CommandSender s = src.getSender();
        if (args.length == 0) {
            help(s);
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("list")) {
            list(s, args);
            return;
        }
        Player p = player(s);
        if (p == null) return;
        switch (sub) {
            case "create" -> create(p, args);
            case "remove" -> {
                Tower t = lookedAt(p);
                if (t == null) return;
                if (!plugin.towers().canManage(p, t)) {
                    Msg.send(p, "<red>You don't manage this tower.");
                    return;
                }
                plugin.towers().remove(t, true);
                Msg.send(p, "<yellow>Tower <id> removed. Its backhaul block was destroyed.", Msg.p("id", t.name()));
            }
            case "rename" -> {
                Tower t = lookedAt(p);
                if (t == null) return;
                if (!plugin.towers().canManage(p, t)) {
                    Msg.send(p, "<red>You don't manage this tower.");
                    return;
                }
                if (args.length < 2) {
                    Msg.send(p, "<red>Usage: /tower rename <name>");
                    return;
                }
                if (!args[1].equalsIgnoreCase(t.name()) && !validName(p, args[1])) return;
                String old = t.name();
                t.name(args[1]);
                plugin.towers().refresh(t);
                Msg.send(p, "<green>Tower <old> renamed to <white><new></white>.", Msg.p("old", old), Msg.p("new", args[1]));
            }
            case "info" -> {
                Tower t = lookedAt(p);
                if (t != null) plugin.towerListener().sendInfo(p, t);
            }
            case "manage" -> {
                Tower t = lookedAt(p);
                if (t == null) return;
                if (!plugin.towers().canManage(p, t)) {
                    Msg.send(p, "<red>You don't manage this tower.");
                    return;
                }
                plugin.gui().open(p, t);
            }
            case "setgen" -> {
                if (!p.hasPermission("mccomm.admin")) {
                    Msg.send(p, "<red>Admins only.");
                    return;
                }
                Tower t = lookedAt(p);
                if (t == null) return;
                int gen;
                try {
                    gen = Integer.parseInt(args.length > 1 ? args[1] : "");
                } catch (NumberFormatException e) {
                    Msg.send(p, "<red>Usage: /tower setgen <1-<max>>", Msg.p("max", "" + plugin.settings().maxGeneration()));
                    return;
                }
                if (!plugin.settings().generations.containsKey(gen)) {
                    Msg.send(p, "<red>No generation <g> in config.", Msg.p("g", "" + gen));
                    return;
                }
                t.generation(gen);
                t.enabledBands().removeIf(b -> !plugin.settings().supports(gen, b));
                plugin.towers().refresh(t);
                Msg.send(p, "<green>Tower <id> set to Gen <g>.", Msg.p("id", t.name()), Msg.p("g", "" + gen));
            }
            case "tp" -> {
                if (!p.hasPermission("mccomm.admin")) {
                    Msg.send(p, "<red>Admins only.");
                    return;
                }
                Tower t = towerById(p, args);
                if (t == null) return;
                Location l = t.antenna(0);
                if (l == null) {
                    Msg.send(p, "<red>That tower's world isn't loaded.");
                    return;
                }
                p.teleportAsync(l.add(0, 0.5, 0));
            }
            default -> help(p);
        }
    }

    private Tower towerById(CommandSender s, String[] args) {
        if (args.length < 2) {
            Msg.send(s, "<red>Give a tower name, like <white>/tower tp LF1");
            return null;
        }
        Tower t = plugin.store().towerByName(args[1]);
        if (t == null) Msg.send(s, "<red>No tower named <white><n></white>.", Msg.p("n", args[1]));
        return t;
    }

    private boolean validName(CommandSender s, String name) {
        if (!plugin.settings().towerNamePattern.matcher(name).matches()) {
            Msg.send(s, "<red>Tower names are 1-12 letters, numbers, _ or -, like <white>LF1</white>.");
            return false;
        }
        if (plugin.store().towerByName(name) != null) {
            Msg.send(s, "<red>A tower named <white><n></white> already exists.", Msg.p("n", name));
            return false;
        }
        return true;
    }

    private void help(CommandSender s) {
        Msg.raw(s, "<dark_gray>━━━ <aqua>/tower</aqua> ━━━");
        Msg.raw(s, "<white>/tower create <carrier> <name> <gray>— while looking at a wall sign (name like LF1)");
        Msg.raw(s, "<white>/tower manage <gray>— open the tower GUI (or right-click the sign)");
        Msg.raw(s, "<white>/tower info <gray>· <white>/tower rename <name> <gray>· <white>/tower remove");
        Msg.raw(s, "<white>/tower list [carrier]");
        if (s.hasPermission("mccomm.admin")) Msg.raw(s, "<white>/tower setgen <gen> <gray>· <white>/tower tp <name>");
    }

    private void create(Player p, String[] args) {
        if (args.length < 3) {
            Msg.send(p, "<red>Usage: /tower create <carrier> <name> <gray>(look at a wall sign, name like LF1)");
            return;
        }
        String name = args[2];
        if (!validName(p, name)) return;
        Carrier c = carrierArg(p, args[1]);
        if (c == null) return;
        if (!p.hasPermission("mccomm.admin") && !c.isStaff(p.getUniqueId())) {
            Msg.send(p, "<red>You don't work for <white><c></white>.", Msg.p("c", c.name()));
            return;
        }
        if (c.mvno()) {
            Msg.send(p, "<red><white><c></white> is an MVNO: it runs on a host network and can't build towers.", Msg.p("c", c.name()));
            return;
        }
        Block b = target(p);
        if (b == null || !TowerManager.isWallSign(b)) {
            Msg.send(p, "<red>Look at a wall sign (standing and hanging signs can't be towers).");
            return;
        }
        if (plugin.towers().towerAt(b) != null) {
            Msg.send(p, "<red>That sign is already a tower.");
            return;
        }
        int max = plugin.settings().maxTowersPerCarrier;
        if (max >= 0 && !p.hasPermission("mccomm.admin") && plugin.store().towersOf(c.id()).size() >= max) {
            Msg.send(p, "<red><c> already has the maximum of <n> towers.", Msg.p("c", c.name()), Msg.p("n", "" + max));
            return;
        }
        long cost = plugin.settings().towerCost;
        boolean free = p.hasPermission("mccomm.admin");
        if (!free && cost > 0) {
            if (c.balance() < cost) {
                Msg.send(p, "<red>Building a tower costs <white><cost></white> from <white><c></white>'s wallet, which has <white><b></white>. Deposit in <white>/carrier manage</white>.",
                        Msg.p("cost", plugin.settings().money(cost)), Msg.p("c", c.name()), Msg.p("b", plugin.settings().money(c.balance())));
                return;
            }
            c.balance(c.balance() - cost);
            c.log(-cost, "Built tower " + name);
        }
        Tower t = plugin.towers().create(p, b, c, name);
        List<String> bands = new ArrayList<>();
        for (Band band : t.enabledBands()) bands.add(plugin.settings().spec(band).name());
        Msg.send(p, "<green>Tower <id> built for <white><c></white> (<model>, bands <bands>).",
                Msg.p("id", t.name()), Msg.p("c", c.name()),
                Msg.p("model", plugin.settings().generation(1).model()), Msg.p("bands", String.join(", ", bands)));
        if (!free && cost > 0) Msg.send(p, "<gray>Paid <white><cost></white> from <white><c></white>'s wallet.",
                Msg.p("cost", plugin.settings().money(cost)), Msg.p("c", c.name()));
        Msg.send(p, "<yellow>It's offline until you install a backhaul block. Right-click the sign to open the GUI.");
    }

    private void list(CommandSender s, String[] args) {
        Carrier filter = null;
        if (args.length > 1) {
            filter = carrierArg(s, args[1]);
            if (filter == null) return;
        }
        Msg.raw(s, "<dark_gray>━━━ <aqua>Towers</aqua> ━━━");
        int n = 0;
        for (Tower t : plugin.store().towers()) {
            if (filter != null && !t.carrierId().equals(filter.id())) continue;
            Carrier c = plugin.store().carrier(t.carrierId());
            String world = t.world() == null ? "?" : t.world().getName();
            Msg.raw(s, "<gray><id> <white><c></white> · Gen <g> · <w> <x>, <y>, <z> · " + (t.online() ? "<green>online" : "<red>offline"),
                    Msg.p("id", t.name()), Msg.p("c", c == null ? "?" : c.name()), Msg.p("g", "" + t.generation()),
                    Msg.p("w", world), Msg.p("x", "" + t.x()), Msg.p("y", "" + t.y()), Msg.p("z", "" + t.z()));
            if (++n >= 30) {
                Msg.raw(s, "<dark_gray>…and more");
                break;
            }
        }
        if (n == 0) Msg.raw(s, "<gray>No towers.");
    }

    @Override
    public Collection<String> suggest(CommandSourceStack src, String[] args) {
        if (args.length <= 1) return filter(SUBS, args.length == 0 ? "" : args[0]);
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && (sub.equals("create") || sub.equals("list"))) {
            if (sub.equals("create") && src.getSender() instanceof Player p && !p.hasPermission("mccomm.admin")) {
                List<String> mine = new ArrayList<>();
                for (Carrier c : plugin.store().carriers()) if (c.isStaff(p.getUniqueId())) mine.add(c.name());
                return filter(mine, args[1]);
            }
            return filter(carrierNames(), args[1]);
        }
        if (args.length == 2 && sub.equals("tp")) {
            List<String> names = new ArrayList<>();
            for (Tower t : plugin.store().towers()) names.add(t.name());
            return filter(names, args[1]);
        }
        if (args.length == 3 && sub.equals("create")) return List.of("<name>");
        if (args.length == 2 && sub.equals("setgen")) {
            List<String> g = new ArrayList<>();
            for (int k : plugin.settings().generations.keySet()) g.add("" + k);
            return filter(g, args[1]);
        }
        return List.of();
    }
}
