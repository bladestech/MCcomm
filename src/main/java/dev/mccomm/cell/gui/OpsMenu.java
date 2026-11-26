package dev.mccomm.cell.gui;

import dev.mccomm.cell.CellPlugin;
import dev.mccomm.cell.broadband.Isp;
import dev.mccomm.cell.model.Carrier;
import dev.mccomm.cell.model.Company;
import dev.mccomm.cell.workforce.OpsPart;
import dev.mccomm.cell.workforce.OpsState;
import dev.mccomm.cell.workforce.WorkforceService;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The Operations tab of the carrier and ISP menus: the three budget parts (click to change funding), what they cost and
 * what they do. Slots: summary 13, parts 20/22/24, contractors 31 (carriers).
 */
public final class OpsMenu {
    private static final int[] SLOTS = {20, 22, 24};

    private final CellPlugin plugin;

    public OpsMenu(CellPlugin plugin) {
        this.plugin = plugin;
    }

    private WorkforceService wf() {
        return plugin.workforce();
    }

    private String money(double v) {
        return plugin.settings().money(Math.round(v));
    }

    /** "||||||||||" coloured by wear (green → red). */
    public static String wearBar(double wear) {
        int lit = (int) Math.ceil(Math.clamp(wear, 0, 100) / 10.0);
        String col = wear >= 80 ? "<#FF5C5C>" : wear >= 60 ? "<#FF9F43>" : wear >= 40 ? "<#FFD86B>" : "<#5EE38A>";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) sb.append(i < lit ? col : "<#3A3D45>").append('|');
        return sb.toString();
    }

    /** A 10-step bar for a funding level (1.0 = full; levels above 100% light up gold). */
    private static String fundBar(double f) {
        int lit = (int) Math.round(Math.clamp(f, 0, 1) * 10);
        String col = f >= 0.95 ? "<#5EE38A>" : f >= 0.6 ? "<#FFD86B>" : f >= 0.25 ? "<#FF9F43>" : "<#FF5C5C>";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) sb.append(i < lit ? col : "<#3A3D45>").append('|');
        if (f > 1.0001) sb.append(" <gold>+").append(Math.round((f - 1) * 100)).append('%');
        return sb.toString();
    }

    /** Fills the tab. {@code edit} is false for staff who may look but not change the budget. */
    public void fill(Menu m, Player p, Company c, boolean edit, Runnable reopen) {
        if (!wf().enabled()) {
            m.set(22, Frame.empty("Operations are off", "operations.enabled is false on this server."));
            return;
        }
        OpsState st = wf().state(c);
        List<String> sum = new ArrayList<>();
        sum.add("<gray>At your funding: <red>" + money(wf().dailyCost(c)) + "</red>/day");
        double full = 0;
        for (OpsPart part : OpsPart.values()) full += wf().fullCost(c, part) * wf().costFactor(c);
        sum.add("<gray>All parts at 100%: <white>" + money(full) + "</white>/day");
        if (st.transitDue() >= 1) sum.add("<gray>Transit used, not yet billed: <white>" + money(st.transitDue()));
        double disc = plugin.groups().opsDiscount(c);
        if (disc > 0) sum.add("<gold>Parent company discount: " + Math.round(disc * 100) + "%");
        sum.add("<gray>Paid since the last daily line: <white>" + money(st.paidToday()));
        sum.add("");
        sum.add("<dark_gray>Billed hourly. If the balance can't cover it");
        sum.add("<dark_gray>(after the next upkeep), it pays what it can");
        sum.add("<dark_gray>and service follows what was paid.");
        m.set(13, Items.of(Material.CLOCK, "<white>Operations budget", sum));
        for (int i = 0; i < SLOTS.length; i++) {
            OpsPart part = OpsPart.values()[i];
            m.set(SLOTS[i], partItem(c, st, part, edit), e -> {
                if (!edit) return;
                Frame.click(p);
                double step = e.isShiftClick() ? 0.5 : 0.1;
                double v = st.target(part) + (e.isRightClick() ? -step : step);
                st.target(part, Math.clamp(Math.round(v * 10) / 10.0, 0, part.max));
                plugin.store().markDirty();
                if (plugin.broadband() != null) plugin.broadband().store().markDirty();
                reopen.run();
            });
        }
        if (c instanceof Carrier car && !car.mvno()) {
            m.set(31, Items.of(st.contractors() ? Material.LIME_DYE : Material.GRAY_DYE, "<white>Contractors: " + (st.contractors() ? "<green>allowed" : "<red>never"),
                    "<gray>When field crews are below " + Math.round(wf().w().fieldMinFunding() * 100) + "% funded,",
                    "<gray>a contractor repairs worn towers after", "<gray>" + wf().w().contractorDelayHours() + " h for about " + Math.round(wf().w().contractorMultiplier()) + "× the cost.",
                    edit ? "<yellow>Click · toggle" : ""), e -> {
                if (!edit) return;
                Frame.click(p);
                st.contractors(!st.contractors());
                plugin.store().markDirty();
                reopen.run();
            });
        }
    }

    private ItemStack partItem(Company c, OpsState st, OpsPart part, boolean edit) {
        double full = wf().fullCost(c, part) * wf().costFactor(c);
        double avg = wf().funding(c, part);
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Funding: <white>" + WorkforceService.pct(st.target(part)) + " <gray>· costs <red>" + money(st.target(part) * full) + "</red>/day");
        lore.add("<gray>3-day average: " + fundBar(avg) + " <white>" + WorkforceService.pct(avg));
        if (Math.abs(st.lastPaid(part) - st.target(part)) > 0.01 && full > 0) {
            lore.add("<red>Last bill only paid " + WorkforceService.pct(st.lastPaid(part)) + ": the balance ran short.");
        }
        lore.add(full <= 0 ? "<dark_gray>Nothing to do yet (free)" : "<gray>At 100%: <white>" + money(full) + "</white>/day");
        lore.add("<dark_gray>" + basis(c, part));
        lore.add("");
        lore.addAll(effect(c, part, avg));
        if (edit) {
            lore.add("");
            lore.add("<yellow>Click +10% · Right-click −10%");
            lore.add("<yellow>Shift: ±50% · up to " + Math.round(part.max * 100) + "%");
        }
        return Items.of(part.icon, "<aqua>" + part.display + " <white>" + WorkforceService.pct(st.target(part)), lore);
    }

    /** What the full cost is made of. */
    private String basis(Company c, OpsPart part) {
        var w = wf().w();
        if (c instanceof Carrier) {
            return switch (part) {
                case FIELD -> money(w.fieldPerMacro()) + " per macro, " + money(w.fieldPerSmall()) + " per small cell, " + w.fieldPerSubscriber() + " per subscriber";
                case CARE -> w.carePerSubscriber() + " per subscriber beyond " + w.selfService();
                case NETWORK -> w.networkPerSubscriber() + " per network subscriber beyond " + w.selfService();
            };
        }
        return switch (part) {
            case FIELD -> w.ispFieldPerUpkeep() + "× node upkeep + " + w.ispFieldPerCustomer() + " per customer";
            case CARE -> w.ispCarePerCustomer() + " per customer beyond " + w.selfService();
            case NETWORK -> w.ispNetworkPerCustomer() + " per customer beyond " + w.selfService();
        };
    }

    private List<String> effect(Company c, OpsPart part, double f) {
        List<String> out = new ArrayList<>();
        if (c instanceof Carrier car) {
            switch (part) {
                case FIELD -> {
                    if (car.mvno()) out.add("<gray>Your host repairs the towers.");
                    else if (wf().crews(car)) {
                        double mins = wf().w().setOutMinutes() + wf().repairMinutes(wf().w().dispatchAt()) / wf().crewSpeed(car);
                        out.add("<green>Crews repair a worn tower in ~" + Math.round(mins) + " min");
                    } else out.add("<red>No crews: contractors only (" + wf().w().contractorDelayHours() + " h, ~"
                            + Math.round(wf().w().contractorMultiplier()) + "× cost)");
                }
                case CARE -> out.add("<gray>Support score in the market: <white>" + WorkforceService.pct(wf().support(car)));
                case NETWORK -> out.add(car.mvno() ? "<gray>Your host runs the network." : "<gray>Network speed: <white>" + WorkforceService.pct(wf().networkFactor(car)));
            }
        } else if (c instanceof Isp isp) {
            switch (part) {
                case FIELD -> out.add(isp.carrierBacked() ? "<gray>The carrier's crews look after its towers."
                        : "<gray>Unpaid faults fixed in ~<white>" + String.format("%.1f", plugin.broadband().autoRepairHours(isp)) + "</white> in-game h");
                case CARE -> out.add("<gray>Care in your rating: <white>" + WorkforceService.pct(Math.min(1, f)));
                case NETWORK -> out.add(isp.carrierBacked() ? "<gray>The carrier runs the network."
                        : "<gray>Backbone works at <white>" + WorkforceService.pct(plugin.broadband().networkFactor(isp)));
            }
        }
        return out;
    }
}
