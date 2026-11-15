package dev.mccomm.cell.command;

import dev.mccomm.cell.CellPlugin;
import dev.mccomm.cell.model.Carrier;
import dev.mccomm.cell.model.PlayerProfile;
import dev.mccomm.cell.model.Tower;
import dev.mccomm.cell.signal.SignalService;
import dev.mccomm.cell.util.Msg;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** /cell — opens the phone; subcommands are shortcuts (status, speedtest, SIMs, numbers, towers, HUD). */
public final class CellCommand extends Cmd {
    private static final List<String> SUBS = List.of("status", "eshop", "wallet", "plan", "speedtest", "subscribe", "unsubscribe", "activate",
            "deactivate", "data", "number", "contacts", "messages", "sims", "towers", "carriers", "hud", "help");

    public CellCommand(CellPlugin plugin) {
        super(plugin);
    }

    @Override
    public String permission() {
        return "celltowers.use";
    }

    @Override
    public void execute(CommandSourceStack src, String[] args) {
        CommandSender s = src.getSender();
        if (args.length == 0) {
            Player p = player(s);
            if (p != null) plugin.phone().open(p);
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("carriers")) {
            listCarriers(s);
            return;
        }
        if (sub.equals("help")) {
            help(s);
            return;
        }
        Player p = player(s);
        if (p == null) return;
        PlayerProfile prof = plugin.store().profile(p.getUniqueId());
        switch (sub) {
            case "speedtest" -> {
                int slot = 0;
                if (args.length > 1) {
                    try {
                        slot = Integer.parseInt(args[1]);
                    } catch (NumberFormatException e) {
                        Msg.send(p, "<red>Usage: /cell speedtest [sim slot]");
                        return;
                    }
                }
                plugin.speedTest().start(p, slot);
            }
            case "subscribe", "unsubscribe", "activate", "deactivate" -> {
                if (args.length < 2) {
                    Msg.send(p, "<red>Usage: /cell <sub> <carrier>", Msg.p("sub", sub));
                    return;
                }
                Carrier c = carrierArg(p, args[1]);
                if (c == null) return;
                switch (sub) {
                    case "subscribe" -> plugin.phone().plans(p, c);
                    case "unsubscribe" -> plugin.sims().unsubscribe(p, c);
                    case "activate" -> plugin.sims().activate(p, c);
                    default -> plugin.sims().deactivate(p, c);
                }
            }
            case "data" -> {
                if (args.length < 2) {
                    Msg.send(p, "<red>Usage: /cell data <carrier|auto>");
                    return;
                }
                if (args[1].equalsIgnoreCase("auto")) {
                    plugin.sims().setDataSim(p, null);
                    return;
                }
                Carrier c = carrierArg(p, args[1]);
                if (c != null) plugin.sims().setDataSim(p, c);
            }
            case "status" -> status(p);
            case "eshop", "shop" -> plugin.phone().eshop(p);
            case "wallet", "balance" -> plugin.phone().wallet(p);
            case "plan", "plans" -> {
                if (args.length < 2) {
                    plugin.phone().wallet(p);
                    return;
                }
                Carrier c = carrierArg(p, args[1]);
                if (c != null) plugin.phone().plans(p, c);
            }
            case "number", "numbers" -> {
                if (prof.numbers().isEmpty()) {
                    Msg.send(p, "<gray>You have no phone numbers. Subscribe to a carrier first.");
                    return;
                }
                for (var e : prof.numbers().entrySet()) {
                    Carrier c = plugin.store().carrier(e.getKey());
                    Msg.send(p, "<gray><c>: <white><n></white> <dark_gray>(<state>)", Msg.p("c", c == null ? e.getKey() : c.name()),
                            Msg.p("n", dev.mccomm.cell.comm.Numbers.format(e.getValue())),
                            Msg.p("state", prof.active().contains(e.getKey()) ? "active" : "inactive"));
                }
            }
            case "contacts" -> plugin.phone().contacts(p);
            case "messages", "inbox" -> plugin.phone().messages(p);
            case "sims" -> sims(p, prof);
            case "towers" -> towers(p);
            case "hud" -> {
                boolean now = plugin.hud().enabled(p);
                prof.hud(!now);
                plugin.store().markDirty();
                if (now) {
                    plugin.hud().clear(p);
                    p.sendActionBar(net.kyori.adventure.text.Component.empty());
                } else {
                    plugin.hud().update(p, plugin.signal().compute(p));
                }
                Msg.send(p, "<gray>Signal HUD " + (!now ? "<green>enabled" : "<red>disabled") + "<gray>.");
            }
            default -> help(p);
        }
    }

