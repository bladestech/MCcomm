package dev.mccomm.cell.economy;

import dev.mccomm.cell.CellPlugin;
import dev.mccomm.cell.model.Agreement;
import dev.mccomm.cell.model.Carrier;
import dev.mccomm.cell.util.Msg;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Roaming agreements and MVNO hosting. The host offers terms, the partner accepts. Either side can
 * end an agreement; it then keeps working for the notice period.
 */
public final class PartnerService {
    private static final long HOUR = 3_600_000L;

    /** An agreement together with the host that holds it. */
    public record Deal(Carrier host, Agreement agreement) {
    }

    private final CellPlugin plugin;

    public PartnerService(CellPlugin plugin) {
        this.plugin = plugin;
    }

    // ================================================================ lookups

    /** The network an MVNO runs on, or null. */
    public Deal mvnoHost(Carrier mvno) {
        if (mvno == null || !mvno.mvno()) return null;
        for (Carrier h : plugin.store().carriers()) {
            Agreement a = h.agreements().get(Agreement.key(Agreement.Kind.MVNO, mvno.id()));
            if (a != null) return new Deal(h, a);
        }
        return null;
    }

    /** Networks this carrier's subscribers can roam on. */
    public List<Deal> roamingHosts(Carrier home) {
        List<Deal> out = new ArrayList<>();
        for (Carrier h : plugin.store().carriers()) {
            Agreement a = h.agreements().get(Agreement.key(Agreement.Kind.ROAMING, home.id()));
            if (a != null) out.add(new Deal(h, a));
        }
        return out;
    }

    public Agreement roamingDeal(Carrier host, Carrier home) {
        return host == null || home == null ? null : host.agreements().get(Agreement.key(Agreement.Kind.ROAMING, home.id()));
    }

    /** Offers other carriers have made to this one. */
    public List<Deal> offersTo(Carrier partner) {
        List<Deal> out = new ArrayList<>();
        for (Carrier h : plugin.store().carriers()) {
            for (Agreement a : h.offers().values()) if (a.partnerId().equals(partner.id())) out.add(new Deal(h, a));
        }
        return out;
    }

    /** Agreements where this carrier is the partner (roaming on others, or its MVNO host). */
    public List<Deal> asPartner(Carrier partner) {
        List<Deal> out = new ArrayList<>();
        for (Carrier h : plugin.store().carriers()) {
            for (Agreement a : h.agreements().values()) if (a.partnerId().equals(partner.id())) out.add(new Deal(h, a));
        }
        return out;
    }

    /** An MVNO whose payments to its host are overdue past the grace period. */
    public boolean suspended(Carrier mvno) {
        return mvno != null && mvno.mvno() && mvno.unpaidSince() != 0
                && System.currentTimeMillis() - mvno.unpaidSince() >= plugin.settings().mvnoGraceHours * HOUR;
    }

    // ================================================================ actions

    /** Creates (or returns the existing) offer from {@code host} to {@code partner}. Null if not allowed. */
    public Agreement offer(Player who, Carrier host, Agreement.Kind kind, Carrier partner) {
        if (host.id().equals(partner.id())) {
            Msg.send(who, "<red>You can't make a deal with yourself.");
            return null;
        }
        if (host.mvno()) {
            Msg.send(who, "<red>An MVNO has no towers, so it can't host other carriers.");
            return null;
        }
        String key = Agreement.key(kind, partner.id());
        if (host.agreements().containsKey(key)) {
            Msg.send(who, "<red>You already have that agreement with <white><c></white>.", Msg.p("c", partner.name()));
            return null;
        }
        if (kind == Agreement.Kind.MVNO) {
            if (!partner.mvno()) {
                Msg.send(who, "<red><white><c></white> isn't an MVNO. Only virtual carriers can be hosted.", Msg.p("c", partner.name()));
                return null;
            }
            if (mvnoHost(partner) != null) {
                Msg.send(who, "<red><white><c></white> already runs on another network.", Msg.p("c", partner.name()));
                return null;
            }
        }
        Agreement existing = host.offers().get(key);
        if (existing != null) return existing;
        Agreement a = new Agreement(kind, partner.id());
        host.offers().put(key, a);
        plugin.store().markDirty();
        plugin.billing().notify(partner.owner(), (kind == Agreement.Kind.ROAMING
                        ? "<aqua><h></aqua> <gray>offers your subscribers roaming on its network."
                        : "<aqua><h></aqua> <gray>offers to host your MVNO on its network.")
                        + " <gray>Review it in <white>/carrier manage</white> → Partners.",
                Msg.p("h", host.name()));
        return a;
    }

