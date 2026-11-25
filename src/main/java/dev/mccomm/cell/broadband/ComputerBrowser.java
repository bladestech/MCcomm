package dev.mccomm.cell.broadband;

import dev.mccomm.cell.CellPlugin;
import dev.mccomm.cell.broadband.BroadbandService.Link;
import dev.mccomm.cell.economy.BillingService;
import dev.mccomm.cell.gui.Frame;
import dev.mccomm.cell.gui.Items;
import dev.mccomm.cell.gui.Menu;
import dev.mccomm.cell.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The little web on a computer: a news page, a marketplace and mail. Pages load at the line's real speed
 * (ping plus size over download) and use data on a capped plan, so a slow line really is slow to browse.
 */
public final class ComputerBrowser {
    private static final int[] LIST3 = Frame.rows(1, 3);

    private final CellPlugin plugin;

    public ComputerBrowser(CellPlugin plugin) {
        this.plugin = plugin;
    }

    private BroadbandService bb() {
        return plugin.broadband();
    }

    private BroadbandStore store() {
        return plugin.broadband().store();
    }

    private BroadbandSettings.Browser cfg() {
        return bb().cfg().browser();
    }

    private static String nameOf(UUID id) {
        String n = id == null ? null : Bukkit.getOfflinePlayer(id).getName();
        return n == null ? "?" : n;
    }

    /** Opens the browser's start page on this computer. */
    public void open(Player p, Computer c) {
        load(p, c, cfg().pageMb() * 0.5, l -> home(p, c));
    }

