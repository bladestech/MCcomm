package dev.mccomm.cell.command;

import dev.mccomm.cell.CellPlugin;
import dev.mccomm.cell.model.Carrier;
import dev.mccomm.cell.util.Msg;
import dev.mccomm.cell.workforce.Facility;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** /facility create|remove|check|list: company buildings (maintenance stations for now). */
public final class FacilityCommand extends Cmd {
    private static final List<String> SUBS = List.of("create", "remove", "check", "list");
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_\\-]{1,16}$");

    public FacilityCommand(CellPlugin plugin) {
        super(plugin);
    }

    @Override
    public void execute(CommandSourceStack src, String[] args) {
        CommandSender s = src.getSender();
        if (args.length == 0) {
            help(s);
            return;
        }
        if (!plugin.workforce().enabled()) {
            Msg.send(s, "<red>Facilities are turned off on this server.");
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create" -> create(s, args);
            case "remove" -> remove(s, args);
            case "check" -> check(s, args);
            case "list" -> list(s, args);
            default -> help(s);
        }
    }

    private void help(CommandSender s) {
        Msg.raw(s, "<dark_gray>━━━ <aqua>Facilities</aqua> ━━━");
        Msg.raw(s, "<white>/facility create station <name> [carrier] <gray>· look at a wall sign inside the building");
        Msg.raw(s, "<white>/facility check <name> [carrier] <gray>· re-count beds and barrels");
        Msg.raw(s, "<white>/facility remove <name> [carrier]");
        Msg.raw(s, "<white>/facility list [carrier]");
        Msg.raw(s, "<gray>A station needs a closed, roofed room with one bed per technician. Barrels hold spare parts (faster repairs).");
    }

    /** The carrier named in args[idx], or the only one the player works for. */
    private Carrier carrier(CommandSender s, String[] args, int idx) {
        if (args.length > idx) {
            Carrier c = carrierArg(s, args[idx]);
            if (c == null) return null;
            if (s instanceof Player p && !p.hasPermission("mccomm.admin") && !c.isStaff(p.getUniqueId())) {
                Msg.send(s, "<red>You don't work for <white><c></white>.", Msg.p("c", c.name()));
                return null;
            }
            return c;
        }
        if (!(s instanceof Player p)) {
            Msg.send(s, "<red>Name the carrier.");
            return null;
        }
        List<Carrier> mine = new ArrayList<>();
        for (Carrier x : plugin.store().carriers()) if (x.isStaff(p.getUniqueId()) && !x.mvno()) mine.add(x);
        if (mine.size() != 1) {
            Msg.send(s, mine.isEmpty() ? "<red>You don't run a carrier with towers." : "<red>You work for several carriers: add the carrier's name.");
            return null;
        }
        return mine.getFirst();
    }

    private void create(CommandSender s, String[] args) {
        // Admin/console: /facility create station <name> <carrier> <x> <y> <z> [world]
        if (args.length >= 7 && (!(s instanceof Player) || s.hasPermission("mccomm.admin"))) {
            createAt(s, args);
            return;
        }
        Player p = player(s);
        if (p == null) return;
        if (args.length < 3) {
            Msg.send(p, "<red>Usage: /facility create station <name> [carrier]");
            return;
        }
        Facility.Type type = Facility.Type.of(args[1]);
        if (type == null) {
            Msg.send(p, "<red>Facility types: <white>station");
            return;
        }
        String name = args[2];
        if (!NAME.matcher(name).matches()) {
            Msg.send(p, "<red>Names are 1-16 letters, digits, - or _.");
            return;
        }
        Carrier c = carrier(p, args, 3);
        if (c == null) return;
        if (c.mvno()) {
            Msg.send(p, "<red>MVNOs have no towers to maintain.");
            return;
        }
        Block b = p.getTargetBlockExact(6);
        String err = plugin.workforce().create(p, b, c, type, name);
        if (err != null) {
            Msg.send(p, "<red>" + Msg.escape(err));
            return;
        }
        Facility f = plugin.workforce().facilityByName(c.id(), name);
        Msg.send(p, "<green>Maintenance station <white><n></white> is open: <white><b></white> bed(s), <white><r></white> barrel(s). Rent <white><rent></white>/day.",
                Msg.p("n", name), Msg.p("b", "" + f.capacity()), Msg.p("r", "" + f.barrels()),
                Msg.p("rent", plugin.settings().money(plugin.workforce().rent(f))));
        Msg.send(p, "<gray>Hire technicians in <white>/carrier manage</white> → Operations, or right-click the sign.");
    }

    private void createAt(CommandSender s, String[] args) {
        Facility.Type type = Facility.Type.of(args[1]);
        Carrier c = carrierArg(s, args[3]);
        if (type == null || c == null) return;
        org.bukkit.World world = args.length >= 8 ? org.bukkit.Bukkit.getWorld(args[7]) : org.bukkit.Bukkit.getWorlds().getFirst();
        if (world == null) {
            Msg.send(s, "<red>No such world.");
            return;
        }
        int x, y, z;
        try {
            x = Integer.parseInt(args[4]);
            y = Integer.parseInt(args[5]);
            z = Integer.parseInt(args[6]);
        } catch (NumberFormatException e) {
            Msg.send(s, "<red>Usage: /facility create station <name> <carrier> <x> <y> <z> [world]");
            return;
        }
        String err = plugin.workforce().create(null, world.getBlockAt(x, y, z), c, type, args[2]);
        if (err != null) Msg.send(s, "<red>" + Msg.escape(err));
        else Msg.send(s, "<green>Created <white><n></white> for <white><c></white>.", Msg.p("n", args[2]), Msg.p("c", c.name()));
    }

    private Facility named(CommandSender s, String[] args) {
        if (args.length < 2) {
            Msg.send(s, "<red>Name the facility.");
            return null;
        }
        Carrier c = carrier(s, args, 2);
        if (c == null) return null;
        Facility f = plugin.workforce().facilityByName(c.id(), args[1]);
        if (f == null) Msg.send(s, "<red><c> has no facility called <white><n></white>.", Msg.p("c", c.name()), Msg.p("n", args[1]));
        return f;
    }

    private void remove(CommandSender s, String[] args) {
        Facility f = named(s, args);
        if (f == null) return;
        plugin.workforce().remove(f, true);
        Msg.send(s, "<yellow>Removed <white><n></white>. Its staff are idle until there's room elsewhere.", Msg.p("n", f.name()));
    }

    private void check(CommandSender s, String[] args) {
        Facility f = named(s, args);
        if (f == null) return;
        String err = plugin.workforce().recheck(f);
        if (err != null) Msg.send(s, "<red>" + Msg.escape(err));
        else Msg.send(s, "<green><n>: <white><b></white> bed(s), <white><r></white> barrel(s).",
                Msg.p("n", f.name()), Msg.p("b", "" + f.capacity()), Msg.p("r", "" + f.barrels()));
    }

    private void list(CommandSender s, String[] args) {
        Carrier c = carrier(s, args, 1);
        if (c == null) return;
        List<Facility> list = plugin.workforce().facilitiesOf(c.id());
        Msg.raw(s, "<dark_gray>━━━ <aqua><c> facilities</aqua> ━━━", Msg.p("c", c.name()));
        if (list.isEmpty()) Msg.raw(s, "<gray>None yet.");
        for (Facility f : list) {
            Msg.raw(s, "<white><n> <gray>· <t> · <w> <x>, <z> · <s>/<b> techs",
                    Msg.p("n", f.name()), Msg.p("t", f.type().display), Msg.p("w", f.world() == null ? "?" : f.world().getName()),
                    Msg.p("x", "" + f.x()), Msg.p("z", "" + f.z()), Msg.p("s", "" + plugin.workforce().staffAt(f).size()),
                    Msg.p("b", "" + f.capacity()));
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack src, String[] args) {
        if (args.length <= 1) return filter(SUBS, args.length == 0 ? "" : args[0]);
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("create")) {
            if (args.length == 2) return filter(List.of("station"), args[1]);
            if (args.length == 3) return List.of("<name>");
            if (args.length == 4) return filter(carrierNames(), args[3]);
        }
        if (sub.equals("list") && args.length == 2) return filter(carrierNames(), args[1]);
        if ((sub.equals("remove") || sub.equals("check")) && args.length == 2 && src.getSender() instanceof Player p) {
            List<String> out = new ArrayList<>();
            for (var f : plugin.workforce().facilities()) {
                Carrier c = plugin.store().carrier(f.companyId());
                if (c != null && c.isStaff(p.getUniqueId())) out.add(f.name());
            }
            return filter(out, args[1]);
        }
        if ((sub.equals("remove") || sub.equals("check")) && args.length == 3) return filter(carrierNames(), args[2]);
        return List.of();
    }

    @Override
    public String permission() {
        return null;
    }
}
