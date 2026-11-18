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
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Carrier menu → Operations: tower wear and repairs, facilities, and staff by role. */
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

    private static Material icon(Employee.Role r) {
        return switch (r) {
            case TECHNICIAN -> Material.IRON_PICKAXE;
            case SUPPORT -> Material.BELL;
            case ENGINEER -> Material.COMPARATOR;
        };
    }

    private static Material icon(Facility.Type t) {
        return switch (t) {
            case STATION -> Material.RED_BED;
            case CALL_CENTRE -> Material.LECTERN;
            case NOC -> Material.OBSERVER;
        };
    }

    // ================================================================ overview

    public void open(Player p, Carrier c) {
        if (!plugin.carrierMenu().canView(p, c)) return;
        long now = System.currentTimeMillis();
        Menu m = menu(6, Items.esc(c.name()) + " · Operations");
        StaffState st = wf().state(c.id());

        List<String> sum = new ArrayList<>();
        sum.add("<gray>Staff: <white>" + wf().staffOf(c.id()).size() + " <gray>· Sites: <white>" + wf().facilitiesOf(c.id()).size());
        sum.add("<gray>Wages, rent & transit: <red>" + money(wf().dailyCost(c.id())) + "</red> /day");
        sum.add("<gray>Next payday in <white>" + (st.nextPayday() == 0 ? "-" : BillingService.until(st.nextPayday() - now)));
        if (st.wagesOwed() > 0) sum.add("<red>Owed: " + money(st.wagesOwed()) + " <gray>(unpaid staff don't work)");
        m.set(4, Items.of(Material.CLOCK, "<gold><b>Operations", sum));

        // Towers, most worn first (one row).
        List<Tower> towers = new ArrayList<>(plugin.store().towersOf(c.id()));
        towers.sort(Comparator.comparingDouble((Tower x) -> x.wear()).reversed());
        int slot = 9;
        for (Tower t : towers) {
            if (slot > 17) break;
            m.set(slot++, towerItem(t, now));
        }
        if (towers.isEmpty()) m.set(13, Items.of(Material.LIGHT_GRAY_DYE, "<gray>No towers yet"));

        // Facilities (one row).
        slot = 19;
        for (Facility f : wf().facilitiesOf(c.id())) {
            if (slot > 25) break;
            m.set(slot++, facilityItem(f, now, true), e -> {
                click(p);
                facility(p, c, f);
            });
        }
        if (slot == 19) {
            m.set(22, Items.of(Material.OAK_SIGN, "<white>No facilities yet",
                    "<gray>Build a closed, roofed room, put a",
                    "<gray>wall sign inside and look at it:",
                    "<white>/facility create station|callcentre|noc <name>",
                    "",
                    "<gray>Station: one bed per technician",
                    "<gray>Call centre: seat (stairs) + lectern per agent",
                    "<gray>NOC: seat (stairs) + observer per engineer"));
        }

        // Staff by role.
        int[] roleSlots = {29, 31, 33};
        Employee.Role[] roles = Employee.Role.values();
        for (int i = 0; i < roles.length; i++) {
            Employee.Role r = roles[i];
            m.set(roleSlots[i], roleItem(c, r, now, true), e -> {
                click(p);
                role(p, c, r);
            });
        }

        boolean ctr = st.contractors();
        m.set(40, Items.of(ctr ? Material.LIME_DYE : Material.GRAY_DYE, "<white>Contractors: " + (ctr ? "<green>allowed" : "<red>never"),
                "<gray>When no technician can take a repair", "<gray>within <white>" + wf().w().contractorAfterMinutes() + " min</white>, a contractor comes",
                "<gray>after <white>" + wf().w().contractorDelayHours() + " h</white> for about <white>" + Math.round(wf().w().contractorMultiplier()) + "×</white> the cost.",
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

    private ItemStack roleItem(Carrier c, Employee.Role r, long now, boolean clickable) {
        List<Employee> staff = wf().staffOf(c.id(), r);
        int working = 0;
        for (Employee e : staff) if (e.working()) working++;
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Staff: <white>" + staff.size() + (working < staff.size() ? " <red>(" + (staff.size() - working) + " not working)" : ""));
        lore.add("<gray>Room: <white>" + Math.max(0, wf().freeSlots(c.id(), r.facility())) + "</white> free in " + r.facility().display.toLowerCase() + "s");
        switch (r) {
            case TECHNICIAN -> lore.add("<gray>Repair worn towers from stations.");
            case SUPPORT, ENGINEER -> {
                double load = wf().load(c, r);
                int need = wf().needed(c, r);
                lore.add("<gray>Needed now: <white>~" + need + " <dark_gray>(1 per " + wf().w().covers(r) + " subscribers, first "
                        + wf().w().selfService() + " free)");
                String col = load <= 1 ? "<green>" : load <= 1.5 ? "<yellow>" : "<red>";
                lore.add("<gray>Workload: " + col + (Double.isInfinite(load) ? "no staff" : Math.round(load * 100) + "%"));
                lore.add(r == Employee.Role.SUPPORT ? "<gray>Understaffed: unhappy customers leave."
                        : "<gray>Understaffed: the whole network slows down" + (wf().engineerSpeed(c) < 1
                        ? " <red>(now " + Math.round(wf().engineerSpeed(c) * 100) + "%)" : "") + ".");
            }
        }
        if (clickable) {
            lore.add("");
            lore.add("<yellow>Click to hire and manage");
        }
        return Items.of(icon(r), "<white>" + r.display + " <dark_gray>(" + staff.size() + ")", lore);
    }

    private ItemStack towerItem(Tower t, long now) {
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

    private ItemStack facilityItem(Facility f, long now, boolean clickable) {
        List<Employee> here = wf().staffAt(f);
        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + f.type().display);
        lore.add("<gray>Staff: <white>" + here.size() + "/" + f.capacity());
        if (f.type() == Facility.Type.STATION) {
            int busy = 0;
            for (Employee e : here) if (!wf().free(e, now)) busy++;
            lore.add("<gray>Out on jobs: <white>" + busy);
            lore.add("<gray>Spare parts: <white>" + f.barrels() + " barrel(s)");
            lore.add("<gray>Covers towers within <white>" + wf().w().serviceRadius() + "</white> blocks");
        }
        lore.add("<gray>Rent: <white>" + money(wf().rent(f)) + "</white> /day");
        lore.add("<gray>At <white>" + (f.world() == null ? "?" : f.world().getName()) + " " + f.x() + ", " + f.y() + ", " + f.z());
        if (clickable) {
            lore.add("");
            lore.add("<yellow>Click for details");
        }
        return Items.of(icon(f.type()), "<white>" + Items.esc(f.name()), lore);
    }

    // ================================================================ facility

    /** One facility: who works there, plus (for stations) reach. Also opened by right-clicking its sign. */
    public void station(Player p, Carrier c, Facility f) {
        facility(p, c, f);
    }

    public void facility(Player p, Carrier c, Facility f) {
        if (!plugin.carrierMenu().canView(p, c)) return;
        long now = System.currentTimeMillis();
        Menu m = menu(4, Items.esc(f.name()) + " · " + f.type().display);
        m.set(4, facilityItem(f, now, false));
        int slot = 9;
        for (Employee e : wf().staffAt(f)) {
            if (slot > 26) break;
            m.set(slot++, employeeItem(e, now));
        }
        if (f.type() == Facility.Type.STATION) {
            int inReach = 0;
            for (Tower t : plugin.store().towersOf(c.id())) {
                double d = f.distanceTo(t.worldId(), t.x(), t.z());
                if (d >= 0 && d <= wf().w().serviceRadius()) inReach++;
            }
            m.set(31, Items.of(Material.COMPASS, "<white>Reach",
                    "<gray>Towers in range: <white>" + inReach + "/" + plugin.store().towersOf(c.id()).size(),
                    "<gray>Travel: <white>" + Math.round(wf().w().blocksPerMinute()) + "</white> blocks per minute"));
        }
        m.set(33, Items.of(Material.SPYGLASS, "<white>Re-count the room", "<gray>Needs one " + f.type().needs + ".", "<yellow>Click to check now"), e -> {
            click(p);
            String err = wf().recheck(f);
            if (err != null) Msg.send(p, "<red>" + Msg.escape(err));
            if (wf().facility(f.id()) != null) facility(p, c, f);
            else p.closeInventory();
        });
        m.set(27, Items.of(Material.ARROW, "<gray>« Operations"), e -> {
            click(p);
            open(p, c);
        });
        m.open(p);
    }

    private ItemStack employeeItem(Employee e, long now) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + e.levelName() + " " + e.role().display.toLowerCase());
        lore.add("<gray>Wage: <white>" + money(e.wage()) + "</white> /day");
        Facility f = wf().facility(e.facilityId());
        lore.add("<gray>Works at: <white>" + (f == null ? "<red>nowhere (idle)" : Items.esc(f.name())));
        if (e.role() == Employee.Role.TECHNICIAN) lore.add("<gray>Repairs done: <white>" + e.jobsDone());
        if (e.unpaidDays() > 0) lore.add("<red>Unpaid for " + e.unpaidDays() + " day(s): not working");
        else if (f == null) lore.add("<red>Idle: no room for them");
        else if (e.busyUntil() > now) {
            Tower t = e.jobTower() == 0 ? null : plugin.store().tower(e.jobTower());
            lore.add(t != null ? "<aqua>Repairing " + Items.esc(t.name()) : "<aqua>Driving back");
            lore.add("<aqua>Free in " + BillingService.until(e.busyUntil() - now));
        } else lore.add("<green>Working");
        return Items.of(e.unpaidDays() > 0 || f == null ? Material.SKELETON_SKULL : Material.PLAYER_HEAD, "<white>" + Items.esc(e.name()), lore);
    }

    // ==================================================================== role

    /** Hire and manage one role: today's candidates and the current staff (shift-right-click twice to fire). */
    public void role(Player p, Carrier c, Employee.Role r) {
        if (!plugin.carrierMenu().canView(p, c)) return;
        long now = System.currentTimeMillis();
        Menu m = menu(6, Items.esc(c.name()) + " · " + r.display);
        m.set(4, roleItem(c, r, now, false));

        List<WorkforceService.Candidate> cands = wf().candidates(c.id(), r, now);
        int[] candSlots = {11, 13, 15};
        for (int i = 0; i < cands.size() && i < candSlots.length; i++) {
            WorkforceService.Candidate cand = cands.get(i);
            List<String> lore = new ArrayList<>();
            lore.add("<gray>" + Employee.LEVELS[cand.level()] + " " + r.display.toLowerCase());
            lore.add("<gray>Wage: <white>" + money(cand.wage()) + "</white> /day <dark_gray>(market " + money(wf().w().wage(r, cand.level())) + ")");
            if (r == Employee.Role.TECHNICIAN) lore.add("<gray>Repair speed: <white>" + Math.round(100 / wf().w().levelSpeed()[cand.level()]) + "%");
            else lore.add("<gray>Covers: <white>~" + Math.round(wf().w().covers(r) * wf().w().levelProductivity()[cand.level()]) + "</white> subscribers");
            lore.add("<gray>Recruitment fee: <white>" + money(wf().recruitFee(cand.wage())));
            lore.add("");
            lore.add("<yellow>Click to hire");
            m.set(candSlots[i], Items.of(Material.PLAYER_HEAD, "<aqua>" + Items.esc(cand.name()), lore), e -> {
                click(p);
                String err = wf().hire(c, cand, System.currentTimeMillis());
                if (err != null) Msg.send(p, "<red>" + Msg.escape(err));
                else Msg.send(p, "<green>Hired <white><n></white>.", Msg.p("n", cand.name()));
                role(p, c, r);
            });
        }
        if (cands.isEmpty()) m.set(13, Items.of(Material.GRAY_DYE, "<gray>No more candidates today", "<dark_gray>New job seekers tomorrow."));

        int slot = 18;
        for (Employee e : wf().staffOf(c.id(), r)) {
            if (slot > 44) break;
            ItemStack item = employeeItem(e, now);
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
                role(p, c, r);
            });
        }
        m.set(45, Items.of(Material.ARROW, "<gray>« Operations"), e -> {
            click(p);
            open(p, c);
        });
        m.set(49, Items.of(Material.BOOK, "<white>Hiring", "<gray>Three new job seekers per role, daily.",
                "<gray>Hiring costs <white>" + wf().w().recruitDays() + " days</white> <gray>of wage; firing",
                "<gray>costs <white>" + wf().w().severanceDays() + " days</white> <gray>severance."));
        m.open(p);
    }
}
