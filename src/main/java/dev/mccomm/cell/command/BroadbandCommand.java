package dev.mccomm.cell.command;

import dev.mccomm.cell.CellPlugin;
import dev.mccomm.cell.broadband.BroadbandService;
import dev.mccomm.cell.broadband.Computer;
import dev.mccomm.cell.broadband.Device;
import dev.mccomm.cell.broadband.Router;
import dev.mccomm.cell.model.BlockKey;
import dev.mccomm.cell.util.Msg;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

/** /broadband — set up internet at home: a router and a computer on wall signs. */
public final class BroadbandCommand extends Cmd {
    private static final List<String> SUBS = List.of("router", "computer", "extender", "connect", "disconnect", "list", "providers", "speedtest", "remove");

    public BroadbandCommand(CellPlugin plugin) {
        super(plugin);
    }

    @Override
    public String permission() {
        return "mccomm.use";
    }

    private BroadbandService bb() {
        return plugin.broadband();
    }

    private Block target(Player p) {
        return p.getTargetBlockExact(6);
    }

    /** The router you look at, else your only router. */
    private Router routerOf(Player p) {
        Block b = target(p);
        if (b != null && bb().store().deviceAt(BlockKey.of(b)) instanceof Router r) return r;
        List<Router> mine = bb().store().routersOf(p.getUniqueId());
        if (mine.size() == 1) return mine.getFirst();
        Msg.send(p, mine.isEmpty() ? "<red>You don't have a router. Look at a wall sign in your home and run <white>/broadband router install</white>."
                : "<red>Look at the router's sign first.");
        return null;
    }

    @Override
    public void execute(CommandSourceStack src, String[] args) {
        CommandSender s = src.getSender();
        Player p = player(s);
        if (p == null) return;
        if (args.length == 0) {
            Router r = routerOf(p);
            if (r != null) plugin.broadbandMenu().router(p, r, "status", 0);
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "router" -> {
                if (args.length < 2 || !args[1].equalsIgnoreCase("install")) {
                    Msg.send(p, "<red>Usage: /broadband router install [name] <gray>(look at a wall sign in your home)");
                    return;
                }
                String name = args.length > 2 ? join(args, 2) : "Home";
                Router r = bb().installRouter(p, target(p), name);
                if (r == null) return;
                Msg.send(p, "<green>Router <white><n></white> installed. Right-click the sign to choose an internet provider, "
                        + "then put a computer within <w> blocks with <white>/broadband computer install</white>.",
                        Msg.p("n", r.name()), Msg.p("w", "" + Math.round(bb().cfg().wifiRange())));
            }
            case "computer" -> {
                if (args.length < 2 || !args[1].equalsIgnoreCase("install")) {
                    Msg.send(p, "<red>Usage: /broadband computer install [name] <gray>(look at a wall sign)");
                    return;
                }
                String name = args.length > 2 ? join(args, 2) : "Computer";
                Computer c = bb().installComputer(p, target(p), name);
                if (c == null) return;
                Msg.send(p, "<green>Computer <white><n></white> set up. Right-click it to go online when a router's Wi-Fi reaches it.", Msg.p("n", c.name()));
            }
            case "list" -> list(p);
            case "providers" -> {
                Router r = routerOf(p);
                if (r != null) plugin.broadbandMenu().router(p, r, "providers", 0);
            }
            case "extender" -> {
                if (args.length < 2 || !args[1].equalsIgnoreCase("install")) {
                    Msg.send(p, "<red>Usage: /broadband extender install [name] <gray>(look at a wall sign within reach of your router's Wi-Fi)");
                    return;
                }
                String name = args.length > 2 ? join(args, 2) : "Extender";
                var x = bb().installExtender(p, target(p), name);
                if (x == null) return;
                var cost = bb().cfg().wifi().extender().price();
                Msg.send(p, "<green>Extender <white><n></white> set up. It repeats your router's Wi-Fi from here; right-click it to choose which router.", Msg.p("n", x.name()));
                if (bb().sourceOf(x) == null) Msg.send(p, "<yellow>No router of yours reaches this sign yet, so it isn't repeating anything. Move it closer to the router.");
            }
            case "connect" -> connect(p, args);
            case "disconnect" -> {
                bb().disconnect(p);
                plugin.signal().tick(); // refresh the HUD now
                Msg.send(p, "<yellow>Disconnected from Wi-Fi. Your phone uses mobile data. <gray>/broadband connect to rejoin.");
            }
            case "speedtest" -> {
                BroadbandService.Link l = bb().wifiLink(p);
                if (l == null) {
                    Msg.send(p, bb().wifiOff(p) ? "<red>You're not on Wi-Fi. Join one with <white>/broadband connect</white>."
                            : "<red>You're not connected to a working Wi-Fi. See what's in range with <white>/broadband connect</white>.");
                    return;
                }
                bb().speedtest(p, l);
            }
            case "remove" -> {
                Block b = target(p);
                Device d = b == null ? null : bb().store().deviceAt(BlockKey.of(b));
                boolean mine = (d instanceof Router r && r.owner().equals(p.getUniqueId()))
                        || (d instanceof Computer c && c.owner().equals(p.getUniqueId()))
                        || (d instanceof dev.mccomm.cell.broadband.Extender x && x.owner().equals(p.getUniqueId()));
                if (d == null || d instanceof dev.mccomm.cell.broadband.Node || !(mine || p.hasPermission("mccomm.admin"))) {
                    Msg.send(p, "<red>Look at your router or computer sign.");
                    return;
                }
                bb().removeDevice(d, true);
                Msg.send(p, "<yellow>Removed.");
            }
            default -> help(p);
        }
    }

