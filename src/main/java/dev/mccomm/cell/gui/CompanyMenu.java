package dev.mccomm.cell.gui;

import dev.mccomm.cell.CellPlugin;
import dev.mccomm.cell.Settings;
import dev.mccomm.cell.economy.CompanyService;
import dev.mccomm.cell.economy.Group;
import dev.mccomm.cell.model.Carrier;
import dev.mccomm.cell.model.Company;
import dev.mccomm.cell.model.Manufacturer;
import dev.mccomm.cell.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;

/**
 * Company actions (valuation, money in and out, selling, liquidation) shown on the Money tab of the carrier,
 * manufacturer and ISP menus. Slots: valuation 13, withdraw 29, add 31, sell 33, parent company 35, liquidate 40.
 */
public final class CompanyMenu {
    private static final long CONFIRM_MS = 5000;

    private final CellPlugin plugin;
    private final Map<String, Long> confirms = new HashMap<>();

    public CompanyMenu(CellPlugin plugin) {
        this.plugin = plugin;
    }

    private Settings s() {
        return plugin.settings();
    }

    private boolean confirmed(Player p, String action) {
        String key = p.getUniqueId() + ":" + action;
        long now = System.currentTimeMillis();
        Long until = confirms.get(key);
        if (until != null && until > now) {
            confirms.remove(key);
            return true;
        }
        confirms.put(key, now + CONFIRM_MS);
        return false;
    }

    public List<String> valuationLore(CompanyService.Valuation v) {
        List<String> lore = new ArrayList<>();
        for (var e : v.parts().entrySet()) lore.add("<gray>" + Items.esc(e.getKey()) + ": <white>" + s().money(e.getValue()));
        lore.add("<dark_gray>A guide for setting a sale price.");
        return lore;
    }

    public void askAmount(Player p, String question, LongConsumer ok) {
        plugin.prompt().ask(p, question, in -> {
            try {
                long v = Long.parseLong(in.replace(",", "").trim());
                if (v <= 0) throw new NumberFormatException();
                ok.accept(v);
            } catch (NumberFormatException ex) {
                Msg.send(p, "<red>Enter a whole number above 0.");
            }
        });
    }

    /** Asks for an online buyer and a price, then {@code offer} sends the offer. */
    public void askSale(Player p, String name, java.util.function.BiConsumer<Player, Long> offer) {
        plugin.prompt().ask(p, "<aqua>Sell <white>" + Items.esc(name) + "</white> to which online player?", buyerName -> {
            Player buyer = Bukkit.getPlayerExact(buyerName);
            if (buyer == null) {
                Msg.send(p, "<red>That player isn't online.");
                return;
            }
            plugin.prompt().ask(p, "<aqua>Price for <white>" + Items.esc(name) + "</white> (credits, paid from their wallet to yours):", in -> {
                try {
                    offer.accept(buyer, Long.parseLong(in.replace(",", "").trim()));
                } catch (NumberFormatException ex) {
                    Msg.send(p, "<red>Enter a whole number.");
                }
            });
        });
    }

    /** Old entry point (/carrier company): the carrier's Money tab. */
    public void carrier(Player p, Carrier c) {
        plugin.carrierMenu().tab(p, c, CarrierMenu.MONEY);
    }

    /** Old entry point (/manufacturer company): the manufacturer's Money tab. */
    public void manufacturer(Player p, Manufacturer mf) {
        plugin.manufacturerMenu().money(p, mf);
    }

    private void moveItems(Menu m, Player p, String name, long companyBalance, java.util.function.BiConsumer<Long, Boolean> move, Runnable reopen) {
        double fee = s().transferFeePercent;
        long mine = plugin.store().profile(p.getUniqueId()).balance();
        m.set(29, Items.of(Material.HOPPER_MINECART, "<green>Withdraw to your wallet",
                "<gray>Balance: <white>" + s().money(companyBalance) + " <gray>· fee " + fee + "%", "<yellow>Click · choose an amount"), e -> {
            Frame.click(p);
            askAmount(p, "<aqua>Withdraw how many credits from <white>" + Items.esc(name) + "</white>?", a -> {
                move.accept(a, true);
                reopen.run();
            });
        });
        m.set(31, Items.of(Material.CHEST_MINECART, "<aqua>Add from your wallet",
                "<gray>Yours: <white>" + s().money(mine) + " <gray>· fee " + fee + "%", "<yellow>Click · choose an amount"), e -> {
            Frame.click(p);
            askAmount(p, "<aqua>Move how many credits into <white>" + Items.esc(name) + "</white>?", a -> {
                move.accept(a, false);
                reopen.run();
            });
        });
    }

