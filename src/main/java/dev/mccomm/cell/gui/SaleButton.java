package dev.mccomm.cell.gui;

import dev.mccomm.cell.CellPlugin;
import dev.mccomm.cell.economy.BillingService;
import dev.mccomm.cell.model.Company;
import dev.mccomm.cell.model.Sale;
import dev.mccomm.cell.util.Msg;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** The "Sale" button of the carrier and ISP plan editors. */
public final class SaleButton {
    private SaleButton() {
    }

    public static void set(CellPlugin plugin, Menu m, int slot, Player p, Company co, String planName, long price, Sale sale,
                           boolean edit, Runnable reopen) {
        var cfg = plugin.settings().sales;
        long now = System.currentTimeMillis();
        List<String> lore = new ArrayList<>();
        boolean on = sale.on(now);
        if (on) {
            lore.add("<green>" + sale.percent() + "% off: <st>" + plugin.settings().money(price) + "</st> → "
                    + plugin.settings().money(sale.apply(price, now)));
            lore.add("<gray>Ends in <white>" + BillingService.until(sale.until() - now));
            lore.add("<gray>The price is locked until it ends.");
        } else {
            lore.add("<gray>A temporary discount (" + cfg.minPercent() + "-" + cfg.maxPercent() + "%) for up to " + BillingService.until(cfg.maxHours() * 3_600_000L) + ".");
            lore.add("<gray>Sign-ups and renewals pay the sale price.");
            String why = plugin.sales().problem(sale, cfg.minPercent(), 1);
            if (why != null) lore.add("<red>" + why);
        }
        int days = plugin.sales().daysOnSale(co, now);
        lore.add("<dark_gray>Sale days in the last " + cfg.windowDays() + ": " + days + " · allowed " + cfg.allowedDays()
                + " (then regulator fines)");
        if (edit) lore.add(on ? "<yellow>Shift-right-click · end the sale" : "<yellow>Click · start a sale");
        m.set(slot, Items.of(on ? Material.EMERALD : Material.NAME_TAG, on ? "<green><b>On sale" : "<white>Sale", lore), e -> {
            if (!edit) return;
            Frame.click(p);
            if (sale.on(System.currentTimeMillis())) {
                if (e.isShiftClick() && e.isRightClick()) {
                    plugin.sales().end(p, planName, sale);
                    reopen.run();
                }
                return;
            }
            plugin.prompt().ask(p, "<aqua>How many percent off (" + cfg.minPercent() + "-" + cfg.maxPercent() + ")?", in -> {
                int pct;
                try {
                    pct = Integer.parseInt(in.replace("%", "").trim());
                } catch (NumberFormatException ex) {
                    Msg.send(p, "<red>Enter a whole number.");
                    return;
                }
                plugin.prompt().ask(p, "<aqua>For how many hours (1-" + cfg.maxHours() + ", e.g. 48 = two days)?", in2 -> {
                    try {
                        int hours = Integer.parseInt(in2.trim());
                        plugin.sales().start(p, co, planName, sale, pct, hours);
                        plugin.store().markDirty();
                        if (plugin.broadband() != null) plugin.broadband().store().markDirty();
                    } catch (NumberFormatException ex) {
                        Msg.send(p, "<red>Enter a whole number of hours.");
                        return;
                    }
                    reopen.run();
                });
            });
        });
    }
}
