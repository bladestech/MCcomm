package dev.mccomm.cell.gui;

import dev.mccomm.cell.CellPlugin;
import dev.mccomm.cell.economy.BillingService;
import dev.mccomm.cell.model.Carrier;
import dev.mccomm.cell.model.Tower;
import dev.mccomm.cell.util.Msg;
import dev.mccomm.cell.workforce.Employee;
import dev.mccomm.cell.workforce.Facility;
import dev.mccomm.cell.workforce.WorkforceService;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Workforce sub-pages of the carrier menu: facilities, one facility, and hiring per role. */
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

    /** Old entry point: the carrier's Network tab. */
    public void open(Player p, Carrier c) {
        plugin.carrierMenu().tab(p, c, CarrierMenu.NETWORK);
    }

    public static String wearBar(double wear) {
        int lit = (int) Math.ceil(Math.clamp(wear, 0, 100) / 10.0);
        String col = wear >= 80 ? "<#FF5C5C>" : wear >= 60 ? "<#FF9F43>" : wear >= 40 ? "<#FFD86B>" : "<#5EE38A>";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) sb.append(i < lit ? col : "<#3A3D45>").append('|');
        return sb.toString();
    }

    // =================================================================== roles

    public ItemStack roleItem(Carrier c, Employee.Role r, long now, boolean clickable) {
        List<Employee> staff = wf().staffOf(c.id(), r);
        int working = 0;
        for (Employee e : staff) if (e.working()) working++;
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Staff: <white>" + staff.size() + (working < staff.size() ? " <red>(" + (staff.size() - working) + " not working)" : "")
                + " <gray>· room for <white>" + Math.max(0, wf().freeSlots(c.id(), r.facility())) + "</white> more");
        if (r == Employee.Role.TECHNICIAN) {
            lore.add("<gray>Repair worn towers from stations.");
        } else {
            double load = wf().load(c, r);
            String col = load <= 1 ? "<green>" : load <= 1.5 ? "<yellow>" : "<red>";
            lore.add("<gray>Workload: " + col + (Double.isInfinite(load) ? "no staff" : Math.round(load * 100) + "%")
                    + " <gray>· need ~<white>" + wf().needed(c, r));
            if (r == Employee.Role.ENGINEER && wf().engineerSpeed(c) < 1) {
                lore.add("<red>Network slowed to " + Math.round(wf().engineerSpeed(c) * 100) + "%");
            }
        }
        if (clickable) lore.add("<yellow>Click · hire and manage");
        return Items.of(icon(r), "<white>" + r.display + " <dark_gray>(" + staff.size() + ")", lore);
    }

    /** Hire and manage one role: today's candidates on top, staff below (shift-right-click twice to fire). */
    public void role(Player p, Carrier c, Employee.Role r) {
        role(p, c, r, 0);
    }

    public void role(Player p, Carrier c, Employee.Role r, int pg) {
        if (!plugin.carrierMenu().canView(p, c)) return;
        long now = System.currentTimeMillis();
        Menu m = Frame.create(p, Items.esc(c.name()) + " · " + r.display, "operations");
        Frame.title(m, roleItem(c, r, now, false));
        Frame.back(m, p, "Staff", () -> plugin.carrierMenu().tab(p, c, CarrierMenu.STAFF));

        List<WorkforceService.Candidate> cands = wf().candidates(c.id(), r, now);
        int[] candSlots = {11, 13, 15};
        for (int i = 0; i < cands.size() && i < candSlots.length; i++) {
            WorkforceService.Candidate cand = cands.get(i);
            List<String> lore = new ArrayList<>();
            lore.add("<gray>" + Employee.LEVELS[cand.level()] + " · <white>" + money(cand.wage()) + "</white>/day <dark_gray>(market "
                    + money(wf().w().wage(r, cand.level())) + ")");
            if (r == Employee.Role.TECHNICIAN) lore.add("<gray>Repair speed: <white>" + Math.round(100 / wf().w().levelSpeed()[cand.level()]) + "%");
            else lore.add("<gray>Covers ~<white>" + Math.round(wf().w().covers(r) * wf().w().levelProductivity()[cand.level()]) + "</white> subscribers");
            lore.add("<gray>Recruitment fee: <white>" + money(wf().recruitFee(cand.wage())));
            lore.add("<yellow>Click · hire");
            m.set(candSlots[i], Items.of(Material.PLAYER_HEAD, "<aqua>" + Items.esc(cand.name()), lore), e -> {
                Frame.click(p);
                String err = wf().hire(c, cand, System.currentTimeMillis());
                if (err != null) Msg.send(p, "<red>" + Msg.escape(err));
                else Msg.send(p, "<green>Hired <white><n></white>.", Msg.p("n", cand.name()));
                role(p, c, r, pg);
            });
        }
        if (cands.isEmpty()) m.set(13, Frame.empty("No more job seekers today", "New ones arrive tomorrow."));

        List<Employee> staff = wf().staffOf(c.id(), r);
        if (staff.isEmpty()) m.set(31, Frame.empty("Nobody hired yet"));
        Frame.page(m, p, staff, pg, Frame.rows(2, 4), e -> {
            ItemStack item = employeeItem(e, now);
            long sev = wf().severance(e);
            item.editMeta(meta -> {
                List<net.kyori.adventure.text.Component> lore = new ArrayList<>(meta.lore() == null ? List.of() : meta.lore());
                lore.add(Msg.item("<red>Shift-right-click twice · fire <gray>(" + money(sev) + ")"));
                meta.lore(lore);
            });
            return item;
        }, (e, ev) -> {
            if (!ev.isShiftClick() || !ev.isRightClick()) return;
            Long t = confirm.get(p.getUniqueId());
            if (t == null || System.currentTimeMillis() - t > 5000) {
                confirm.put(p.getUniqueId(), System.currentTimeMillis());
                Msg.send(p, "<yellow>Shift-right-click again within 5 s to fire <white><n></white> (<white><s></white> severance).",
                        Msg.p("n", e.name()), Msg.p("s", money(wf().severance(e))));
                return;
            }
            confirm.remove(p.getUniqueId());
            String err = wf().fire(c, e);
            if (err != null) Msg.send(p, "<red>" + Msg.escape(err));
            else Msg.send(p, "<yellow>Fired <white><n></white>.", Msg.p("n", e.name()));
            role(p, c, r, pg);
        }, n -> role(p, c, r, n));
        Frame.help(m, r.display, "Top: today's job seekers (three a day).", "Below: your staff.", "",
                "They work in a " + r.facility().display.toLowerCase() + ":", "one " + r.facility().needs + ".",
                "Hiring costs " + wf().w().recruitDays() + " days of wage; firing " + wf().w().severanceDays() + ".");
        m.open(p);
    }

    private ItemStack employeeItem(Employee e, long now) {
        List<String> lore = new ArrayList<>();
        Facility f = wf().facility(e.facilityId());
        lore.add("<gray>" + e.levelName() + " · <white>" + money(e.wage()) + "</white>/day");
        lore.add("<gray>At: <white>" + (f == null ? "<red>nowhere (idle)" : Items.esc(f.name())));
        if (e.role() == Employee.Role.TECHNICIAN) lore.add("<gray>Repairs done: <white>" + e.jobsDone());
        if (e.unpaidDays() > 0) lore.add("<red>Unpaid for " + e.unpaidDays() + " day(s): not working");
        else if (f == null) lore.add("<red>Idle: no room for them");
        else if (e.busyUntil() > now) {
            Tower t = e.jobTower() == 0 ? null : plugin.store().tower(e.jobTower());
            lore.add((t != null ? "<aqua>Repairing " + Items.esc(t.name()) : "<aqua>Driving back") + " · free in " + BillingService.until(e.busyUntil() - now));
        } else lore.add("<green>Working");
        return Items.of(e.unpaidDays() > 0 || f == null ? Material.SKELETON_SKULL : Material.PLAYER_HEAD, "<white>" + Items.esc(e.name()), lore);
    }

    // ============================================================== facilities

    private ItemStack facilityItem(Facility f, long now, boolean clickable) {
        List<Employee> here = wf().staffAt(f);
        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + f.type().display + " · staff <white>" + here.size() + "/" + f.capacity());
        if (f.type() == Facility.Type.STATION) {
            int busy = 0;
            for (Employee e : here) if (!wf().free(e, now)) busy++;
            lore.add("<gray>Out on jobs: <white>" + busy + " <gray>· spare parts: <white>" + f.barrels());
        }
        lore.add("<gray>Rent: <white>" + money(wf().rent(f)) + "</white>/day");
        lore.add("<dark_gray>" + (f.world() == null ? "?" : f.world().getName()) + " " + f.x() + ", " + f.y() + ", " + f.z());
        if (clickable) lore.add("<yellow>Click · details");
        return Items.of(icon(f.type()), "<white>" + Items.esc(f.name()), lore);
    }

    public void facilities(Player p, Carrier c, int pg) {
        if (!plugin.carrierMenu().canView(p, c)) return;
        long now = System.currentTimeMillis();
        Menu m = Frame.create(p, Items.esc(c.name()) + " · Facilities", "operations");
        Frame.title(m, Items.of(Material.RED_BED, "<white>Facilities"));
        Frame.back(m, p, "Network", () -> plugin.carrierMenu().tab(p, c, CarrierMenu.NETWORK));
        List<Facility> list = wf().facilitiesOf(c.id());
        if (list.isEmpty()) {
            m.set(22, Frame.empty("No facilities yet", "Build a closed, roofed room, put a wall", "sign inside, look at it and run",
                    "/facility create station|callcentre|noc <name>"));
        }
        Frame.page(m, p, list, pg, Frame.CONTENT, f -> facilityItem(f, now, true), (f, e) -> {
            Frame.click(p);
            facility(p, c, f);
        }, n -> facilities(p, c, n));
        Frame.help(m, "Facilities", "Station: one bed per technician;", "barrels make repairs faster.",
                "Call centre: one seat (stairs) and", "lectern per support agent.", "NOC: one seat (stairs) and observer", "per network engineer.",
                "", "Create: /facility create <type> <name>");
        m.open(p);
    }

    /** Old name, kept for callers. */
    public void station(Player p, Carrier c, Facility f) {
        facility(p, c, f);
    }

    public void facility(Player p, Carrier c, Facility f) {
        if (!plugin.carrierMenu().canView(p, c)) return;
        long now = System.currentTimeMillis();
        Menu m = Frame.create(p, Items.esc(f.name()) + " · " + f.type().display, "operations");
        Frame.title(m, facilityItem(f, now, false));
        Frame.back(m, p, "Facilities", () -> facilities(p, c, 0));
        List<Employee> here = wf().staffAt(f);
        if (here.isEmpty()) m.set(22, Frame.empty("Nobody works here yet", "Hire staff on the Staff tab."));
        int slot = 9;
        for (Employee e : here) {
            if (slot > 35) break;
            m.set(slot++, employeeItem(e, now));
        }
        if (f.type() == Facility.Type.STATION) {
            int inReach = 0;
            for (Tower t : plugin.store().towersOf(c.id())) {
                double d = f.distanceTo(t.worldId(), t.x(), t.z());
                if (d >= 0 && d <= wf().w().serviceRadius()) inReach++;
            }
            m.set(39, Items.of(Material.COMPASS, "<white>Reach: " + inReach + "/" + plugin.store().towersOf(c.id()).size() + " towers",
                    "<gray>Within " + wf().w().serviceRadius() + " blocks; travel " + Math.round(wf().w().blocksPerMinute()) + " blocks/min."));
        }
        m.set(41, Items.of(Material.SPYGLASS, "<white>Re-count the room", "<gray>Needs one " + f.type().needs + ".", "<yellow>Click · check now"), e -> {
            Frame.click(p);
            String err = wf().recheck(f);
            if (err != null) Msg.send(p, "<red>" + Msg.escape(err));
            if (wf().facility(f.id()) != null) facility(p, c, f);
            else p.closeInventory();
        });
        Frame.help(m, f.type().display, "Room is re-counted every " + wf().w().recheckMinutes() + " minutes", "while loaded. If it shrinks, the newest",
                "staff go idle (still paid).", "", "Breaking the sign removes it.");
        m.open(p);
    }
}