    /** Fills a company's Money tab items (carrier, manufacturer or ISP). */
    public void items(Menu m, Player p, Company c, Runnable reopen) {
        boolean owner = plugin.companies().isOwner(p, c);
        CompanyService.Valuation v = plugin.companies().valuation(c);
        m.set(13, Items.glow(Items.of(Material.GOLD_BLOCK, "<gold><b>Worth " + s().money(v.total()), valuationLore(v))));
        Group gr = plugin.groups().groupOf(c);
        if (!owner) {
            m.set(31, Items.of(Material.BARRIER, "<gray>Only the owner can move money,", "<gray>sell or liquidate."));
            return;
        }
        moveItems(m, p, c.name(), c.balance(), (a, out) -> plugin.companies().move(p, c, a, out), reopen);
        m.set(33, Items.of(Material.WRITABLE_BOOK, "<white>Sell the company", "<gray>Offer it to an online player.",
                gr == null ? "<gray>Everything it owns goes with it." : "<gray>It leaves " + Items.esc(gr.name()) + " when sold.",
                "<yellow>Click · make an offer"), e -> {
            Frame.click(p);
            askSale(p, c.name(), (buyer, price) -> plugin.companies().offerSale(p, c, buyer, price));
        });
        groupItem(m, p, c, gr, reopen);
        CompanyService.Liquidation l = plugin.companies().previewLiquidation(c);
        List<String> lore = new ArrayList<>();
        for (var e : l.parts().entrySet()) {
            long val = e.getValue();
            lore.add("<gray>" + Items.esc(e.getKey()) + ": " + (val < 0 ? "<red>-" + s().money(-val) : "<white>" + s().money(val)));
        }
        lore.add("<gray>Fee: <red>-" + s().money(l.fee()) + " <gold>· " + (l.toGroup() == null ? "You'd get " : Items.esc(l.toGroup().name()) + " gets ")
                + s().money(l.payout()));
        lore.add("<red>Closes it for good. " + l.effect());
        lore.add("<red>Can't be undone.");
        lore.add("<yellow>Shift-right-click twice · liquidate");
        m.set(40, Items.of(Material.TNT, "<dark_red>Liquidate", lore), e -> {
            if (!(e.isShiftClick() && e.isRightClick())) return;
            Frame.click(p);
            if (confirmed(p, "liq:" + c.ref().key())) {
                p.closeInventory();
                plugin.companies().liquidate(p, c);
            } else {
                Msg.send(p, "<red>Shift-right-click again within 5 seconds to liquidate <white><c></white> for good.", Msg.p("c", c.name()));
            }
        });
    }

    /** Slot 35: the parent company this one belongs to (open it), or how to put it into one. */
    private void groupItem(Menu m, Player p, Company c, Group gr, Runnable reopen) {
        if (!plugin.groups().enabled() || (c instanceof Manufacturer mf && mf.system())) return;
        if (gr != null) {
            long owes = plugin.groups().owes(c);
            m.set(35, Items.of(Material.GOLD_BLOCK, "<gold>Part of " + gr.styled(),
                    "<gray>Treasury: <white>" + s().money(gr.balance()),
                    owes > 0 ? "<gold>Owes it " + s().money(owes) + " (repaid from income)" : "<gray>No loan outstanding",
                    "<gray>Operations " + Math.round(plugin.groups().opsDiscount(c) * 100) + "% cheaper, extra bankruptcy grace.",
                    "<yellow>Click · open the parent company"), e -> {
                Frame.click(p);
                plugin.groupMenu().open(p, gr);
            });
            return;
        }
        List<Group> mine = plugin.groups().ownedBy(c.owner());
        List<String> lore = new ArrayList<>();
        lore.add("<gray>A parent company backs its companies");
        lore.add("<gray>with loans, cheaper operations and");
        lore.add("<gray>transfers at " + s().groups.transferFeePercent() + "%.");
        if (mine.isEmpty()) lore.add("<dark_gray>Start one: /company create <name>");
        else {
            lore.add("");
            lore.add("<yellow>Click · join " + Items.esc(mine.getFirst().name()) + (mine.size() > 1 ? " (or /company add)" : ""));
        }
        m.set(35, Items.of(Material.GOLD_NUGGET, "<white>Parent company: <gray>none", lore), e -> {
            if (mine.isEmpty()) return;
            Frame.click(p);
            String err = plugin.groups().add(p, mine.getFirst(), c);
            if (err != null) Msg.send(p, "<red>" + err);
            else Msg.send(p, "<green><c> is now part of <white><g></white>.", Msg.p("c", c.name()), Msg.p("g", mine.getFirst().name()));
            reopen.run();
        });
    }
}
