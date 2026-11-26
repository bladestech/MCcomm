package dev.mccomm.cell.comm;

import dev.mccomm.cell.CellPlugin;
import dev.mccomm.cell.signal.SignalService;
import dev.mccomm.cell.util.Msg;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/** /call: a private two-way chat that needs both players to have service, and drops when either loses it. */
public final class CallService implements Listener {
    private record Ring(UUID caller, String callerNumber, String targetNumber, long expiresAt) {
    }

    private final CellPlugin plugin;
    private final Map<UUID, UUID> inCall = new ConcurrentHashMap<>();
    private final Map<UUID, Ring> ringing = new ConcurrentHashMap<>(); // target -> ring
    private final Map<UUID, String> partnerNumber = new ConcurrentHashMap<>(); // player -> other side's number

    public CallService(CellPlugin plugin) {
        this.plugin = plugin;
    }

    /** Who this player is talking to, as they see it (contact name or number), or null. */
    public String partnerName(Player p) {
        String n = partnerNumber.get(p.getUniqueId());
        return n == null ? null : plugin.sims().displayName(p, n);
    }

    /** Who is calling this player, as they see it, or null if nothing is ringing. */
    public String incoming(Player p) {
        Ring r = ringing.get(p.getUniqueId());
        if (r == null || r.expiresAt() < System.currentTimeMillis()) return null;
        return plugin.sims().displayName(p, r.callerNumber());
    }

    public boolean ringingOut(Player p) {
        for (Ring r : ringing.values()) if (r.caller().equals(p.getUniqueId())) return true;
        return false;
    }

    public boolean inCall(Player p) {
        return inCall.containsKey(p.getUniqueId());
    }

