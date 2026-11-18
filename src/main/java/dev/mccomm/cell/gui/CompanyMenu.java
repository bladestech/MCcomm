package dev.mccomm.cell.gui;

import dev.mccomm.cell.CellPlugin;
import dev.mccomm.cell.Settings;
import dev.mccomm.cell.economy.CompanyService;
import dev.mccomm.cell.model.Carrier;
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
 * Company actions (valuation, money in and out, selling, liquidation) shown on the Money tab of
 * the carrier and manufacturer menus. Slots: valuation 13, withdraw 29, add 31, sell 33, liquidate 40.
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

    private List<String> valuationLore(CompanyService.Valuation v) {
        List<String> lore = new ArrayList<>();
        for (var e : v.parts().entrySet()) lore.add("<gray>" + Items.esc(e.getKey()) + ": <white>" + s().money(e.getValue()));
        lore.add("<dark_gray>A guide for setting a sale price.");
        return lore;
    }

    private void askAmount(Player p, String question, LongConsumer ok) {
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

    private void askSale(Player p, boolean carrier, String id, String name) {
        plugin.prompt().ask(p, "<aqua>Sell <white>" + Items.esc(name) + "</white> to which online player?", buyerName -> {
            Player buyer = Bukkit.getPlayerExact(buyerName);
            if (buyer == null) {
                Msg.send(p, "<red>That player isn't online.");
                return;
            }
            plugin.prompt().ask(p, "<aqua>Price for <white>" + Items.esc(name) + "</white> (credits, paid from their wallet to yours):", in -> {
                try {
                    long price = Long.parseLong(in.replace(",", "").trim());
                    plugin.companies().offerSale(p, carrier, id, name, buyer, price);
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

    private void moveItems(Menu m, Player p, String name, long companyBalance, boolean owner,
                           java.util.function.BiConsumer<Long, Boolean> move, Runnable reopen) {
        double fee = s().transferFeePercent;
        long mine = plugin.store().profile(p.getUniqueId()).balance();
        if (!owner) {
            m.set(31, Items.of(Material.BARRIER, "<gray>Only the owner can move money,", "<gray>sell or liquidate."));
            return;
        }
        m.set(29, Items.of(Material.HOPPER_MINECART, "<green>Withdraw to your wallet",
                "<gray>Company: <white>" + s().money(companyBalance) + " <gray>· fee " + fee + "%", "<yellow>Click · choose an amount"), e -> {
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

    /** Fills the carrier Money tab's company items. */
    public void carrierItems(Menu m, Player p, Carrier c, Runnable reopen) {
        boolean owner = p.hasPermission("mccomm.admin") || c.owner().equals(p.getUniqueId());
        CompanyService.Valuation v = plugin.companies().valuation(c);
        m.set(13, Items.glow(Items.of(Material.GOLD_BLOCK, "<gold><b>Worth " + s().money(v.total()), valuationLore(v))));
        moveItems(m, p, c.name(), c.balance(), owner, (a, out) -> plugin.companies().moveCarrier(p, c, a, out), reopen);
        if (!owner) return;
        m.set(33, Items.of(Material.WRITABLE_BOOK, "<white>Sell the company", "<gray>Offer it to an online player.",
                "<yellow>Click · make an offer"), e -> {
            Frame.click(p);
            askSale(p, true, c.id(), c.name());
        });
        CompanyService.CarrierLiquidation l = plugin.companies().previewLiquidation(c);
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Wallet " + s().money(l.wallet()) + " + scrap " + s().money(l.scrap()) + " + stock " + s().money(l.stock()));
        if (l.staff() > 0) lore.add("<gray>Owed wages and severance: <red>-" + s().money(l.staff()));
        if (l.farewell() > 0) lore.add("<gray>MVNO farewell: <red>-" + s().money(l.farewell()));
        lore.add("<gray>Fee: <red>-" + s().money(l.fee()) + " <gold>· You'd get " + s().money(l.payout()));
        lore.add("<red>Closes it for good: towers come down,");
        lore.add("<red>subscribers lose their SIMs. Can't be undone.");
        lore.add("<yellow>Shift-right-click twice · liquidate");
        m.set(40, Items.of(Material.TNT, "<dark_red>Liquidate", lore), e -> {
            if (!(e.isShiftClick() && e.isRightClick())) return;
            Frame.click(p);
            if (confirmed(p, "liq:c:" + c.id())) {
                p.closeInventory();
                plugin.companies().liquidate(p, c);
            } else {
                Msg.send(p, "<red>Shift-right-click again within 5 seconds to liquidate <white><c></white> for good.", Msg.p("c", c.name()));
            }
        });
    }

    /** Fills the manufacturer Money tab's company items. */
    public void manufacturerItems(Menu m, Player p, Manufacturer mf, Runnable reopen) {
        boolean owner = p.hasPermission("mccomm.admin") || p.getUniqueId().equals(mf.owner());
        CompanyService.Valuation v = plugin.companies().valuation(mf);
        m.set(13, Items.glow(Items.of(Material.GOLD_BLOCK, "<gold><b>Worth " + s().money(v.total()), valuationLore(v))));
        moveItems(m, p, mf.name(), mf.balance(), owner, (a, out) -> plugin.companies().moveManufacturer(p, mf, a, out), reopen);
        if (!owner) return;
        m.set(33, Items.of(Material.WRITABLE_BOOK, "<white>Sell the company", "<gray>Offer it to an online player.",
                "<gray>Research and models go with it.", "<yellow>Click · make an offer"), e -> {
            Frame.click(p);
            askSale(p, false, mf.id(), mf.name());
        });
        CompanyService.ManufacturerLiquidation l = plugin.companies().previewLiquidation(mf);
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Wallet: <white>" + s().money(l.wallet()));
        if (l.refunds() > 0) lore.add("<gray>Refunds for carriers' unsold stock: <red>-" + s().money(l.refunds()));
        lore.add("<gray>Fee: <red>-" + s().money(l.fee()) + " <gold>· You'd get " + s().money(l.payout()));
        lore.add("<red>Models and research are lost. Can't be undone.");
        lore.add("<yellow>Shift-right-click twice · liquidate");
        m.set(40, Items.of(Material.TNT, "<dark_red>Liquidate", lore), e -> {
            if (!(e.isShiftClick() && e.isRightClick())) return;
            Frame.click(p);
            if (confirmed(p, "liq:m:" + mf.id())) {
                p.closeInventory();
                plugin.companies().liquidate(p, mf);
            } else {
                Msg.send(p, "<red>Shift-right-click again within 5 seconds to liquidate <white><m></white> for good.", Msg.p("m", mf.name()));
            }
        });
    }
}