    public void accept(Player who, Carrier partner, Deal offer) {
        Carrier host = offer.host();
        Agreement a = offer.agreement();
        if (host.offers().remove(a.key()) == null) {
            Msg.send(who, "<red>That offer is no longer available.");
            return;
        }
        if (a.kind() == Agreement.Kind.MVNO && mvnoHost(partner) != null) {
            Msg.send(who, "<red>You already run on another network. End that agreement first.");
            return;
        }
        a.endsAt(0);
        host.agreements().put(a.key(), a);
        plugin.store().markDirty();
        Msg.send(who, "<green>Agreement with <white><h></white> is now active.", Msg.p("h", host.name()));
        plugin.billing().notify(host.owner(), "<green><p> accepted your <k> offer.", Msg.p("p", partner.name()),
                Msg.p("k", a.kind() == Agreement.Kind.ROAMING ? "roaming" : "MVNO hosting"));
    }

    public void decline(Player who, Deal offer) {
        offer.host().offers().remove(offer.agreement().key());
        plugin.store().markDirty();
        Carrier partner = plugin.store().carrier(offer.agreement().partnerId());
        Msg.send(who, "<yellow>Declined.");
        plugin.billing().notify(offer.host().owner(), "<yellow><p> declined your offer.", Msg.p("p", partner == null ? "?" : partner.name()));
    }

    /** Starts the notice period. Either side may call it. */
    public void end(Player who, Deal deal) {
        Agreement a = deal.agreement();
        if (a.endsAt() != 0) {
            Msg.send(who, "<gray>That agreement is already ending.");
            return;
        }
        a.endsAt(System.currentTimeMillis() + plugin.settings().noticeHours * HOUR);
        plugin.store().markDirty();
        Carrier partner = plugin.store().carrier(a.partnerId());
        String what = a.kind() == Agreement.Kind.ROAMING ? "roaming agreement" : "MVNO hosting agreement";
        String msg = "<yellow>The " + what + " between <white><h></white> and <white><p></white> ends in <white><t></white>.";
        var r = new net.kyori.adventure.text.minimessage.tag.resolver.TagResolver[]{
                Msg.p("h", deal.host().name()), Msg.p("p", partner == null ? "?" : partner.name()),
                Msg.p("t", BillingService.until(a.endsAt() - System.currentTimeMillis()))};
        plugin.billing().notify(deal.host().owner(), msg, r);
        if (partner != null && !partner.owner().equals(deal.host().owner())) plugin.billing().notify(partner.owner(), msg, r);
    }

    /** Removes agreements whose notice period is over, and offers to carriers that no longer exist. */
    public void tick() {
        long now = System.currentTimeMillis();
        for (Carrier h : new ArrayList<>(plugin.store().carriers())) {
            for (Agreement a : new ArrayList<>(h.agreements().values())) {
                Carrier partner = plugin.store().carrier(a.partnerId());
                if (partner == null || (a.endsAt() != 0 && now >= a.endsAt())) {
                    h.agreements().remove(a.key());
                    plugin.store().markDirty();
                    if (partner != null) {
                        String what = a.kind() == Agreement.Kind.ROAMING ? "roaming agreement" : "MVNO hosting";
                        plugin.billing().notify(h.owner(), "<yellow>Your " + what + " with <white><p></white> has ended.", Msg.p("p", partner.name()));
                        plugin.billing().notify(partner.owner(), "<yellow>Your " + what + " with <white><h></white> has ended."
                                + (a.kind() == Agreement.Kind.MVNO ? " Your subscribers have no network until you find a new host." : ""),
                                Msg.p("h", h.name()));
                    }
                }
            }
            h.offers().values().removeIf(a -> plugin.store().carrier(a.partnerId()) == null);
        }
    }
}
