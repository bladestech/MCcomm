package dev.mccomm.cell.comm;

import dev.mccomm.cell.CellPlugin;
import dev.mccomm.cell.model.PlayerProfile;
import dev.mccomm.cell.model.SmsMessage;
import dev.mccomm.cell.signal.SignalService;
import dev.mccomm.cell.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Texts between phone numbers. The sender needs service. If the recipient's SIM can't be reached
 * the text waits (store and forward) until it can, or until it expires.
 */
public final class SmsService {
    private record Pending(UUID sender, String fromNumber, String fromName, String toNumber, String text, long expiresAt) {
    }

    private final CellPlugin plugin;
    private final List<Pending> pending = new ArrayList<>();

    public SmsService(CellPlugin plugin) {
        this.plugin = plugin;
    }

    public void send(Player from, String toNumber, String text) {
        SignalService.Status st = plugin.signal().status(from);
        SignalService.SimStatus sim = st.outgoing(false);
        if (sim == null) {
            if (st.phone() == null) Msg.send(from, "<red>You don't have a phone. Get one in <white>/cell eshop</white>.");
            else if (st.hasService()) Msg.send(from, "<red>None of your plans include texts.");
            else Msg.send(from, st.emergency()
                    ? "<red>No Service</red> <gray>· Emergency calls only. You can't send texts."
                    : "<red>No signal. Your text could not be sent.");
            return;
        }
        String fromNumber = plugin.store().profile(from.getUniqueId()).number(sim.carrier().id());
        if (plugin.store().numberOwner(toNumber) == null) {
            Msg.send(from, "<red>The number <white><n></white> is not in service.", Msg.p("n", Numbers.format(toNumber)));
            return;
        }
        if (plugin.store().numberOwner(toNumber).player().equals(from.getUniqueId())) {
            Msg.send(from, "<red>You can't text yourself.");
            return;
        }
        int delay = sim.link() == null ? 40 : plugin.settings().spec(sim.link().band()).smsDelayTicks();
        String shown = plugin.sims().displayName(from, toNumber);
        Msg.send(from, "<gray>Sending to <white><to></white>…", Msg.p("to", shown));
        UUID senderId = from.getUniqueId();
        String fromName = from.getName();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player sender = Bukkit.getPlayer(senderId);
            if (tryDeliver(fromNumber, fromName, toNumber, text)) {
                if (sender != null) Msg.raw(sender, "<dark_gray>[SMS → <to>] <gray>Delivered: <white><msg>",
                        Msg.p("to", shown), Msg.p("msg", text));
            } else {
                long exp = System.currentTimeMillis() + plugin.settings().smsExpireMinutes * 60_000L;
                pending.add(new Pending(senderId, fromNumber, fromName, toNumber, text, exp));
                if (sender != null) Msg.raw(sender, "<dark_gray>[SMS → <to>] <yellow>Pending</yellow> <gray>— they can't be reached right now. It will be delivered when they can.",
                        Msg.p("to", shown));
            }
        }, delay);
    }

    private boolean tryDeliver(String fromNumber, String fromName, String toNumber, String text) {
        Player to = plugin.sims().reachablePlayer(toNumber);
        if (to == null) return false;
        SignalService.SimStatus rs = plugin.sims().simFor(to, toNumber);
        if (rs == null || !rs.texts()) return false;
        PlayerProfile prof = plugin.store().profile(to.getUniqueId());
        prof.inbox().addFirst(new SmsMessage(fromNumber, fromName, toNumber, text, System.currentTimeMillis(), false));
        while (prof.inbox().size() > plugin.settings().inboxMax) prof.inbox().removeLast();
        plugin.store().markDirty();

        String who = plugin.sims().displayName(to, fromNumber);
        if (!prof.dnd()) to.playSound(to.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, 1.8f);
        Msg.raw(to, "<aqua>[SMS] <white><from></white><gray>: <white><msg> <dark_gray><click:suggest_command:'/text <num> '>[Reply]</click>",
                Msg.p("from", who), Msg.p("msg", text), Msg.p("num", fromNumber));
        return true;
    }

    public void onSignalUpdate() {
        if (pending.isEmpty()) return;
        long now = System.currentTimeMillis();
        Iterator<Pending> it = pending.iterator();
        while (it.hasNext()) {
            Pending p = it.next();
            if (p.expiresAt() < now) {
                it.remove();
                Player s = Bukkit.getPlayer(p.sender());
                if (s != null) Msg.raw(s, "<dark_gray>[SMS] <red>Your text to <n> expired undelivered.",
                        Msg.p("n", plugin.sims().displayName(s, p.toNumber())));
                continue;
            }
            if (tryDeliver(p.fromNumber(), p.fromName(), p.toNumber(), p.text())) it.remove();
        }
    }
}
