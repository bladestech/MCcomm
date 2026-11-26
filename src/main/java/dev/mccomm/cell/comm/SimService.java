package dev.mccomm.cell.comm;

import dev.mccomm.cell.CellPlugin;
import dev.mccomm.cell.model.Carrier;
import dev.mccomm.cell.model.PlayerProfile;
import dev.mccomm.cell.signal.SignalService;
import dev.mccomm.cell.storage.DataStore;
import dev.mccomm.cell.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;

/** SIM management and phone-number lookups, shared by the phone GUI and the commands. */
public final class SimService {
    private final CellPlugin plugin;

    public SimService(CellPlugin plugin) {
        this.plugin = plugin;
    }

    private PlayerProfile prof(Player p) {
        return plugin.store().profile(p.getUniqueId());
    }

    public boolean subscribe(Player p, Carrier c, dev.mccomm.cell.model.Plan plan) {
        PlayerProfile prof = prof(p);
        if (!prof.overdue().isEmpty()) {
            Msg.send(p, "<red>You have an overdue balance of <white><o></white>. Pay it off before subscribing to any carrier.",
                    Msg.p("o", plugin.settings().money(prof.totalOverdue())));
            return false;
        }
        if (prof.has(c.id())) {
            Msg.send(p, "<gray>You already have a <white><c></white> SIM.", Msg.p("c", c.name()));
            return false;
        }
        if (prof.active().size() < plugin.billing().simSlots(p)) {
            prof.active().add(c.id());
        } else if (prof.inactive().size() < plugin.settings().maxInactive) {
            prof.inactive().add(c.id());
        } else {
            Msg.send(p, "<red>You have the maximum number of SIMs. Unsubscribe from one first.");
            return false;
        }
        if (!plugin.billing().startPlan(p, c, plan)) {
            prof.active().remove(c.id());
            prof.inactive().remove(c.id());
            return false;
        }
        String number = plugin.store().assignNumber(prof, c);
        boolean active = prof.active().contains(c.id());
        Msg.send(p, "<green>Subscribed to <white><c></white> on <white><plan></white> (<white><price></white>). Your number is <white><n></white>" + (active
                        ? " (SIM <slot>, active)." : ". Both SIM slots are full, so it was saved as inactive."),
                Msg.p("c", c.name()), Msg.p("n", Numbers.format(number)), Msg.p("slot", "" + prof.active().size()),
                Msg.p("plan", plan.name()), Msg.p("price", plugin.settings().money(plan.price())));
        plugin.store().markDirty();
        return true;
    }

    public boolean unsubscribe(Player p, Carrier c) {
        PlayerProfile prof = prof(p);
        if (!prof.has(c.id())) {
            Msg.send(p, "<red>You are not subscribed to that carrier.");
            return false;
        }
        if (!plugin.billing().canLeave(p, c)) return false;
        String n = prof.number(c.id());
        plugin.store().unsubscribe(prof, c.id());
        Msg.send(p, "<yellow>Unsubscribed from <white><c></white>. Number <white><n></white> was released.",
                Msg.p("c", c.name()), Msg.p("n", Numbers.format(n)));
        return true;
    }

    public boolean activate(Player p, Carrier c) {
        PlayerProfile prof = prof(p);
        if (!prof.inactive().contains(c.id())) {
            Msg.send(p, "<red>That carrier is not one of your inactive SIMs.");
            return false;
        }
        if (prof.active().size() >= plugin.billing().simSlots(p)) {
            Msg.send(p, plugin.billing().simSlots(p) == 1
                    ? "<red>Your phone has one SIM slot and it's in use. Deactivate it first, or get a + model for dual SIM."
                    : "<red>Both SIM slots are in use. Deactivate one first.");
            return false;
        }
        prof.inactive().remove(c.id());
        prof.active().add(c.id());
        plugin.store().markDirty();
        Msg.send(p, "<green><c> is now active in SIM <n>.", Msg.p("c", c.name()), Msg.p("n", "" + prof.active().size()));
        return true;
    }

