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
    private static final List<String> SUBS = List.of("router", "computer", "list", "providers", "speedtest", "remove");

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
            case "speedtest" -> {
                BroadbandService.Link l = bb().wifiLink(p);
                if (l == null) {
                    Msg.send(p, "<red>No Wi-Fi with a working connection reaches you.");
                    return;
                }
                bb().speedtest(p, l);
            }
            case "remove" -> {
                Block b = target(p);
                Device d = b == null ? null : bb().store().deviceAt(BlockKey.of(b));
                boolean mine = (d instanceof Router r && r.owner().equals(p.getUniqueId()))
                        || (d instanceof Computer c && c.owner().equals(p.getUniqueId()));
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

    private void list(Player p) {
        List<Router> routers = bb().store().routersOf(p.getUniqueId());
        List<Computer> computers = bb().store().computersOf(p.getUniqueId());
        if (routers.isEmpty() && computers.isEmpty()) {
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
    }

    private void help(CommandSender s) {
        Msg.raw(s, "<dark_gray>━━━ <aqua>/broadband</aqua> ━━━");
        Msg.raw(s, "<white>/broadband router install [name] <gray>— put your router on the wall sign you look at");
        Msg.raw(s, "<gray>Right-click it to pick an internet provider and plan.");
        Msg.raw(s, "<white>/broadband computer install [name] <gray>— put a computer on another wall sign");
        Msg.raw(s, "<gray>Within " + Math.round(bb().cfg().wifiRange()) + " blocks of the router it goes online.");
        Msg.raw(s, "<white>/broadband <gray>· <white>providers <gray>· <white>list <gray>· <white>speedtest <gray>· <white>remove");
    }

    @Override
    public Collection<String> suggest(CommandSourceStack src, String[] args) {
        if (args.length <= 1) return filter(SUBS, args.length == 0 ? "" : args[0]);
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && (sub.equals("router") || sub.equals("computer"))) return filter(List.of("install"), args[1]);
        return List.of();
    }
}
