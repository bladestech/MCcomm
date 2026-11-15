package dev.mccomm.cell.command;

import dev.mccomm.cell.CellPlugin;
import dev.mccomm.cell.util.Msg;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

/** /text, /call and /celladmin. */
public final class CommCommands {
    private CommCommands() {
    }

    /** Contact names plus online player names, for tab completion. */
    private static List<String> targets(CommandSourceStack src) {
        List<String> out = new java.util.ArrayList<>(Cmd.onlineNames());
        if (src.getSender() instanceof Player p && org.bukkit.Bukkit.getPluginManager().getPlugin("MCcomm") instanceof CellPlugin cp) {
            for (String name : cp.store().profile(p.getUniqueId()).contacts().values()) {
                if (!name.contains(" ")) out.add(name);
            }
        }
        return out;
    }

    public static final class Text extends Cmd {
        public Text(CellPlugin plugin) {
            super(plugin);
        }

        @Override
        public String permission() {
            return "mccomm.use";
        }

        @Override
        public void execute(CommandSourceStack src, String[] args) {
            Player p = player(src.getSender());
            if (p == null) return;
            if (args.length < 2) {
                Msg.send(p, "<red>Usage: /text <number|contact|player> <message>");
                return;
            }
            String n = plugin.sims().resolve(p, args[0]);
            if (n == null) {
                Msg.send(p, "<red>No number, contact or online player called <white><x></white>.", Msg.p("x", args[0]));
                return;
            }
            plugin.sms().send(p, n, join(args, 1));
        }

        @Override
        public Collection<String> suggest(CommandSourceStack src, String[] args) {
            return args.length <= 1 ? filter(targets(src), args.length == 0 ? "" : args[0]) : List.of();
        }
    }

    public static final class Internet extends Cmd {
        public Internet(CellPlugin plugin) {
            super(plugin);
        }

        @Override
        public String permission() {
            return "mccomm.use";
        }

        @Override
        public void execute(CommandSourceStack src, String[] args) {
            Player p = player(src.getSender());
            if (p == null) return;
            if (args.length == 0) plugin.internet().toggle(p);
            else plugin.internet().send(p, join(args, 0));
        }
    }

    public static final class Call extends Cmd {
        public Call(CellPlugin plugin) {
            super(plugin);
        }

        @Override
        public String permission() {
            return "mccomm.use";
        }

        @Override
        public void execute(CommandSourceStack src, String[] args) {
            Player p = player(src.getSender());
            if (p == null) return;
            String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "start" -> {
                    if (args.length < 2) {
                        Msg.send(p, "<red>Usage: /call start <number|contact|player|<n>>", Msg.p("n", plugin.settings().emergencyNumber));
                        return;
                    }
                    String target = join(args, 1);
                    if (target.equals(plugin.settings().emergencyNumber)) {
                        plugin.calls().dialEmergency(p);
                        return;
                    }
                    String n = plugin.sims().resolve(p, target);
                    if (n == null) {
                        Msg.send(p, "<red>No number, contact or online player called <white><x></white>.", Msg.p("x", target));
                        return;
                    }
                    plugin.calls().start(p, n);
                }
                case "accept" -> plugin.calls().accept(p);
                case "deny", "decline" -> plugin.calls().deny(p);
                case "end", "hangup" -> plugin.calls().end(p, "<gray>Call ended.");
                default -> Msg.send(p, "<red>Usage: /call start <player> | accept | deny | end");
            }
        }