    public void dialEmergency(Player p) {
        SignalService.Status st = plugin.signal().status(p);
        if (st.phone() == null) {
            Msg.send(p, "<red>You don't have a phone.");
            return;
        }
        if (!st.emergency()) {
            Msg.send(p, "<red>No signal. The call could not be placed.");
            return;
        }
        Msg.send(p, "<gray>Dialing <red><n></red>…", Msg.p("n", plugin.settings().emergencyNumber));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) Msg.send(p, "<yellow>Emergency services are not available yet.");
        }, 40L);
    }

    public void start(Player caller, String number) {
        if (number.equals(plugin.settings().emergencyNumber)) {
            dialEmergency(caller);
            return;
        }
        if (inCall(caller)) {
            Msg.send(caller, "<red>You are already in a call. Use <white>/call end</white> first.");
            return;
        }
        SignalService.Status st = plugin.signal().status(caller);
        if (st.phone() == null) {
            Msg.send(caller, "<red>You don't have a phone. Get one in <white>/cell eshop</white>.");
            return;
        }
        if (st.hasService() && st.outgoing(true) == null) {
            Msg.send(caller, "<red>None of your plans include calls.");
            return;
        }
        if (!st.hasService() && st.outgoing(true) == null) {
            Msg.send(caller, st.emergency()
                    ? "<red>No Service</red> <gray>· Emergency calls only (<n>).".replace("<n>", plugin.settings().emergencyNumber)
                    : "<red>No signal. The call could not be placed.");
            return;
        }
        if (plugin.store().numberOwner(number) == null) {
            Msg.send(caller, "<red>The number <white><n></white> is not in service.", Msg.p("n", Numbers.format(number)));
            return;
        }
        if (plugin.store().numberOwner(number).player().equals(caller.getUniqueId())) {
            Msg.send(caller, "<red>You can't call yourself.");
            return;
        }
        String shown = plugin.sims().displayName(caller, number);
        Player target = plugin.sims().reachablePlayer(number);
        SignalService.SimStatus targetSim = target == null ? null : plugin.sims().simFor(target, number);
        if (target == null || targetSim == null || !targetSim.calls() || inCall(target)
                || ringing.containsKey(target.getUniqueId()) || plugin.store().profile(target.getUniqueId()).dnd()) {
            Msg.send(caller, "<gray>The number you are calling (<white><n></white>) is not reachable right now.", Msg.p("n", shown));
            return;
        }
        String callerNumber = plugin.store().profile(caller.getUniqueId()).number(st.outgoing(true).carrier().id());
        long exp = System.currentTimeMillis() + plugin.settings().callRingSeconds * 1000L;
        ringing.put(target.getUniqueId(), new Ring(caller.getUniqueId(), callerNumber, number, exp));
        Msg.send(caller, "<gray>Calling <white><t></white>…", Msg.p("t", shown));
        target.playSound(target.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.2f);
        Msg.send(target, "<aqua><c></aqua> <gray>is calling you. <green><click:run_command:'/call accept'>[Accept]</click></green> <red><click:run_command:'/call deny'>[Decline]</click></red>",
                Msg.p("c", plugin.sims().displayName(target, callerNumber)));
    }

    public void accept(Player p) {
        Ring r = ringing.remove(p.getUniqueId());
        Player caller = r == null ? null : Bukkit.getPlayer(r.caller());
        if (caller == null || r.expiresAt() < System.currentTimeMillis()) {
            Msg.send(p, "<red>No incoming call.");
            return;
        }
        if (inCall(caller) || inCall(p)) {
            Msg.send(p, "<red>That call is no longer available.");
            return;
        }
        inCall.put(p.getUniqueId(), caller.getUniqueId());
        inCall.put(caller.getUniqueId(), p.getUniqueId());
        partnerNumber.put(p.getUniqueId(), r.callerNumber());
        partnerNumber.put(caller.getUniqueId(), r.targetNumber());
        String msg = "<green>Call connected with <white><o></white>. Your chat now goes only to them. <gray>(<white>/call end</white> to hang up)";
        Msg.send(p, msg, Msg.p("o", partnerName(p)));
        Msg.send(caller, msg, Msg.p("o", partnerName(caller)));
    }

    public void deny(Player p) {
        Ring r = ringing.remove(p.getUniqueId());
        if (r == null) {
            Msg.send(p, "<red>No incoming call.");
            return;
        }
        Msg.send(p, "<gray>Call declined.");
        Player c = Bukkit.getPlayer(r.caller());
        if (c != null) Msg.send(c, "<gray><t> declined your call.", Msg.p("t", plugin.sims().displayName(c, r.targetNumber())));
    }

    public void end(Player p, String reasonForBoth) {
        UUID other = inCall.remove(p.getUniqueId());
        if (other == null) {
            // Cancel an outgoing ring, if any.
            boolean cancelled = ringing.values().removeIf(r -> r.caller().equals(p.getUniqueId()));
            Msg.send(p, cancelled ? "<gray>Call cancelled." : "<red>You are not in a call.");
            return;
        }
        inCall.remove(other);
        partnerNumber.remove(p.getUniqueId());
        partnerNumber.remove(other);
        Msg.send(p, reasonForBoth);
        Player o = Bukkit.getPlayer(other);
        if (o != null) Msg.send(o, reasonForBoth);
    }

    public void onSignalUpdate() {
        long now = System.currentTimeMillis();
        ringing.entrySet().removeIf(e -> {
            if (e.getValue().expiresAt() >= now) return false;
            Player c = Bukkit.getPlayer(e.getValue().caller());
            if (c != null) Msg.send(c, "<gray>No answer.");
            return true;
        });
        for (Map.Entry<UUID, UUID> e : Map.copyOf(inCall).entrySet()) {
            Player a = Bukkit.getPlayer(e.getKey());
            if (a == null || !inCall.containsKey(e.getKey())) continue;
            var aSt = plugin.signal().status(a);
            if (!aSt.hasService() && !aSt.wifiCalling()) {
                end(a, "<red>Call dropped <gray>— signal lost.");
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        UUID other = inCall.get(p.getUniqueId());
        if (other == null) return;
        e.setCancelled(true);
        Player o = Bukkit.getPlayer(other);
        if (o == null) return;
        String text = PlainTextComponentSerializer.plainText().serialize(e.message());
        SignalService.SimStatus a = plugin.signal().status(p).primary();
        SignalService.SimStatus b = plugin.signal().status(o).primary();
        int bars = Math.min(callBars(p, a), callBars(o, b));
        String heard = bars <= plugin.settings().garbleBars ? garble(text) : text;
        Msg.raw(p, "<dark_aqua>[Call] <white>You</white><gray>: <white><m>", Msg.p("m", text));
        String asSeen = partnerName(o);
        Msg.raw(o, "<dark_aqua>[Call] <white><n></white><gray>: <white><m>", Msg.p("n", asSeen == null ? p.getName() : asSeen), Msg.p("m", heard));
    }

    /** Signal bars for call quality: the tower link, or full bars over home Wi-Fi. */
    private int callBars(Player p, SignalService.SimStatus sim) {
        if (sim != null && sim.link() != null) return sim.link().bars();
        return plugin.signal().status(p).wifiCalling() ? 4 : 0;
    }

    private String garble(String s) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double chance = plugin.settings().garbleChance;
        StringBuilder sb = new StringBuilder(s.length());
        for (char ch : s.toCharArray()) sb.append(ch != ' ' && r.nextDouble() < chance ? '-' : ch);
        return sb.toString();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        ringing.remove(p.getUniqueId());
        ringing.values().removeIf(r -> r.caller().equals(p.getUniqueId()));
        if (inCall(p)) end(p, "<gray>Call ended.");
        plugin.signal().clear(p.getUniqueId());
        plugin.hud().forget(p.getUniqueId());
    }
}
