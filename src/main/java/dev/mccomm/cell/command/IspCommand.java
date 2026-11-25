package dev.mccomm.cell.command;

import dev.mccomm.cell.CellPlugin;
import dev.mccomm.cell.broadband.BroadbandService;
import dev.mccomm.cell.broadband.InternetPlan;
import dev.mccomm.cell.broadband.Isp;
import dev.mccomm.cell.broadband.Node;
import dev.mccomm.cell.broadband.Technology;
import dev.mccomm.cell.util.Msg;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** /isp — start and run an internet service provider. */
public final class IspCommand extends Cmd {
    private static final List<String> SUBS = List.of("create", "delete", "info", "list", "manage", "node", "plan", "deposit", "withdraw", "employee", "color",
            "transit", "peer", "bundle");

    public IspCommand(CellPlugin plugin) {
        super(plugin);
    }

    @Override
    public String permission() {
        return "mccomm.use";
    }

    private BroadbandService bb() {
        return plugin.broadband();
    }

    private List<String> ispNames() {
        List<String> out = new ArrayList<>();
        for (Isp i : bb().store().isps()) out.add(i.name());
        return out;
    }

    private Isp ispArg(CommandSender s, String name) {
        Isp i = bb().store().isp(name);
        if (i == null) Msg.send(s, "<red>No ISP named <white><n></white>.", Msg.p("n", name));
        return i;
    }

    /** The ISP the player means: the named one, or the only one they work for. */
    private Isp myIsp(Player p, String explicit) {
        if (explicit != null) {
            Isp i = ispArg(p, explicit);
            if (i != null && !bb().canManage(p, i)) {
                Msg.send(p, "<red>You don't work for <white><n></white>.", Msg.p("n", i.name()));
                return null;
            }
            return i;
        }
        List<Isp> mine = bb().store().ispsStaffedBy(p.getUniqueId());
        if (mine.size() == 1) return mine.getFirst();
        Msg.send(p, mine.isEmpty() ? "<red>You don't run an ISP. Start one with <white>/isp create <name></white>."
                : "<red>You work for several ISPs: add the ISP's name to the command.");
        return null;
    }

    @Override
    public void execute(CommandSourceStack src, String[] args) {
        CommandSender s = src.getSender();
        if (args.length == 0) {
            if (s instanceof Player p && !bb().store().ispsStaffedBy(p.getUniqueId()).isEmpty()) {
                List<Isp> mine = bb().store().ispsStaffedBy(p.getUniqueId());
                if (mine.size() == 1) {
                    plugin.broadbandMenu().isp(p, mine.getFirst());
                    return;
                }
            }
            help(s);
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create" -> create(s, args);
            case "delete" -> delete(s, args);
            case "info" -> info(s, args);
            case "list" -> list(s);
            case "manage" -> {
                Player p = player(s);
                if (p == null) return;
                Isp i = myIsp(p, args.length > 1 ? args[1] : null);
                if (i != null) plugin.broadbandMenu().isp(p, i);
            }
            case "node" -> node(s, args);
            case "plan" -> plan(s, args);
            case "deposit" -> money(s, args, true);
            case "withdraw" -> money(s, args, false);
            case "employee" -> employee(s, args);
            case "color" -> color(s, args);
            case "transit" -> {
                Player p = player(s);
                if (p == null) return;
                Isp i = myIsp(p, args.length > 1 ? args[1] : null);
                if (i != null) bb().upgradeTransit(p, i);
            }
            case "peer" -> peer(s, args);
            case "bundle" -> bundle(s, args);
            default -> help(s);
        }
    }

