package dev.mccomm.cell.gui;

import dev.mccomm.cell.CellPlugin;
import dev.mccomm.cell.economy.BillingService;
import dev.mccomm.cell.model.Carrier;
import dev.mccomm.cell.model.Tower;
import dev.mccomm.cell.util.Msg;
import dev.mccomm.cell.workforce.Employee;
import dev.mccomm.cell.workforce.Facility;
import dev.mccomm.cell.workforce.RepairJob;
import dev.mccomm.cell.workforce.StaffState;
import dev.mccomm.cell.workforce.WorkforceService;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Carrier menu → Operations: stations, technicians, hiring and repairs. */
public final class OperationsMenu {
    private final CellPlugin plugin;
    private final Map<UUID, Long> confirm = new HashMap<>();

    public OperationsMenu(CellPlugin plugin) {
        this.plugin = plugin;
    }

    private WorkforceService wf() {
        return plugin.workforce();
    }

    private String money(long v) {
        return plugin.settings().money(v);
    }

    private void click(Player p) {
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.6f);
    }

    private Menu menu(int rows, String title) {
        return new Menu(rows, Msg.mm("<dark_gray>" + title), "operations").fill(Items.of(Material.BLACK_STAINED_GLASS_PANE, "<gray> "));
    }

    // ================================================================ overview

    public void open(Player p, Carrier c) {
        if (!plugin.carrierMenu().canView(p, c)) return;
        long now = System.currentTimeMillis();
        Menu m = menu(6, Items.esc(c.name()) + " · Operations");
        StaffState st = wf().state(c.id());
        List<Facility> stations = wf().facilitiesOf(c.id());
        List<Employee> staff = wf().staffOf(c.id());

        // Summary
        List<String> sum = new ArrayList<>();
        sum.add("<gray>Technicians: <white>" + staff.size() + " <dark_gray>(" + Math.max(0, wf().freeBeds(c.id())) + " free beds)");
        sum.add("<gray>Stations: <white>" + stations.size());
        sum.add("<gray>Wages & rent: <red>" + money(wf().dailyCost(c.id())) + "</red> /day");
        sum.add("<gray>Next payday in <white>" + (st.nextPayday() == 0 ? "-" : BillingService.until(st.nextPayday() - now)));
        if (st.wagesOwed() > 0) sum.add("<red>Owed: " + money(st.wagesOwed()) + " <gray>(staff stopped working)");
        m.set(4, Items.of(Material.IRON_PICKAXE, "<gold><b>Field operations", sum));

        // Towers by wear
        List<Tower> towers = new ArrayList<>(plugin.store().towersOf(c.id()));
        towers.sort(Comparator.comparingDouble((Tower x) -> x.wear()).reversed());
        int slot = 9;
        for (Tower t : towers) {
            if (slot > 26) break;
            m.set(slot++, towerItem(t, now));
        }
        if (towers.isEmpty()) m.set(13, Items.of(Material.LIGHT_GRAY_DYE, "<gray>No towers yet"));

        // Stations
        slot = 27;
        for (Facility f : stations) {
            if (slot > 33) break;
            Facility fx = f;
            m.set(slot++, stationItem(f, now, true), e -> {
                click(p);
                station(p, c, fx);
            });
        }
        if (stations.isEmpty()) {
            m.set(27, Items.of(Material.WHITE_BED, "<white>No maintenance stations",
                    "<gray>Build a closed, roofed room with",
                    "<gray>one bed per technician (barrels",
                    "<gray>for spare parts), put a wall sign",
                    "<gray>inside and look at it:",
                    "<white>/facility create station <name>"));
        }

        // Hiring
        List<WorkforceService.Candidate> cands = wf().candidates(c.id(), now);
        slot = 36;
        for (WorkforceService.Candidate cand : cands) {
            long fee = wf().recruitFee(cand.wage());
            m.set(slot++, Items.of(Material.PLAYER_HEAD, "<aqua>" + Items.esc(cand.name()),
                    "<gray>" + Employee.LEVELS[cand.level()] + " field technician",
                    "<gray>Wage: <white>" + money(cand.wage()) + "</white> /day <dark_gray>(market " + money(wf().w().wage(cand.level())) + ")",
                    "<gray>Repair speed: <white>" + Math.round(100 / wf().w().levelSpeed()[cand.level()]) + "%",
                    "<gray>Recruitment fee: <white>" + money(fee),
                    "",
                    "<yellow>Click to hire"), e -> {
                click(p);
                String err = wf().hire(c, cand, System.currentTimeMillis());
                if (err != null) Msg.send(p, "<red>" + Msg.escape(err));
                else Msg.send(p, "<green>Hired <white><n></white>.", Msg.p("n", cand.name()));
                open(p, c);
            });
        }
        if (cands.isEmpty()) m.set(36, Items.of(Material.GRAY_DYE, "<gray>No more candidates today", "<dark_gray>New job seekers tomorrow."));
        m.set(40, Items.of(Material.BOOK, "<white>Job seekers", "<gray>A few new candidates every day.",
                "<gray>Hiring costs a recruitment fee of", "<white>" + wf().w().recruitDays() + " days</white> <gray>of their wage; firing",
                "<gray>costs <white>" + wf().w().severanceDays() + " days</white> <gray>severance."));

        // Staff list
        m.set(42, Items.of(Material.WRITABLE_BOOK, "<white>Technicians (" + staff.size() + ")",
                "<gray>See who's where, what they're", "<gray>doing, and fire people.", "<yellow>Click to open"), e -> {
            click(p);
            staff(p, c);
        });

        boolean ctr = st.contractors();
        m.set(44, Items.of(ctr ? Material.LIME_DYE : Material.GRAY_DYE, "<white>Contractors: " + (ctr ? "<green>allowed" : "<red>never"),
                "<gray>When no technician can take a job", "<gray>within <white>" + wf().w().contractorAfterMinutes() + " min</white>, a contractor is",
                "<gray>booked: arrives in <white>" + wf().w().contractorDelayHours() + " h</white>, costs about <white>" + Math.round(wf().w().contractorMultiplier()) + "×</white>",
                "<gray>a technician (at least " + money(wf().w().contractorMin()) + ").",
                "", "<yellow>Click to toggle"), e -> {
            click(p);
            st.contractors(!st.contractors());
            plugin.store().markDirty();
            open(p, c);
        });

        m.set(45, Items.of(Material.ARROW, "<gray>« Back"), e -> {
            click(p);
            plugin.carrierMenu().open(p, c);
        });
        m.set(49, Items.of(Material.BARRIER, "<red>Close"), e -> p.closeInventory());
        m.open(p);
    }

    private org.bukkit.inventory.ItemStack towerItem(Tower t, long now) {
        int stage = wf().wearStage(t);
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Wear: " + wearBar(t.wear()) + " <white>" + Math.round(t.wear()) + "%");
        lore.add("<gray>Wearing <white>" + String.format("%.1f", wf().wearPerDay(t)) + "</white>/day at current traffic");
        lore.add(switch (stage) {
            case 0 -> "<green>Running normally";
            case 1 -> "<yellow>Slowed to " + Math.round(wf().wearSpeed(t) * 100) + "%";
            case 2, 3 -> "<gold>" + Math.round(wf().wearSpeed(t) * 100) + "% speed, " + wf().wearDroppedBands(t) + " band(s) off";
            default -> "<dark_red>Silent: worn out";
        });
        RepairJob j = wf().job(t);
        if (j != null) {
            lore.add("");
            if (!j.started()) lore.add("<yellow>Repair queued " + BillingService.until(now - j.queuedAt()) + " ago");
            else if (j.contractor()) lore.add("<aqua>Contractor: done in " + BillingService.until(j.repairAt() - now));
            else {
                Employee e = wf().employees().stream().filter(x -> x.id() == j.technicianId()).findFirst().orElse(null);
                lore.add("<aqua>" + (e == null ? "Technician" : Items.esc(e.name())) + (wf().onSite(t) ? " is on site" : " is on the way"));
                lore.add("<aqua>Done in " + BillingService.until(j.repairAt() - now));
            }
        }
        Material icon = stage >= 4 ? Material.IRON_BARS : stage >= 2 ? Material.REDSTONE_TORCH : Material.LIGHTNING_ROD;
        return Items.of(icon, "<gold>" + Items.esc(t.name()), lore);
    }

    public static String wearBar(double wear) {
        int lit = (int) Math.ceil(Math.clamp(wear, 0, 100) / 10.0);
        String col = wear >= 80 ? "<#FF5C5C>" : wear >= 60 ? "<#FF9F43>" : wear >= 40 ? "<#FFD86B>" : "<#5EE38A>";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) sb.append(i < lit ? col : "<#3A3D45>").append('|');
        return sb.toString();
    }

    private org.bukkit.inventory.ItemStack stationItem(Facility f, long now, boolean clickable) {
        List<Employee> here = wf().staffAt(f);
        int busy = 0;
        for (Employee e : here) if (!wf().free(e, now)) busy++;
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Technicians: <white>" + here.size() + "/" + f.capacity() + " <dark_gray>(" + busy + " out)");
        lore.add("<gray>Spare parts: <white>" + f.barrels() + " barrel(s)");
        lore.add("<gray>Rent: <white>" + money(wf().rent(f)) + "</white> /day");
        lore.add("<gray>Location: <white>" + (f.world() == null ? "?" : f.world().getName()) + " " + f.x() + ", " + f.y() + ", " + f.z());
        lore.add("<gray>Covers towers within <white>" + wf().w().serviceRadius() + "</white> blocks");
        if (clickable) {
            lore.add("");
            lore.add("<yellow>Click for details");
        }
        return Items.of(Material.RED_BED, "<white>" + Items.esc(f.name()), lore);
    }

    // ================================================================ station

    public void station(Player p, Carrier c, Facility f) {
        if (!plugin.carrierMenu().canView(p, c)) return;
        long now = System.currentTimeMillis();
        Menu m = menu(4, Items.esc(f.name()) + " · Station");
        m.set(4, stationItem(f, now, false));
        int slot = 9;
        for (Employee e : wf().staffAt(f)) {
            if (slot > 26) break;
            m.set(slot++, employeeItem(e, now));
        }
        int towersInReach = 0;
        for (Tower t : plugin.store().towersOf(c.id())) {
            double d = f.distanceTo(t.worldId(), t.x(), t.z());
            if (d >= 0 && d <= wf().w().serviceRadius()) towersInReach++;
        }
        m.set(31, Items.of(Material.COMPASS, "<white>Reach",
                "<gray>Towers in range: <white>" + towersInReach + "/" + plugin.store().towersOf(c.id()).size(),
                "<gray>Travel: <white>" + Math.round(wf().w().blocksPerMinute()) + "</white> blocks per minute"));
        m.set(33, Items.of(Material.SPYGLASS, "<white>Re-count beds and barrels", "<yellow>Click to check the room now"), e -> {
            click(p);
            String err = wf().recheck(f);
            if (err != null) Msg.send(p, "<red>" + Msg.escape(err));
            if (wf().facility(f.id()) != null) station(p, c, f);
            else p.closeInventory();
        });
        m.set(27, Items.of(Material.ARROW, "<gray>« Operations"), e -> {
            click(p);
            open(p, c);
        });
        m.open(p);
    }

    private org.bukkit.inventory.ItemStack employeeItem(Employee e, long now) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + e.levelName() + " " + e.role().display.toLowerCase());
        lore.add("<gray>Wage: <white>" + money(e.wage()) + "</white> /day");
        lore.add("<gray>Repairs done: <white>" + e.jobsDone());
        Facility f = wf().facility(e.facilityId());
        lore.add("<gray>Station: <white>" + (f == null ? "<red>none (idle)" : Items.esc(f.name())));
        if (e.unpaidDays() > 0) lore.add("<red>Unpaid for " + e.unpaidDays() + " day(s): not working");
        else if (e.busyUntil() > now) {
            Tower t = e.jobTower() == 0 ? null : plugin.store().tower(e.jobTower());
            lore.add(t != null ? "<aqua>Repairing " + Items.esc(t.name()) : "<aqua>Driving back");
            lore.add("<aqua>Free in " + BillingService.until(e.busyUntil() - now));
        } else lore.add("<green>Available");
        return Items.of(e.unpaidDays() > 0 ? Material.SKELETON_SKULL : Material.PLAYER_HEAD, "<white>" + Items.esc(e.name()), lore);
    }

    // ================================================================== staff

    public void staff(Player p, Carrier c) {
        if (!plugin.carrierMenu().canView(p, c)) return;
        long now = System.currentTimeMillis();
        Menu m = menu(6, Items.esc(c.name()) + " · Technicians");
        int slot = 0;
        for (Employee e : wf().staffOf(c.id())) {
            if (slot >= 45) break;
            var item = employeeItem(e, now);
            long sev = wf().severance(e);
            item.editMeta(meta -> {
                List<net.kyori.adventure.text.Component> lore = new ArrayList<>(meta.lore() == null ? List.of() : meta.lore());
                lore.add(Msg.item(""));
                lore.add(Msg.item("<red>Shift-right-click <gray>to fire (" + money(sev) + " severance)"));
                meta.lore(lore);
            });
            m.set(slot++, item, ev -> {
                if (!ev.isShiftClick() || !ev.isRightClick()) return;
                Long t = confirm.get(p.getUniqueId());
                if (t == null || System.currentTimeMillis() - t > 5000) {
                    confirm.put(p.getUniqueId(), System.currentTimeMillis());
                    Msg.send(p, "<yellow>Shift-right-click again within 5 s to fire <white><n></white> (<white><s></white> severance).",
                            Msg.p("n", e.name()), Msg.p("s", money(sev)));
                    return;
                }
                confirm.remove(p.getUniqueId());
                String err = wf().fire(c, e);
                if (err != null) Msg.send(p, "<red>" + Msg.escape(err));
                else Msg.send(p, "<yellow>Fired <white><n></white>.", Msg.p("n", e.name()));
                staff(p, c);
            });
        }
        if (slot == 0) m.set(22, Items.of(Material.LIGHT_GRAY_DYE, "<gray>No technicians yet", "<dark_gray>Hire them in Operations."));
        m.set(45, Items.of(Material.ARROW, "<gray>« Operations"), e -> {
            click(p);
            open(p, c);
        });
        m.open(p);
    }
}
