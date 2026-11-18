package dev.mccomm.cell.gui;

import dev.mccomm.cell.CellPlugin;
import dev.mccomm.cell.Settings;
import dev.mccomm.cell.economy.CompanyService;
import dev.mccomm.cell.model.Carrier;
import dev.mccomm.cell.model.Manufacturer;
import dev.mccomm.cell.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;

/** The "Company" page for carriers and manufacturers: valuation, money in/out, selling and liquidation. */
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

    private void click(Player p) {
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.6f);
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

    private Menu menu(String title) {
        return new Menu(4, Msg.mm("<dark_gray>" + title), "company").fill(Items.of(Material.BLACK_STAINED_GLASS_PANE, "<gray> "));
    }

    private List<String> valuationLore(CompanyService.Valuation v) {
        List<String> lore = new ArrayList<>();
        for (var e : v.parts().entrySet()) lore.add("<gray>" + Items.esc(e.getKey()) + ": <white>" + s().money(e.getValue()));
        lore.add("");
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

    private void askSale(Player p, boolean carrier, String id, String name, Runnable back) {
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

    // ================================================================ carrier

    public void carrier(Player p, Carrier c) {
        boolean owner = p.hasPermission("mccomm.admin") || c.owner().equals(p.getUniqueId());
        Menu m = menu(Items.esc(c.name()) + " · Company");
        CompanyService.Valuation v = plugin.companies().valuation(c);
        m.set(4, Items.glow(Items.of(Material.GOLD_BLOCK, "<gold><b>Valuation: " + s().money(v.total()), valuationLore(v))));
        double fee = s().transferFeePercent;
        long mine = plugin.store().profile(p.getUniqueId()).balance();
        if (owner) {
            m.set(10, Items.of(Material.HOPPER_MINECART, "<green>Withdraw to your wallet",
                    "<gray>Company wallet: <white>" + s().money(c.balance()), "<gray>Fee: <white>" + fee + "%", "<yellow>Click to choose an amount"), e -> {
                click(p);
                askAmount(p, "<aqua>Withdraw how many credits from <white>" + Items.esc(c.name()) + "</white>?", a -> {
                    plugin.companies().moveCarrier(p, c, a, true);
                    carrier(p, c);
                });
            });
            m.set(12, Items.of(Material.CHEST_MINECART, "<aqua>Add from your wallet",
                    "<gray>Your wallet: <white>" + s().money(mine), "<gray>Fee: <white>" + fee + "%", "<yellow>Click to choose an amount"), e -> {
                click(p);
                askAmount(p, "<aqua>Move how many credits into <white>" + Items.esc(c.name()) + "</white>?", a -> {
                    plugin.companies().moveCarrier(p, c, a, false);
                    carrier(p, c);
                });
            });
            m.set(14, Items.of(Material.WRITABLE_BOOK, "<white>Sell the company", "<gray>Offer it to another player for a price.",
                    "<gray>Everything stays as it is; they become owner.", "<yellow>Click to make an offer"), e -> {
                click(p);
                askSale(p, true, c.id(), c.name(), () -> carrier(p, c));
            });
            CompanyService.CarrierLiquidation l = plugin.companies().previewLiquidation(c);
            List<String> lore = new ArrayList<>();
            lore.add("<gray>Wallet: <white>" + s().money(l.wallet()));
            lore.add("<gray>Towers as scrap (" + Math.round(s().towerScrapPercent) + "%): <white>" + s().money(l.scrap()));
            lore.add("<gray>Unused stock (" + Math.round(s().stockRefundPercent) + "%): <white>" + s().money(l.stock()));
            if (l.staff() > 0) lore.add("<gray>Owed wages & severance: <red>-" + s().money(l.staff()));
            if (l.farewell() > 0) lore.add("<gray>Farewell to " + l.mvnos().size() + " MVNO(s): <red>-" + s().money(l.farewell()));
            lore.add("<gray>Fee (" + Math.round(s().liquidationFeePercent) + "%): <red>-" + s().money(l.fee()));
            lore.add("<gold>You'd receive: " + s().money(l.payout()));
            lore.add("");
            lore.add("<red>Towers come down, subscribers lose their SIMs,");
            lore.add("<red>debts to you are cancelled, and phones still being");
            lore.add("<red>paid off become paperweights. This can't be undone.");
            lore.add("");
            lore.add("<yellow>Shift-right-click twice to liquidate");
            m.set(16, Items.of(Material.TNT, "<dark_red>Liquidate", lore), e -> {
                if (!(e.isShiftClick() && e.isRightClick())) return;
                click(p);
                if (confirmed(p, "liq:c:" + c.id())) {
                    p.closeInventory();
                    plugin.companies().liquidate(p, c);
                } else {
                    Msg.send(p, "<red>Shift-right-click again within 5 seconds to liquidate <white><c></white> for good.", Msg.p("c", c.name()));
                }
            });
        } else {
            m.set(13, Items.of(Material.BARRIER, "<gray>Only the owner can move money, sell or liquidate."));
        }
        m.set(27, Items.of(Material.ARROW, "<gray>« Back"), e -> {
            click(p);
            plugin.carrierMenu().open(p, c);
        });
        m.open(p);
    }

    // =========================================================== manufacturer

    public void manufacturer(Player p, Manufacturer mf) {
        boolean owner = p.hasPermission("mccomm.admin") || p.getUniqueId().equals(mf.owner());
        Menu m = menu(Items.esc(mf.name()) + " · Company");
        CompanyService.Valuation v = plugin.companies().valuation(mf);
        m.set(4, Items.glow(Items.of(Material.GOLD_BLOCK, "<gold><b>Valuation: " + s().money(v.total()), valuationLore(v))));
        double fee = s().transferFeePercent;
        long mine = plugin.store().profile(p.getUniqueId()).balance();
        if (owner) {
            m.set(10, Items.of(Material.HOPPER_MINECART, "<green>Withdraw to your wallet",
                    "<gray>Company wallet: <white>" + s().money(mf.balance()), "<gray>Fee: <white>" + fee + "%", "<yellow>Click to choose an amount"), e -> {
                click(p);
                askAmount(p, "<aqua>Withdraw how many credits from <white>" + Items.esc(mf.name()) + "</white>?", a -> {
                    plugin.companies().moveManufacturer(p, mf, a, true);
                    manufacturer(p, mf);
                });
            });
            m.set(12, Items.of(Material.CHEST_MINECART, "<aqua>Add from your wallet",
                    "<gray>Your wallet: <white>" + s().money(mine), "<gray>Fee: <white>" + fee + "%", "<yellow>Click to choose an amount"), e -> {
                click(p);
                askAmount(p, "<aqua>Move how many credits into <white>" + Items.esc(mf.name()) + "</white>?", a -> {
                    plugin.companies().moveManufacturer(p, mf, a, false);
                    manufacturer(p, mf);
                });
            });
            m.set(14, Items.of(Material.WRITABLE_BOOK, "<white>Sell the company", "<gray>Offer it to another player for a price.",
                    "<gray>Research and models go with it.", "<yellow>Click to make an offer"), e -> {
                click(p);
                askSale(p, false, mf.id(), mf.name(), () -> manufacturer(p, mf));
            });
            CompanyService.ManufacturerLiquidation l = plugin.companies().previewLiquidation(mf);
            List<String> lore = new ArrayList<>();
            lore.add("<gray>Wallet: <white>" + s().money(l.wallet()));
            if (l.refunds() > 0) lore.add("<gray>Refunds for carriers' unsold stock: <red>-" + s().money(l.refunds()));
            lore.add("<gray>Fee (" + Math.round(s().liquidationFeePercent) + "%): <red>-" + s().money(l.fee()));
            lore.add("<gold>You'd receive: " + s().money(l.payout()));
            lore.add("");
            lore.add("<red>Models and research are lost. Installed towers keep");
            lore.add("<red>working, but stop paying you upkeep share.");
            lore.add("");
            lore.add("<yellow>Shift-right-click twice to liquidate");
            m.set(16, Items.of(Material.TNT, "<dark_red>Liquidate", lore), e -> {
                if (!(e.isShiftClick() && e.isRightClick())) return;
                click(p);
                if (confirmed(p, "liq:m:" + mf.id())) {
                    p.closeInventory();
                    plugin.companies().liquidate(p, mf);
                } else {
                    Msg.send(p, "<red>Shift-right-click again within 5 seconds to liquidate <white><m></white> for good.", Msg.p("m", mf.name()));
                }
            });
        } else {
            m.set(13, Items.of(Material.BARRIER, "<gray>Only the owner can move money, sell or liquidate."));
        }
        m.set(27, Items.of(Material.ARROW, "<gray>« Back"), e -> {
            click(p);
            plugin.manufacturerMenu().open(p, mf);
        });
        m.open(p);
    }
}