    private void help(CommandSender s) {
        Msg.raw(s, "<dark_gray>━━━ <aqua>/cell</aqua> ━━━");
        Msg.raw(s, "<white>/cell <gray>— open your phone");
        Msg.raw(s, "<white>/cell status <gray>— your connection in chat");
        Msg.raw(s, "<white>/cell eshop <gray>— buy phones · <white>/cell wallet <gray>— balance, plans");
        Msg.raw(s, "<white>/cell plan <carrier> <gray>— pick or change a plan");
        Msg.raw(s, "<white>/cell number <gray>— your phone numbers");
        Msg.raw(s, "<white>/cell contacts <gray>· <white>/cell messages");
        Msg.raw(s, "<white>/cell speedtest [slot] <gray>— test your speed");
        Msg.raw(s, "<white>/cell subscribe|unsubscribe <carrier>");
        Msg.raw(s, "<white>/cell activate|deactivate <carrier> <gray>— swap SIMs");
        Msg.raw(s, "<white>/cell data <carrier|auto> <gray>— choose your data SIM");
        Msg.raw(s, "<white>/cell sims <gray>— list your SIMs");
        Msg.raw(s, "<white>/cell towers <gray>— nearby towers");
        Msg.raw(s, "<white>/cell carriers <gray>— all carriers");
        Msg.raw(s, "<white>/cell hud <gray>— toggle the signal sidebar");
        Msg.raw(s, "<white>/text <number|contact|player> <msg> <gray>· <white>/call start <number|contact|player>|accept|deny|end");
    }

    private void status(Player p) {
        SignalService.Status st = plugin.signal().compute(p);
        Msg.raw(p, "<dark_gray>━━━ <aqua>Cell status</aqua> ━━━");
        if (st.phone() == null) {
            Msg.raw(p, "<red>No phone.</red> <gray>Buy one with <white>/cell eshop</white>.");
            return;
        }
        Msg.raw(p, "<gray>Phone: <white><ph></white> <dark_gray>(up to <b2>)", Msg.p("ph", st.phone().name()),
                Msg.p("b2", plugin.settings().spec(st.phone().maxBand()).name()));
        if (st.sims().isEmpty()) {
            if (st.emergency()) {
                Msg.raw(p, "<red>No Service</red> <gray>— Emergency calls only (<white><n></white>)", Msg.p("n", plugin.settings().emergencyNumber));
                Msg.raw(p, "<gray>Subscribe with <white>/cell subscribe <carrier></white>. See <white>/cell carriers</white>.");
            } else {
                Msg.raw(p, "<dark_gray>No Signal");
            }
            return;
        }
        for (SignalService.SimStatus sim : st.sims()) {
            Carrier c = sim.carrier();
            String head = "<gray>SIM <n> · <" + c.color().asHexString() + "><c></" + c.color().asHexString() + "> "
                    + (st.sims().size() > 1 && st.isDataSim(sim) ? "<dark_gray>[<aqua>Data</aqua>]</dark_gray> " : "");
            SignalService.Link l = sim.link();
            if (l == null) {
                Msg.raw(p, head + (sim.issue() != null ? "<red>" + net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(sim.issue()) + "</red>"
                                : st.emergency() ? "<red>No Service</red> <gray>— Emergency calls only" : "<dark_gray>No Signal"),
                        Msg.p("n", "" + sim.slot()), Msg.p("c", c.name()));
                continue;
            }
            Msg.raw(p, head + Msg.bars(l.bars()) + " <white><band>",
                    Msg.p("n", "" + sim.slot()), Msg.p("c", c.name()), Msg.p("band", plugin.settings().spec(l.band()).name()));
            Msg.raw(p, "  <gray>Tower <white><id></white> <dark_gray>(<model>)</dark_gray> · <white><dist></white> · <white><dbm> dBm",
                    Msg.p("id", l.tower().name()),
                    Msg.p("model", plugin.settings().generation(l.tower().generation()).model()),
                    Msg.p("dist", Msg.distance(l.distance())),
                    Msg.p("dbm", String.format(Locale.ROOT, "%.0f", l.dbm())));
        }
    }