    /** /isp peer offer|accept|end <isp> [my isp] */
    private void peer(CommandSender s, String[] args) {
        Player p = player(s);
        if (p == null) return;
        if (args.length < 3) {
            Msg.send(p, "<red>Usage: /isp peer offer|accept|end <isp> [your isp]");
            return;
        }
        Isp other = ispArg(p, args[2]);
        Isp mine = other == null ? null : myIsp(p, args.length > 3 ? args[3] : null);
        if (other == null || mine == null) return;
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "offer" -> bb().offerPeer(p, mine, other);
            case "accept" -> bb().acceptPeer(p, mine, other);
            case "end" -> bb().endPeer(p, mine, other);
            default -> Msg.send(p, "<red>Usage: /isp peer offer|accept|end <isp> [your isp]");
        }
    }

    /** /isp bundle offer <carrier> <isp%> <carrier%> · accept <isp> · end <carrier> · list */
    private void bundle(CommandSender s, String[] args) {
        Player p = player(s);
        if (p == null) return;
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "offer" -> {
                if (args.length < 5) {
                    Msg.send(p, "<red>Usage: /isp bundle offer <carrier> <isp discount %> <carrier discount %>");
                    return;
                }
                Isp isp = myIsp(p, null);
                var carrier = carrierArg(p, args[2]);
                if (isp == null || carrier == null) return;
                try {
                    bb().offerBundle(p, isp, carrier, Integer.parseInt(args[3]), Integer.parseInt(args[4]));
                } catch (NumberFormatException e) {
                    Msg.send(p, "<red>Discounts are whole percentages.");
                }
            }
            case "accept" -> {
                if (args.length < 3) {
                    Msg.send(p, "<red>Usage: /isp bundle accept <isp> [carrier]");
                    return;
                }
                Isp isp = ispArg(p, args[2]);
                if (isp == null) return;
                dev.mccomm.cell.model.Carrier carrier = null;
                if (args.length > 3) carrier = carrierArg(p, args[3]);
                else {
                    for (var b : isp.bundles().values()) {
                        var c = plugin.store().carrier(b.carrierId());
                        if (c != null && !b.accepted() && (c.isStaff(p.getUniqueId()) || p.hasPermission("mccomm.admin"))) carrier = c;
                    }
                }
                if (carrier == null) {
                    Msg.send(p, "<red>No pending offer from <n> for a carrier you run.", Msg.p("n", isp.name()));
                    return;
                }
                bb().acceptBundle(p, carrier, isp);
            }
            case "end" -> {
                if (args.length < 3) {
                    Msg.send(p, "<red>Usage: /isp bundle end <carrier> [isp]");
                    return;
                }
                var carrier = carrierArg(p, args[2]);
                Isp isp = args.length > 3 ? ispArg(p, args[3]) : bb().store().ispsStaffedBy(p.getUniqueId()).stream().findFirst().orElse(null);
                if (isp == null) isp = bb().store().isps().stream().filter(i -> i.bundles().containsKey(carrierId(args[2]))).findFirst().orElse(null);
                if (carrier == null || isp == null) return;
                bb().endBundle(p, isp, carrier);
            }
            case "list" -> {
                boolean any = false;
                for (Isp isp : bb().store().isps()) {
                    for (var b : isp.bundles().values()) {
                        var c = plugin.store().carrier(b.carrierId());
                        any = true;
                        Msg.raw(p, "<dark_gray> · " + isp.styled() + " <gray>+ <white><cn> <gray>· <white><d1>%</white>/<white><d2>%</white>" + (b.accepted() ? "" : " <yellow>(pending)"),
                                Msg.p("cn", c == null ? b.carrierId() : c.name()), Msg.p("d1", "" + b.ispDiscount()), Msg.p("d2", "" + b.carrierDiscount()));
                    }
                }
                if (!any) Msg.send(p, "<gray>No bundles yet. An ISP offers one with <white>/isp bundle offer <carrier> <isp%> <carrier%></white>.");
            }
            default -> Msg.send(p, "<red>Usage: /isp bundle offer|accept|end|list");
        }
    }

    private static String carrierId(String name) {
        return dev.mccomm.cell.model.Carrier.idOf(name);
    }

    private void help(CommandSender s) {
        Msg.raw(s, "<dark_gray>━━━ <aqua>/isp</aqua> ━━━");
        Msg.raw(s, "<white>/isp create <name> <gray>— start an internet service provider");
        Msg.raw(s, "<white>/isp manage [isp] <gray>— open the ISP menu (or just <white>/isp</white>)");
        Msg.raw(s, "<white>/isp node install <tech> <name> [isp] <gray>— build a node on the wall sign you look at");
        Msg.raw(s, "<gray>Technologies: <white>dsl, cable, fiber, satellite");
        Msg.raw(s, "<white>/isp plan add <tech> <price> <mbps> <name...> <gray>— sell a new plan");
        Msg.raw(s, "<white>/isp deposit <amount> [isp] <gray>· <white>/isp withdraw <amount> [isp]");
        Msg.raw(s, "<white>/isp info [isp] <gray>· <white>/isp list <gray>· <white>/isp employee add|remove <isp> <player>");
        Msg.raw(s, "<white>/isp color <isp> <colors...> <gray>· <white>/isp delete <isp> confirm");
        Msg.raw(s, "<white>/isp create <name> carrier <carrier> <gray>— a carrier's 5G Home business (runs on its towers)");
        Msg.raw(s, "<white>/isp transit [isp] <gray>— buy more backbone · <white>/isp peer offer|accept|end <isp> <gray>— share it with another ISP");
        Msg.raw(s, "<white>/isp bundle offer|accept|end|list <gray>— joint discounts with a carrier");
        Msg.raw(s, "<white>/isp node repair <name> [isp] <gray>— pay a technician to fix a node fault");
        Msg.raw(s, "<gray>Customers sign up on their router: <white>/broadband");
    }

    private void create(CommandSender s, String[] args) {
        Player p = player(s);
        if (p == null) return;
        if (!p.hasPermission("mccomm.isp.create")) {
            Msg.send(p, "<red>You can't start an ISP.");
            return;
        }
        if (args.length < 2) {
            Msg.send(p, "<red>Usage: /isp create <name>");
            return;
        }
        if (args.length >= 4 && args[2].equalsIgnoreCase("carrier")) {
            var carrier = carrierArg(p, args[3]);
            if (carrier == null) return;
            Isp home = bb().createCarrierIsp(p, args[1], carrier);
            if (home == null) return;
            Msg.send(p, "<green>5G Home business <white><n></white> created for <white><c></white>. Customers connect to the carrier's towers: no nodes to build. Edit its plan in <white>/isp manage</white>.",
                    Msg.p("n", home.name()), Msg.p("c", carrier.name()));
            return;
        }
        Isp isp = bb().createIsp(p, args[1]);
        if (isp == null) return;
        Msg.send(p, "<green>ISP <white><n></white> created with a starter plan for each technology. Deposit credits with <white>/isp deposit <amount></white>, "
                + "then look at a wall sign and run <white>/isp node install <tech> <name></white>.", Msg.p("n", isp.name()));
    }

    private void delete(CommandSender s, String[] args) {
        if (args.length < 2) {
            Msg.send(s, "<red>Usage: /isp delete <name> confirm");
            return;
        }
        Isp isp = ispArg(s, args[1]);
        if (isp == null) return;
        boolean ok = !(s instanceof Player p) || p.hasPermission("mccomm.admin") || isp.owner().equals(p.getUniqueId());
        if (!ok) {
            Msg.send(s, "<red>Only the owner can shut an ISP down.");
            return;
        }
        if (args.length < 3 || !args[2].equalsIgnoreCase("confirm")) {
            Msg.send(s, "<yellow>This shuts down <white><n></white>: its <nodes> node(s) come down and its customers lose service. Run <white>/isp delete <n> confirm</white>.",
                    Msg.p("n", isp.name()), Msg.p("nodes", "" + bb().store().nodesOf(isp.id()).size()));
            return;
        }
        bb().deleteIsp(isp);
        Msg.send(s, "<yellow>ISP <white><n></white> shut down.", Msg.p("n", isp.name()));
    }

    private void info(CommandSender s, String[] args) {
        Isp isp;
        if (args.length > 1) {
            isp = ispArg(s, args[1]);
        } else if (s instanceof Player p && bb().store().ispsStaffedBy(p.getUniqueId()).size() == 1) {
            isp = bb().store().ispsStaffedBy(p.getUniqueId()).getFirst();
        } else {
            Msg.send(s, "<red>Usage: /isp info <name>");
            return;
        }
        if (isp == null) return;
        Msg.raw(s, "<dark_gray>━━━ " + isp.styled() + " <dark_gray>━━━");
        Msg.raw(s, "<gray>Owner: <white><o>", Msg.p("o", name(isp.owner())));
        List<String> techs = new ArrayList<>();
        for (Technology t : Technology.values()) {
            long n = bb().store().nodesOf(isp.id()).stream().filter(x -> x.tech() == t).count();
            if (n > 0) techs.add(bb().cfg().spec(t).name() + " ×" + n);
        }
        Msg.raw(s, "<gray>Nodes: <white><n>", Msg.p("n", techs.isEmpty() ? "none" : String.join(", ", techs)));
        Msg.raw(s, "<gray>Customers: <white><n>", Msg.p("n", "" + bb().customers(isp)));
        Msg.raw(s, "<gray>Plans:");
        for (InternetPlan pl : isp.plans().values()) {
            Msg.raw(s, "<dark_gray> · <white><p> <gray>(<t>) <white><price> <gray>· cap <white><cap>",
                    Msg.p("p", pl.name()), Msg.p("t", bb().cfg().spec(pl.tech()).name()),
                    Msg.p("price", plugin.settings().money(pl.price())), Msg.p("cap", Msg.speed(pl.speedCapMbps())));
        }
        Msg.raw(s, "<gray>Rating: " + bb().ratingLore(isp));
        if (!isp.carrierBacked()) Msg.raw(s, "<gray>Transit: tier <white><t></white> · <white><cap></white> backbone · peers: <white><p></white>",
                Msg.p("t", "" + isp.transitTier()), Msg.p("cap", Msg.speed(bb().transitCapacity(isp))), Msg.p("p", isp.peers().isEmpty() ? "none" : String.join(", ", isp.peers())));
        Msg.raw(s, isp.paidUp() ? "<green>Network up" : "<red>Network down");
    }

    private void list(CommandSender s) {
        if (bb().store().isps().isEmpty()) {
            Msg.send(s, "<gray>No ISPs yet. Start one with <white>/isp create <name></white>.");
            return;
        }
        Msg.raw(s, "<dark_gray>━━━ <aqua>Internet providers</aqua> ━━━");
        for (Isp i : bb().store().isps()) {
            Msg.raw(s, "<dark_gray> · " + i.styled() + " <gray>· <white><n> <gray>node(s) · <white><cust> <gray>customer(s)" + (i.paidUp() ? "" : " <red>(down)"),
                    Msg.p("n", "" + bb().store().nodesOf(i.id()).size()), Msg.p("cust", "" + bb().customers(i)));
        }
    }

    private void node(CommandSender s, String[] args) {
        Player p = player(s);
        if (p == null) return;
        if (args.length >= 3 && args[1].equalsIgnoreCase("repair")) {
            Isp owner = myIsp(p, args.length > 3 ? args[3] : null);
            if (owner == null) return;
            Node n = bb().store().nodeByName(owner.id(), args[2]);
            if (n == null) {
                Msg.send(p, "<red>No node named <white><n></white>.", Msg.p("n", args[2]));
                return;
            }
            if (!n.faulted()) {
                Msg.send(p, "<gray>That node has no fault.");
                return;
            }
            bb().repairNode(p, n);
            return;
        }
        if (args.length < 2 || !args[1].equalsIgnoreCase("install")) {
            Msg.send(p, "<red>Usage: /isp node install <tech> <name> [isp] <gray>(look at a wall sign) · /isp node repair <name> [isp]");
            return;
        }
        if (args.length < 4) {
            Msg.send(p, "<red>Usage: /isp node install <dsl|cable|fiber|satellite> <name> [isp]");
            return;
        }
        Technology tech = Technology.fromKey(args[2]);
        if (tech == null) {
            Msg.send(p, "<red>Unknown technology. Use <white>dsl, cable, fiber</white> or <white>satellite</white>.");
            return;
        }
        Isp isp = myIsp(p, args.length > 4 ? args[4] : null);
        if (isp == null) return;
        Block b = p.getTargetBlockExact(6);
        Node n = bb().installNode(p, isp, tech, args[3], b);
        if (n == null) return;
        Msg.send(p, "<green><t> node <white><n></white> is up for <white><isp></white>. Homes <rng> can sign up.",
                Msg.p("t", bb().cfg().spec(tech).name()), Msg.p("n", n.name()), Msg.p("isp", isp.name()),
                Msg.p("rng", bb().cfg().spec(tech).range() > 0 ? "within " + Math.round(bb().cfg().spec(tech).range()) + " blocks" : "anywhere in the world"));
    }

    private void plan(CommandSender s, String[] args) {
        Player p = player(s);
        if (p == null) return;
        if (args.length < 6 || !args[1].equalsIgnoreCase("add")) {
            Msg.send(p, "<red>Usage: /isp plan add <tech> <price> <mbps> <name...>");
            return;
        }
        Isp isp = myIsp(p, null);
        if (isp == null) return;
        Technology tech = Technology.fromKey(args[2]);
        if (tech == null || !java.util.Arrays.asList(bb().techsFor(isp)).contains(tech)) {
            Msg.send(p, isp.carrierBacked() ? "<red>A carrier's ISP only sells <white>fixed-wireless</white> plans."
                    : "<red>Unknown technology. Use <white>dsl, cable, fiber</white> or <white>satellite</white>.");
            return;
        }
        long price;
        double cap;
        try {
            price = Long.parseLong(args[3]);
            cap = Double.parseDouble(args[4]);
            if (price < 0 || cap < 0.1) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            Msg.send(p, "<red>Price is a whole number of credits (0 or more); the speed cap is Mbps (0.1 or more).");
            return;
        }
        String name = join(args, 5);
        if (name.length() > 24) {
            Msg.send(p, "<red>Plan names are at most 24 characters.");
            return;
        }
        if (isp.plans().size() >= bb().cfg().maxPlansPerIsp()) {
            Msg.send(p, "<red>An ISP can have at most <n> plans.", Msg.p("n", "" + bb().cfg().maxPlansPerIsp()));
            return;
        }
        InternetPlan pl = new InternetPlan(bb().newPlanId(isp, name), name, tech, price, 24, cap, bb().cfg().spec(tech).installFee());
        isp.plans().put(pl.id(), pl);
        bb().store().markDirty();
        Msg.send(p, "<green>Plan <white><n></white> added. Edit its period and install fee in <white>/isp manage</white> → Plans.", Msg.p("n", name));
    }

    private void money(CommandSender s, String[] args, boolean deposit) {
        Player p = player(s);
        if (p == null) return;
        if (args.length < 2) {
            Msg.send(p, "<red>Usage: /isp <cmd> <amount> [isp]", Msg.p("cmd", deposit ? "deposit" : "withdraw"));
            return;
        }
        long amount;
        try {
            amount = Long.parseLong(args[1].replace(",", ""));
        } catch (NumberFormatException e) {
            Msg.send(p, "<red>Enter a whole number of credits.");
            return;
        }
        Isp isp = myIsp(p, args.length > 2 ? args[2] : null);
        if (isp == null) return;
        if (deposit) bb().deposit(p, isp, amount);
        else bb().withdraw(p, isp, amount);
    }

    private void employee(CommandSender s, String[] args) {
        if (args.length < 4) {
            Msg.send(s, "<red>Usage: /isp employee add|remove <isp> <player>");
            return;
        }
        Isp isp = ispArg(s, args[2]);
        if (isp == null) return;
        if (s instanceof Player p && !p.hasPermission("mccomm.admin") && !isp.owner().equals(p.getUniqueId())) {
            Msg.send(s, "<red>Only the owner can change staff.");
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(args[3]);
        if (target == null) {
            Msg.send(s, "<red>Unknown player <white><p></white>.", Msg.p("p", args[3]));
            return;
        }
        if (args[1].equalsIgnoreCase("add")) {
            if (target.getUniqueId().equals(isp.owner()) || !isp.employees().add(target.getUniqueId())) {
                Msg.send(s, "<gray>They already work for <n>.", Msg.p("n", isp.name()));
                return;
            }
            Msg.send(s, "<green>Added <white><p></white> to <n>.", Msg.p("p", args[3]), Msg.p("n", isp.name()));
        } else if (args[1].equalsIgnoreCase("remove")) {
            if (!isp.employees().remove(target.getUniqueId())) {
                Msg.send(s, "<gray>They don't work for <n>.", Msg.p("n", isp.name()));
                return;
            }
            Msg.send(s, "<yellow>Removed <white><p></white> from <n>.", Msg.p("p", args[3]), Msg.p("n", isp.name()));
        } else {
            Msg.send(s, "<red>Usage: /isp employee add|remove <isp> <player>");
            return;
        }
        bb().store().markDirty();
    }

    private void color(CommandSender s, String[] args) {
        if (args.length < 3) {
            Msg.send(s, "<red>Usage: /isp color <isp> <color> [color...] <gray>(2-4 colours = gradient, or a preset like sunset)");
            return;
        }
        Isp isp = ispArg(s, args[1]);
        if (isp == null) return;
        if (s instanceof Player p && !bb().canManage(p, isp)) {
            Msg.send(s, "<red>You don't work for <white><n></white>.", Msg.p("n", isp.name()));
            return;
        }
        List<TextColor> style = dev.mccomm.cell.util.Style.parse(join(args, 2));
        if (style == null) {
            Msg.send(s, "<red>Unknown style. Use colour names or hex codes, 1 for solid or 2-4 for a gradient, or a preset like <white>sunset</white>.");
            return;
        }
        isp.colors(style);
        bb().store().markDirty();
        bb().refreshAll();
        Msg.send(s, "<green>Style updated: " + isp.styled());
    }

    private static String name(UUID id) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(id);
        return op.getName() == null ? id.toString().substring(0, 8) : op.getName();
    }

    @Override
    public Collection<String> suggest(CommandSourceStack src, String[] args) {
        if (args.length <= 1) return filter(SUBS, args.length == 0 ? "" : args[0]);
        String sub = args[0].toLowerCase(Locale.ROOT);
        List<String> techs = new ArrayList<>();
        for (Technology t : Technology.values()) techs.add(t.key());
        switch (sub) {
            case "node" -> {
                if (args.length == 2) return filter(List.of("install", "repair"), args[1]);
                if (args.length == 3) return filter(techs, args[2]);
                if (args.length == 5) return filter(ispNames(), args[4]);
            }
            case "plan" -> {
                if (args.length == 2) return filter(List.of("add"), args[1]);
                if (args.length == 3) return filter(techs, args[2]);
            }
            case "employee" -> {
                if (args.length == 2) return filter(List.of("add", "remove"), args[1]);
                if (args.length == 3) return filter(ispNames(), args[2]);
                if (args.length == 4) return filter(onlineNames(), args[3]);
            }
            case "create" -> {
                if (args.length == 3) return filter(List.of("carrier"), args[2]);
                if (args.length == 4 && args[2].equalsIgnoreCase("carrier")) return filter(carrierNames(), args[3]);
            }
            case "peer" -> {
                if (args.length == 2) return filter(List.of("offer", "accept", "end"), args[1]);
                if (args.length == 3 || args.length == 4) return filter(ispNames(), args[args.length - 1]);
            }
            case "bundle" -> {
                if (args.length == 2) return filter(List.of("offer", "accept", "end", "list"), args[1]);
                if (args.length == 3 && args[1].equalsIgnoreCase("accept")) return filter(ispNames(), args[2]);
                if (args.length == 3) return filter(carrierNames(), args[2]);
            }
            case "delete" -> {
                if (args.length == 2) return filter(ispNames(), args[1]);
                if (args.length == 3) return filter(List.of("confirm"), args[2]);
            }
            case "info", "manage", "color" -> {
                if (args.length == 2) return filter(ispNames(), args[1]);
                if (args.length >= 3 && sub.equals("color")) {
                    List<String> opts = new ArrayList<>(NamedTextColor.NAMES.keys());
                    if (args.length == 3) for (String g : dev.mccomm.cell.util.Style.GRADIENTS.keySet()) opts.add(g.toLowerCase(Locale.ROOT));
                    return filter(opts, args[args.length - 1]);
                }
            }
            case "deposit", "withdraw" -> {
                if (args.length == 3) return filter(ispNames(), args[2]);
            }
            default -> {
            }
        }
        return List.of();
    }
}