    /** Waits as long as the line makes this page take, uses its data, then shows it. */
    private void load(Player p, Computer c, double sizeMb, Consumer<Link> show) {
        Link l = bb().computerLink(c, p.getUniqueId());
        if (l == null) {
            Msg.send(p, "<red>No connection: the page can't load.");
            p.closeInventory();
            return;
        }
        bb().useWifiData(l, sizeMb);
        double seconds = l.pingMs() / 1000.0 + sizeMb * 8 / Math.max(0.01, l.downMbps());
        long ticks = Math.clamp(Math.round(seconds * 20), 2, 120);
        p.closeInventory();
        p.sendActionBar(Msg.mm("<gray>Loading… <white><secs>", Msg.p("secs", String.format(java.util.Locale.ROOT, "%.1f s", seconds))));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline() || store().computer(c.id()) != c) return;
            Link now = bb().computerLink(c, p.getUniqueId());
            if (now == null) {
                Msg.send(p, "<red>The connection dropped while the page was loading.");
                return;
            }
            show.accept(now);
        }, ticks);
    }

    private void home(Player p, Computer c) {
        Menu m = Frame.create(p, "Browser · " + c.name(), "bb-browser");
        int unread = (int) store().inbox(p.getUniqueId()).stream().filter(x -> !x.read()).count();
        m.set(20, Items.of(Material.WRITTEN_BOOK, "<aqua>News", "<gray>What's happening on the server."), e -> {
            Frame.click(p);
            load(p, c, cfg().pageMb(), l -> news(p, c));
        });
        m.set(22, Items.of(Material.EMERALD, "<green>Marketplace", "<gray>" + store().listings().size() + " listing(s).", "<gray>Post what you sell, contact sellers."), e -> {
            Frame.click(p);
            load(p, c, cfg().pageMb(), l -> market(p, c, 0));
        });
        m.set(24, unread > 0 ? Items.glow(Items.of(Material.PAPER, "<yellow>Mail <gold>(" + unread + " new)", "<gray>Your inbox."))
                : Items.of(Material.PAPER, "<white>Mail", "<gray>Your inbox."), e -> {
            Frame.click(p);
            load(p, c, cfg().pageMb() * 0.5, l -> mail(p, c, 0));
        });
        Frame.back(m, p, "Computer", () -> plugin.broadbandMenu().computer(p, c));
        m.open(p);
    }

    // ------------------------------------------------------------------- news

    private void news(Player p, Computer c) {
        Menu m = Frame.create(p, "Browser · News", "bb-news");
        int slot = 9;
        for (String line : cfg().news()) {
            if (slot > 44) break;
            m.set(slot++, Items.of(Material.PAPER, "<white>Headline", Items.wrap(line, 36, "<gray>")));
        }
        Frame.back(m, p, "Start page", () -> home(p, c));
        m.open(p);
    }

    // ------------------------------------------------------------ marketplace

    private void market(Player p, Computer c, int page) {
        Menu m = Frame.create(p, "Browser · Marketplace", "bb-market");
        List<Listing> list = new ArrayList<>(store().listings());
        list.sort((a, b) -> Long.compare(b.time(), a.time()));
        if (list.isEmpty()) m.set(22, Frame.empty("No listings", "Post the first one below."));
        Frame.page(m, p, list, page, LIST3, l -> {
            boolean mine = l.seller().equals(p.getUniqueId());
            return Items.head(Bukkit.getOfflinePlayer(l.seller()), "<white>" + Items.esc(l.title()), List.of(
                    "<gray>Seller: <white>" + Items.esc(nameOf(l.seller())),
                    "<gray>Price: <white>" + plugin.settings().money(l.price()),
                    "<gray>Posted: <white>" + BillingService.until(System.currentTimeMillis() - l.time()) + " ago",
                    mine ? "<yellow>Shift-click to take it down" : "<yellow>Click to message the seller"));
        }, (l, e) -> {
            Frame.click(p);
            if (l.seller().equals(p.getUniqueId())) {
                if (e.isShiftClick()) {
                    store().listings().remove(l);
                    store().markDirty();
                    market(p, c, page);
                }
                return;
            }
            compose(p, c, l.seller(), "Message about \"" + l.title() + "\"", () -> market(p, c, page));
        }, pg -> market(p, c, pg));
        m.set(40, Items.of(Material.WRITABLE_BOOK, "<green>Post a listing", "<gray>Up to " + cfg().listingsPerPlayer() + " at a time, for "
                + cfg().listingDays() + " days."), e -> {
            Frame.click(p);
            postListing(p, c);
        });
        Frame.back(m, p, "Start page", () -> home(p, c));
        m.open(p);
    }

    private void postListing(Player p, Computer c) {
        long mine = store().listings().stream().filter(l -> l.seller().equals(p.getUniqueId())).count();
        if (mine >= cfg().listingsPerPlayer() || store().listings().size() >= cfg().maxListings()) {
            Msg.send(p, "<red>No room for another listing right now.");
            return;
        }
        plugin.prompt().ask(p, "<aqua>What are you selling? (a short title)", title -> {
            if (title.length() > 40) {
                Msg.send(p, "<red>Titles are at most 40 characters.");
                return;
            }
            plugin.prompt().ask(p, "<aqua>Price in credits?", in -> {
                long price;
                try {
                    price = Long.parseLong(in.replace(",", "").trim());
                    if (price < 0) throw new NumberFormatException();
                } catch (NumberFormatException ex) {
                    Msg.send(p, "<red>Enter a whole number of credits.");
                    return;
                }
                store().listings().add(new Listing(store().allocateListingId(), p.getUniqueId(), title, price, System.currentTimeMillis()));
                store().markDirty();
                Msg.send(p, "<green>Listing posted.");
                market(p, c, 0);
            });
        });
    }

    // ------------------------------------------------------------------- mail

    private void mail(Player p, Computer c, int page) {
        Menu m = Frame.create(p, "Browser · Mail", "bb-mail");
        List<Mail> box = store().inbox(p.getUniqueId());
        if (box.isEmpty()) m.set(22, Frame.empty("No mail", "Write to someone below."));
        Frame.page(m, p, new ArrayList<>(box), page, LIST3, x -> {
            OfflinePlayer from = Bukkit.getOfflinePlayer(x.from());
            List<String> lore = new ArrayList<>(Items.wrap(x.text(), 36, "<gray>"));
            lore.add("<dark_gray>" + BillingService.until(System.currentTimeMillis() - x.time()) + " ago");
            lore.add("<yellow>Click to read and reply · shift-click to delete");
            var it = Items.head(from, (x.read() ? "<white>" : "<yellow>") + "From " + Items.esc(nameOf(x.from())), lore);
            return x.read() ? it : Items.glow(it);
        }, (x, e) -> {
            Frame.click(p);
            if (e.isShiftClick()) {
                box.remove(x);
                store().markDirty();
                mail(p, c, page);
                return;
            }
            x.read(true);
            store().markDirty();
            Msg.raw(p, "<dark_gray>━━━ <aqua>Mail from <n></aqua> ━━━", Msg.p("n", nameOf(x.from())));
            Msg.raw(p, "<white><t>", Msg.p("t", x.text()));
            compose(p, c, x.from(), "Reply to " + nameOf(x.from()), () -> mail(p, c, page));
        }, pg -> mail(p, c, pg));
        m.set(40, Items.of(Material.WRITABLE_BOOK, "<green>Write mail", "<gray>Send a message to any player."), e -> {
            Frame.click(p);
            plugin.prompt().ask(p, "<aqua>Who is it for? (player name)", name -> {
                OfflinePlayer to = Bukkit.getPlayerExact(name);
                if (to == null) to = Bukkit.getOfflinePlayerIfCached(name);
                if (to == null) {
                    Msg.send(p, "<red>Unknown player <white><n></white>.", Msg.p("n", name));
                    return;
                }
                compose(p, c, to.getUniqueId(), "Message for " + name, () -> mail(p, c, 0));
            });
        });
        Frame.back(m, p, "Start page", () -> home(p, c));
        m.open(p);
    }

    /** Asks for the text and sends it. Needs a connection at the moment of sending. */
    private void compose(Player p, Computer c, UUID to, String question, Runnable done) {
        plugin.prompt().ask(p, "<aqua>" + Items.esc(question) + "?", text -> {
            if (text.length() > cfg().mailLength()) {
                Msg.send(p, "<red>Mail is at most <n> characters.", Msg.p("n", "" + cfg().mailLength()));
                return;
            }
            Link l = bb().computerLink(c, p.getUniqueId());
            if (l == null) {
                Msg.send(p, "<red>No connection: the mail wasn't sent.");
                return;
            }
            if (to.equals(p.getUniqueId())) {
                Msg.send(p, "<red>You can't mail yourself.");
                return;
            }
            bb().useWifiData(l, 0.05);
            List<Mail> box = store().inbox(to);
            box.addFirst(new Mail(p.getUniqueId(), text, System.currentTimeMillis(), false));
            while (box.size() > cfg().mailInbox()) box.removeLast();
            store().markDirty();
            Msg.send(p, "<green>Mail sent to <white><n></white>.", Msg.p("n", nameOf(to)));
            Player online = Bukkit.getPlayer(to);
            if (online != null) Msg.send(online, "<yellow>You've got mail from <white><n></white>. Open a computer's Browser to read it.", Msg.p("n", p.getName()));
            else plugin.billing().notify(to, "<yellow>You've got mail from <white><n></white>. Open a computer's Browser to read it.", Msg.p("n", p.getName()));
            done.run();
        });
    }
}