    private static final java.util.regex.Pattern SAFE = java.util.regex.Pattern.compile("[A-Za-z0-9 _.-]+");

    /** /broadband connect: lists the Wi-Fi in range, or joins one by name ("auto" = let the phone choose). */
    private void connect(Player p, String[] args) {
        if (args.length >= 2) {
            String name = join(args, 1);
            String err = bb().connect(p, name.equalsIgnoreCase("auto") ? null : name);
            if (err != null) {
                Msg.send(p, "<red><err>", Msg.p("err", err));
                return;
            }
            plugin.signal().tick(); // refresh the HUD now
            var now = bb().wifiLink(p);
            if (name.equalsIgnoreCase("auto")) Msg.send(p, "<green>Wi-Fi is automatic again: your phone joins the best network in range.");
            else if (now == null) Msg.send(p, "<green>Saved. You'll join <white><n></white> when you're in range.", Msg.p("n", name));
            else Msg.send(p, "<green>Connected to <white><n></white> <gray>· <isp> · <spd>", Msg.p("n", now.router().name()),
                    Msg.p("isp", now.isp().name()), Msg.p("spd", Msg.speed(now.downMbps())));
            return;
        }
        List<BroadbandService.Link> nets = bb().networksInRange(p);
        var current = bb().wifiLink(p);
        Msg.raw(p, "<dark_gray>━━━ <aqua>Wi-Fi networks</aqua> ━━━");
        if (nets.isEmpty()) {
            Msg.raw(p, "<gray>No Wi-Fi reaches you. Get closer to a router, or put one up with <white>/broadband router install</white>.");
        }
        for (BroadbandService.Link l : nets) {
            boolean on = current != null && current.router().id() == l.router().id();
            String name = l.router().name();
            String line = " " + Msg.bars(bb().wifiBars(l, p.getLocation())) + " <white><n></white> <dark_gray>·</dark_gray> <gray><isp> · <spd></gray>"
                    + (on ? " <green>✔ connected" : " <yellow>click to join");
            if (SAFE.matcher(name).matches()) line = "<click:run_command:'/broadband connect " + name + "'><hover:show_text:'Join this Wi-Fi'>" + line + "</hover></click>";
            Msg.raw(p, line, Msg.p("n", name), Msg.p("isp", l.isp().name()), Msg.p("spd", Msg.speed(l.downMbps())));
        }
        Msg.raw(p, bb().wifiOff(p) ? "<gray>You're off Wi-Fi. <white>/broadband connect [name]</white> to join."
                : "<gray><white>/broadband connect [name]</white> joins one · <white>/broadband disconnect</white> leaves · <white>connect auto</white> lets your phone pick.");
    }

    private void list(Player p) {
        List<Router> routers = bb().store().routersOf(p.getUniqueId());
        List<Computer> computers = bb().store().computersOf(p.getUniqueId());
        if (routers.isEmpty() && computers.isEmpty() && bb().store().extendersOf(p.getUniqueId()).isEmpty()) {
            help(p);
            return;
        }
        Msg.raw(p, "<dark_gray>━━━ <aqua>Your home network</aqua> ━━━");
        for (Router r : routers) {
            BroadbandService.Link l = bb().link(r);
            Msg.raw(p, "<dark_gray> · <white>Router <n> <gray>· <state>",
                    Msg.p("n", r.name()), Msg.p("state", l == null ? r.state().name().toLowerCase(Locale.ROOT) : Msg.speed(l.downMbps())));
        }
        for (Computer c : computers) Msg.raw(p, "<dark_gray> · <white>Computer <n>", Msg.p("n", c.name()));
        for (var x : bb().store().extendersOf(p.getUniqueId())) {
            var src = bb().sourceOf(x);
            Msg.raw(p, "<dark_gray> · <white>Extender <n> <gray>· <state>", Msg.p("n", x.name()), Msg.p("state", src == null ? "not repeating" : "repeats " + src.name()));
        }
    }

    private void help(CommandSender s) {
        Msg.raw(s, "<dark_gray>━━━ <aqua>/broadband</aqua> ━━━");
        Msg.raw(s, "<white>/broadband router install [name] <gray>— put your router on the wall sign you look at");
        Msg.raw(s, "<gray>Right-click it to pick an internet provider and plan.");
        Msg.raw(s, "<white>/broadband computer install [name] <gray>— put a computer on another wall sign");
        Msg.raw(s, "<gray>Within " + Math.round(bb().cfg().wifiRange()) + " blocks of the router it goes online.");
        Msg.raw(s, "<white>/broadband extender install [name] <gray>— repeat your router's Wi-Fi from another wall sign");
        Msg.raw(s, "<white>/broadband connect [name] <gray>— see the Wi-Fi in range and join one (it shows on your HUD)");
        Msg.raw(s, "<white>/broadband disconnect <gray>· <white>speedtest <gray>— test the Wi-Fi you are on");
        Msg.raw(s, "<white>/broadband <gray>· <white>providers <gray>· <white>list <gray>· <white>remove");
    }

    @Override
    public Collection<String> suggest(CommandSourceStack src, String[] args) {
        if (args.length <= 1) return filter(SUBS, args.length == 0 ? "" : args[0]);
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && (sub.equals("router") || sub.equals("computer") || sub.equals("extender"))) return filter(List.of("install"), args[1]);
        if (args.length == 2 && sub.equals("connect") && src.getSender() instanceof Player p) {
            List<String> names = new java.util.ArrayList<>(List.of("auto"));
            for (BroadbandService.Link l : bb().networksInRange(p)) names.add(l.router().name());
            return filter(names, args[1]);
        }
        return List.of();
    }
}
