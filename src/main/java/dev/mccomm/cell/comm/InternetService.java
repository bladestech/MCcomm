package dev.mccomm.cell.comm;

import dev.mccomm.cell.CellPlugin;
import dev.mccomm.cell.Settings;
import dev.mccomm.cell.economy.BillingService;
import dev.mccomm.cell.economy.PhoneModel;
import dev.mccomm.cell.model.Carrier;
import dev.mccomm.cell.model.PlayerProfile;
import dev.mccomm.cell.signal.SignalService;
import dev.mccomm.cell.util.Msg;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /internet: a second chat for players with mobile data, separate from public chat.
 * The sender pays data per character, and so does every receiver.
 */
public final class InternetService implements Listener {
    private final CellPlugin plugin;
    private final Set<UUID> mode = ConcurrentHashMap.newKeySet();

    public InternetService(CellPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean inMode(Player p) {
        return mode.contains(p.getUniqueId());
    }

    public void toggle(Player p) {
        if (mode.remove(p.getUniqueId())) {
            Msg.send(p, "<gray>Internet mode <red>off</red>. Your chat goes to the public server chat again.");
        } else {
            mode.add(p.getUniqueId());
            Msg.send(p, "<gray>Internet mode <green>on</green>. Your chat now goes to the internet (costs data). <white>/internet</white> to switch back.");
        }
    }

    /** Sends a message to the internet channel. Must run on the main thread. */
    public void send(Player from, String text) {
        if (text.length() > plugin.settings().internetMaxLength) text = text.substring(0, plugin.settings().internetMaxLength);
        SignalService.Status st = plugin.signal().status(from);
        SignalService.SimStatus sim = st.primary();
        if (sim == null) {
            Msg.send(from, st.phone() == null ? "<red>You don't have a phone." : "<red>No data connection. Your message wasn't sent.");
            return;
        }
        PlayerProfile prof = plugin.store().profile(from.getUniqueId());
        double cost = text.length() * plugin.settings().mbPerCharSend;
        if (plugin.billing().dataBlocked(prof, sim.carrier().id(), cost)) {
            Msg.send(from, "<red>Not enough data to send that (<white><mb></white>). Buy a data pack in your wallet.",
                    Msg.p("mb", Settings.data(cost)));
            return;
        }
        plugin.billing().useData(from.getUniqueId(), sim.carrier().id(), cost);

        Component line = format(from, sim, st.phone(), text);
        long delayTicks = Math.max(1, Math.round(plugin.signal().pingMs(sim.link()) / 50.0));
        if (sim.throttled()) delayTicks += 40; // a throttled connection crawls
        final String msg = text;
        UUID senderId = from.getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> deliver(senderId, line, msg), delayTicks);
    }

    private void deliver(UUID senderId, Component line, String text) {
        double cost = text.length() * plugin.settings().mbPerCharReceive;
        for (Player to : Bukkit.getOnlinePlayers()) {
            if (to.getUniqueId().equals(senderId)) {
                to.sendMessage(line);
                continue;
            }
            SignalService.SimStatus sim = plugin.signal().status(to).primary();
            if (sim == null) continue; // offline from the internet: they miss it
            if (plugin.billing().useData(to.getUniqueId(), sim.carrier().id(), cost) == BillingService.DataResult.BLOCKED) continue;
            to.sendMessage(line);
        }
    }

    /** [NET] name Carrier P+: message (hover the name for the phone and band). */
    private Component format(Player from, SignalService.SimStatus sim, PhoneModel phone, String text) {
        Carrier c = sim.carrier();
        String model = phone == null ? "?" : phone.name();
        String tag = model.isEmpty() ? "?" : model.substring(0, 1).toUpperCase() + (model.endsWith("+") ? "+" : "");
        Component hover = Msg.mm("<white><n>\n<gray>Phone: <white><ph>\n<gray>Carrier: " + c.styled() + "\n<gray>Band: <white><b>",
                Msg.p("n", from.getName()), Msg.p("ph", model), Msg.p("c", c.name()),
                Msg.p("b", plugin.settings().spec(sim.link().band()).name()));
        return Msg.mm("<dark_aqua>[NET]</dark_aqua> <white><hoverable></white> " + c.styled() + " <dark_gray><tag></dark_gray><gray>: <white><m>",
                Msg.c("hoverable", Component.text(from.getName()).hoverEvent(HoverEvent.showText(hover))),
                Msg.p("c", c.name()), Msg.p("tag", tag), Msg.p("m", text));
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        if (!mode.contains(p.getUniqueId())) return;
        e.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(e.message());
        Bukkit.getScheduler().runTask(plugin, () -> send(p, text));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        mode.remove(e.getPlayer().getUniqueId());
    }
}