    private void sims(Player p, PlayerProfile prof) {
        Msg.raw(p, "<dark_gray>━━━ <aqua>Your SIMs</aqua> ━━━");
        int i = 1;
        for (String id : prof.active()) {
            Carrier c = plugin.store().carrier(id);
            Msg.raw(p, "<green>● <gray>SIM <n>: <white><c>", Msg.p("n", "" + i++), Msg.p("c", c == null ? id : c.name()));
        }
        for (int j = prof.active().size(); j < plugin.settings().maxActive; j++) {
            Msg.raw(p, "<dark_gray>○ SIM <n>: empty", Msg.p("n", "" + (j + 1)));
        }
        if (!prof.inactive().isEmpty()) {
            List<String> names = new ArrayList<>();
            for (String id : prof.inactive()) {
                Carrier c = plugin.store().carrier(id);
                names.add(c == null ? id : c.name());
            }
            Msg.raw(p, "<gray>Inactive (<n>/<max>): <white><list>", Msg.p("n", "" + names.size()),
                    Msg.p("max", "" + plugin.settings().maxInactive), Msg.p("list", String.join(", ", names)));
        }
    }

    private void towers(Player p) {
        Location eye = p.getEyeLocation();
        List<Tower> near = new ArrayList<>();
        for (Tower t : plugin.store().towers()) {
            if (!t.worldId().equals(p.getWorld().getUID())) continue;
            Location a = t.antenna(plugin.settings().antennaHeight);
            if (a != null && a.distanceSquared(eye) <= 4000 * 4000) near.add(t);
        }
        near.sort(Comparator.comparingDouble(t -> t.antenna(plugin.settings().antennaHeight).distanceSquared(eye)));
        Msg.raw(p, "<dark_gray>━━━ <aqua>Nearby towers</aqua> ━━━");
        if (near.isEmpty()) {
            Msg.raw(p, "<gray>No towers within 4 km.");
            return;
        }
        for (Tower t : near.subList(0, Math.min(8, near.size()))) {
            Carrier c = plugin.store().carrier(t.carrierId());
            double d = t.antenna(plugin.settings().antennaHeight).distance(eye);
            Msg.raw(p, "<gray><id> <white><c></white> · <dist> · " + (t.online() ? "<green>online" : "<red>offline"),
                    Msg.p("id", t.name()), Msg.p("c", c == null ? "?" : c.name()), Msg.p("dist", Msg.distance(d)));
        }
    }

    private void listCarriers(CommandSender s) {
        Msg.raw(s, "<dark_gray>━━━ <aqua>Carriers</aqua> ━━━");
        if (plugin.store().carriers().isEmpty()) {
            Msg.raw(s, "<gray>No carriers yet. Start one with <white>/carrier create <name>");
            return;
        }
        for (Carrier c : plugin.store().carriers()) {
            Msg.raw(s, "<" + c.color().asHexString() + "><c></" + c.color().asHexString() + "> <gray>· <n> towers",
                    Msg.p("c", c.name()), Msg.p("n", "" + plugin.store().towersOf(c.id()).size()));
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack src, String[] args) {
        if (args.length <= 1) return filter(SUBS, args.length == 0 ? "" : args[0]);
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && src.getSender() instanceof Player p) {
            PlayerProfile prof = plugin.store().profile(p.getUniqueId());
            List<String> opts = switch (sub) {
                case "subscribe", "plan" -> carrierNames();
                case "unsubscribe" -> names(prof.active(), prof.inactive());
                case "activate" -> names(prof.inactive());
                case "deactivate" -> names(prof.active());
                case "data" -> { List<String> l = new ArrayList<>(names(prof.active())); l.add("auto"); yield l; }
                case "speedtest" -> List.of("1", "2");
                default -> List.of();
            };
            return filter(opts, args[1]);
        }
        return List.of();
    }

    @SafeVarargs
    private List<String> names(List<String>... lists) {
        List<String> out = new ArrayList<>();
        for (List<String> l : lists) {
            for (String id : l) {
                Carrier c = plugin.store().carrier(id);
                if (c != null) out.add(c.name());
            }
        }
        return out;
    }
}