    public boolean deactivate(Player p, Carrier c) {
        PlayerProfile prof = prof(p);
        if (!prof.active().contains(c.id())) {
            Msg.send(p, "<red>That carrier is not one of your active SIMs.");
            return false;
        }
        if (prof.inactive().size() >= plugin.settings().maxInactive) {
            Msg.send(p, "<red>No room for another inactive SIM. Unsubscribe from one first.");
            return false;
        }
        prof.active().remove(c.id());
        prof.inactive().add(c.id());
        plugin.store().markDirty();
        Msg.send(p, "<yellow><c> moved to inactive SIMs. Calls and texts to that number won't reach you.", Msg.p("c", c.name()));
        return true;
    }

    /** Chooses the data SIM. {@code c == null} switches back to automatic. */
    public void setDataSim(Player p, Carrier c) {
        PlayerProfile prof = prof(p);
        if (c != null && !prof.active().contains(c.id())) {
            Msg.send(p, "<red>Only an active SIM can be your data SIM.");
            return;
        }
        prof.dataSim(c == null ? null : c.id());
        plugin.store().markDirty();
        if (c == null) Msg.send(p, "<green>Data SIM set to <white>automatic</white> (first SIM with service).");
        else Msg.send(p, "<green>Data SIM set to <white><c></white>. If it has no service, the other SIM takes over.", Msg.p("c", c.name()));
    }

    // ----------------------------------------------------------------- numbers

    /** The number this player calls/texts from right now: their data SIM with service, else first active SIM. */
    public String outgoingNumber(Player p) {
        PlayerProfile prof = prof(p);
        SignalService.SimStatus prim = plugin.signal().status(p).primary();
        if (prim != null) return prof.number(prim.carrier().id());
        if (prof.dataSim() != null && prof.number(prof.dataSim()) != null) return prof.number(prof.dataSim());
        for (String cid : prof.active()) {
            String n = prof.number(cid);
            if (n != null) return n;
        }
        return null;
    }

    /**
     * Turns user input into a phone number: a number ("312-4821"), a contact name, or an online
     * player's name (their current number). Returns null if nothing matches.
     */
    public String resolve(Player requester, String input) {
        String digits = Numbers.normalize(input);
        if (digits != null) return digits;
        for (Map.Entry<String, String> e : prof(requester).contacts().entrySet()) {
            if (e.getValue().equalsIgnoreCase(input)) return e.getKey();
        }
        Player target = Bukkit.getPlayerExact(input);
        return target == null ? null : outgoingNumber(target);
    }

    /** How this player sees a number: their contact name if saved, else the formatted number. */
    public String displayName(Player viewer, String digits) {
        String name = prof(viewer).contacts().get(digits);
        return name != null ? name : Numbers.format(digits);
    }

    /** The online player who can currently be reached on this number (active SIM), or null. */
    public Player reachablePlayer(String digits) {
        DataStore.NumberOwner o = plugin.store().numberOwner(digits);
        if (o == null) return null;
        Player p = Bukkit.getPlayer(o.player());
        if (p == null) return null;
        return prof(p).active().contains(o.carrierId()) ? p : null;
    }

    /** True if the owner of this number has service on that specific SIM right now. */
    public boolean hasServiceOn(Player p, String digits) {
        SignalService.SimStatus s = simFor(p, digits);
        return s != null && (s.link() != null || s.wifi());
    }

    /** The owner's SIM status for this number, or null if that SIM isn't active. */
    public SignalService.SimStatus simFor(Player p, String digits) {
        DataStore.NumberOwner o = plugin.store().numberOwner(digits);
        if (o == null) return null;
        for (SignalService.SimStatus s : plugin.signal().status(p).sims()) {
            if (s.carrier().id().equals(o.carrierId())) return s;
        }
        return null;
    }
}