        @Override
        public Collection<String> suggest(CommandSourceStack src, String[] args) {
            if (args.length <= 1) return filter(List.of("start", "accept", "deny", "end"), args.length == 0 ? "" : args[0]);
            if (args.length == 2 && args[0].equalsIgnoreCase("start")) {
                List<String> opts = new java.util.ArrayList<>(targets(src));
                opts.add(plugin.settings().emergencyNumber);
                return filter(opts, args[1]);
            }
            return List.of();
        }
    }

    private static Long parse(String s) {
        try {
            return Long.parseLong(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static final class Sim extends Cmd {
        public Sim(CellPlugin plugin) {
            super(plugin);
        }

        @Override
        public String permission() {
            return "mccomm.simulate";
        }

        @Override
        public void execute(CommandSourceStack src, String[] args) {
            Player p = player(src.getSender());
            if (p != null) plugin.simulationMenu().open(p);
        }
    }

    public static final class Admin extends Cmd {
        public Admin(CellPlugin plugin) {
            super(plugin);
        }

        @Override
        public String permission() {
            return "mccomm.admin";
        }

        @Override
        public void execute(CommandSourceStack src, String[] args) {
            CommandSender s = src.getSender();
            String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "reload" -> {
                    plugin.reloadSettings();
                    Msg.send(s, "<green>Config reloaded and all towers refreshed.");
                }
                case "save" -> {
                    plugin.store().save();
                    Msg.send(s, "<green>Data saved.");
                }
                case "credits" -> {
                    // /celladmin credits <player> <amount>   (negative to take)
                    if (args.length < 3) {
                        Msg.send(s, "<red>Usage: /celladmin credits <player> <amount>");
                        return;
                    }
                    org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(args[1]);
                    Long amt = parse(args[2]);
                    if (op == null || amt == null) {
                        Msg.send(s, "<red>Unknown player or bad amount.");
                        return;
                    }
                    var prof = plugin.store().profile(op.getUniqueId());
                    prof.balance(Math.max(0, prof.balance() + amt));
                    prof.log(amt, "Admin adjustment");
                    plugin.store().markDirty();
                    if (amt > 0) plugin.billing().payOverdue(op.getUniqueId());
                    Msg.send(s, "<green><p>'s balance is now <white><b></white>.", Msg.p("p", args[1]),
                            Msg.p("b", plugin.settings().money(prof.balance())));
                }
                case "carriercredits" -> {
                    if (args.length < 3) {
                        Msg.send(s, "<red>Usage: /celladmin carriercredits <carrier> <amount>");
                        return;
                    }
                    var c = plugin.store().carrier(args[1]);
                    Long amt = parse(args[2]);
                    if (c == null || amt == null) {
                        Msg.send(s, "<red>Unknown carrier or bad amount.");
                        return;
                    }
                    if (amt > 0 && s instanceof Player p) {
                        plugin.billing().depositCarrier(p, c, amt);
                    } else {
                        c.balance(Math.max(0, c.balance() + amt));
                        c.log(amt, "Admin adjustment");
                        plugin.store().markDirty();
                        Msg.send(s, "<green><c>'s balance is now <white><b></white>.", Msg.p("c", c.name()),
                                Msg.p("b", plugin.settings().money(c.balance())));
                    }
                }
                case "givephone" -> {
                    if (args.length < 3) {
                        Msg.send(s, "<red>Usage: /celladmin givephone <player> <model>");
                        return;
                    }
                    Player target = Bukkit.getPlayerExact(args[1]);
                    var model = plugin.settings().phone(args[2].toLowerCase(Locale.ROOT));
                    if (target == null || model == null) {
                        Msg.send(s, "<red>Player must be online; models: <white><m>", Msg.p("m", String.join(", ", plugin.settings().phones.keySet())));
                        return;
                    }
                    var tp = plugin.store().profile(target.getUniqueId());
                    if (tp.ownedPhones().add(model.id())) tp.giftedPhones().add(model.id());
                    plugin.billing().usePhone(target, model);
                    Msg.send(s, "<green>Gave <white><m></white> to <white><p></white>.", Msg.p("m", model.name()), Msg.p("p", target.getName()));
                }
                case "timewarp" -> {
                    double days;
                    try {
                        days = Double.parseDouble(args.length > 1 ? args[1] : "");
                    } catch (NumberFormatException e) {
                        Msg.send(s, "<red>Usage: /celladmin timewarp <days>");
                        return;
                    }
                    if (days <= 0 || days > 3650) {
                        Msg.send(s, "<red>Days must be between 0 and 3650.");
                        return;
                    }
                    int ticks = plugin.billing().timewarp(Math.round(days * 86_400_000L), 3_600_000L);
                    plugin.store().save();
                    plugin.towers().refreshAll();
                    Msg.send(s, "<green>Fast-forwarded the economy <white><d></white> days (<white><t></white> hourly steps).",
                            Msg.p("d", "" + days), Msg.p("t", "" + ticks));
                }
                case "billing" -> {
                    plugin.billing().tick();
                    Msg.send(s, "<green>Billing check ran.");
                }
                default -> Msg.send(s, "<red>Usage: /celladmin reload | save | credits | carriercredits | givephone | billing | timewarp");
            }
        }

        @Override
        public Collection<String> suggest(CommandSourceStack src, String[] args) {
            if (args.length <= 1) return filter(List.of("reload", "save", "credits", "carriercredits", "givephone", "billing", "timewarp"), args.length == 0 ? "" : args[0]);
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (args.length == 2 && (sub.equals("credits") || sub.equals("givephone"))) return filter(onlineNames(), args[1]);
            if (args.length == 2 && sub.equals("carriercredits")) return filter(carrierNames(), args[1]);
            if (args.length == 3 && sub.equals("givephone")) return filter(plugin.settings().phones.keySet(), args[2]);
            return List.of();
        }
    }
}
