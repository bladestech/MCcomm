package dev.mccomm.cell.command;

import dev.mccomm.cell.CellPlugin;
import dev.mccomm.cell.model.Carrier;
import dev.mccomm.cell.util.Msg;
import io.papermc.paper.command.brigadier.BasicCommand;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/** Shared helpers for the plugin's commands. */
abstract class Cmd implements BasicCommand {
    protected final CellPlugin plugin;

    Cmd(CellPlugin plugin) {
        this.plugin = plugin;
    }

    protected Player player(CommandSender s) {
        if (s instanceof Player p) return p;
        Msg.send(s, "<red>Only players can use this.");
        return null;
    }

    protected static List<String> filter(Collection<String> options, String prefix) {
        String pre = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) if (o.toLowerCase(Locale.ROOT).startsWith(pre)) out.add(o);
        return out;
    }

    protected List<String> carrierNames() {
        List<String> out = new ArrayList<>();
        for (Carrier c : plugin.store().carriers()) out.add(c.name());
        return out;
    }

    protected static List<String> onlineNames() {
        List<String> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        return out;
    }

    protected Carrier carrierArg(CommandSender s, String name) {
        Carrier c = plugin.store().carrier(name);
        if (c == null) Msg.send(s, "<red>No carrier named <white><n></white>.", Msg.p("n", name));
        return c;
    }

    protected static String join(String[] args, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < args.length; i++) {
            if (i > from) sb.append(' ');
            sb.append(args[i]);
        }
        return sb.toString();
    }
}
