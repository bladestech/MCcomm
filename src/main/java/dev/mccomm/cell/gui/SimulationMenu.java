package dev.mccomm.cell.gui;

import dev.mccomm.cell.CellPlugin;
import dev.mccomm.cell.model.Carrier;
import dev.mccomm.cell.model.Plan;
import dev.mccomm.cell.util.Msg;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** /cellsim: inject simulated users per carrier (roleplay), and the server-wide simulated revenue toggle. */
public final class SimulationMenu {
    private final CellPlugin plugin;

    public SimulationMenu(CellPlugin plugin) {
        this.plugin = plugin;
    }

    private void click(Player p) {
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.6f);
    }

    private Menu menu(Player p, String title) {
        return Frame.create(p, title, "simulation");
    }

    private List<String> split(Carrier c) {
        List<String> lore = new ArrayList<>();
        var counts = plugin.simulation().planCounts(c);
        for (var e : counts.entrySet()) {
            Plan plan = c.plans().get(e.getKey());
            lore.add("<gray>• " + (plan == null ? "?" : plan.styled(c)) + " <gray>× <white>" + e.getValue());
        }
        return lore;
    }

    public void open(Player p) {
        if (!p.hasPermission("mccomm.simulate")) {
            Msg.send(p, "<red>You can't simulate users.");
            return;
        }
        boolean market = plugin.market().active();
        Menu m = menu(p, market ? "Customer market" : "Simulated users");
        boolean revenue = plugin.store().simulatedRevenue();
        int slot = 9;
        for (Carrier c : plugin.store().carriers()) {
            if (slot > 44) break;
            if (market) {
                m.set(slot++, plugin.carrierMenu().marketItem(c));
                continue;
            }
            List<String> lore = new ArrayList<>();
            lore.add("<gray>Simulated users: <white>" + c.simulatedUsers());
            int towers = plugin.simulation().towerCount(c);
            lore.add("<gray>Spread over <white>" + towers + "</white> tower(s)" + (towers == 0 ? " <red>(no working towers)" : ""));
            lore.addAll(split(c));
            if (revenue && c.simulatedUsers() > 0) {
                lore.add("<gray>Pays the carrier ~<green>" + plugin.settings().money(Math.round(plugin.simulation().revenuePerDay(c))) + "</green>/day");
            }
            lore.add("");
            lore.add("<yellow>Click to adjust");
            m.set(slot++, Items.of(c.simulatedUsers() > 0 ? Material.PLAYER_HEAD : Material.SKELETON_SKULL, "<b>" + c.styled(), lore), e -> {
                click(p);
                carrier(p, c);
            });
        }
        boolean admin = p.hasPermission("mccomm.admin");
        if (market) {
            int size = plugin.store().marketSize();
            m.set(Frame.CLOSE - 4, Items.of(Material.BEACON, "<white>Market size: <aqua>" + size,
                    "<gray>Simulated customers on the server.",
                    "<gray>With a carrier: <white>" + plugin.market().customers(),
                    "<gray>Without one: <white>" + plugin.market().unassigned(),
                    plugin.settings().market.growthPerDay() > 0
                            ? "<gray>Grows <white>" + Math.round(plugin.settings().market.growthPerDay() * 1000) / 10.0 + "%</white>/day"
                            + (plugin.settings().market.growthMax() > 0 ? " up to " + plugin.settings().market.growthMax() : "") : "<gray>Growth: off",
                    "",
                    "<gray>Carriers win and lose customers on",
                    "<gray>coverage, speed, support and price.",
                    "<gray>" + Math.round(1 / plugin.settings().simulationWeight) + " customers load a tower like 1 player.",
                    "",
                    "<yellow>Click <gray>+100 · <yellow>Right-click <gray>-100 · <yellow>Shift-click <gray>type a number"), e -> {
                click(p);
                if (e.isShiftClick()) {
                    plugin.prompt().ask(p, "<aqua>How many simulated customers on the server?", in -> {
                        try {
                            plugin.market().resize(Integer.parseInt(in.trim()));
                        } catch (NumberFormatException ex) {
                            Msg.send(p, "<red>Enter a whole number.");
                        }
                        open(p);
                    });
                    return;
                }
                plugin.market().resize(size + (e.isRightClick() ? -100 : 100));
                open(p);
            });
        }
        m.set(Frame.CLOSE - 6, Items.of(Material.SCAFFOLDING, "<white>Spread evenly",
                market ? "<gray>Shares the whole pool equally between" : "<gray>Evens out the users carriers have so",
                market ? "<gray>all carriers (nobody is left without)." : "<gray>every carrier has the same number.",
                "<gray>Customers then move on as usual.", "", "<yellow>Click to spread"), e -> {
            click(p);
            int n = plugin.market().spreadEvenly();
            if (n == 0) Msg.send(p, "<red>There are no simulated users to spread.");
            else Msg.send(p, "<green>Spread simulated users evenly across <n> carrier(s).", Msg.p("n", "" + n));
            open(p);
        });
        m.set(Frame.CLOSE - 2, Items.of(revenue ? Material.EMERALD_BLOCK : Material.COAL_BLOCK,
                "<white>Simulated revenue: " + (revenue ? "<green>ON" : "<red>OFF"),
                "<gray>When on, simulated users pay their plan",
                "<gray>into the carrier's wallet (hourly).",
                "<gold>This creates money: it moves the economy.",
                "<gray>Applies to the whole server.",
                "",
                admin ? "<yellow>Click to turn " + (revenue ? "off" : "on") : "<dark_gray>Only admins can change this"), e -> {
            if (!admin) return;
            click(p);
            plugin.store().simulatedRevenue(!revenue);
            Msg.send(p, "<gray>Simulated revenue is now " + (!revenue ? "<green>on" : "<red>off") + "<gray>.");
            open(p);
        });
        Frame.help(m, market ? "Customer market" : "Simulated users", market ? "Each carrier's customers and satisfaction."
                : "Click a carrier to set its simulated users.", "Top: " + (market ? "market size and " : "") + "the revenue toggle.",
                "", Math.round(1 / plugin.settings().simulationWeight) + " simulated users load a tower like", "one real player.");
        m.open(p);
    }

    public void carrier(Player p, Carrier c) {
        if (!p.hasPermission("mccomm.simulate")) return;
        Menu m = menu(p, "Simulate · " + Items.esc(c.name()));
        List<String> info = new ArrayList<>();
        info.add("<gray>Simulated users: <white>" + c.simulatedUsers());
        int towers = plugin.simulation().towerCount(c);
        info.add("<gray>Towers: <white>" + towers + (towers > 0 && c.simulatedUsers() > 0
                ? " <dark_gray>(~" + Math.max(1, c.simulatedUsers() / towers) + " each)" : ""));
        info.addAll(split(c));
        info.add("");
        info.add("<gray>" + Math.round(1 / plugin.settings().simulationWeight) + " simulated users load a tower");
        info.add("<gray>like 1 real player (counted separately).");
        info.add(plugin.store().simulatedRevenue() ? "<green>They pay their plans (revenue is on)." : "<gray>They don't pay (revenue is off).");
        m.set(4, Items.glow(Items.of(Material.PLAYER_HEAD, "<b>" + c.styled(), info)));
        int[] deltas = {-100, -10, -1, 1, 10, 100};
        int[] slots = {19, 20, 21, 23, 24, 25};
        for (int i = 0; i < deltas.length; i++) {
            int d = deltas[i];
            m.set(slots[i], Items.of(d < 0 ? Material.RED_CONCRETE : Material.LIME_CONCRETE, (d < 0 ? "<red>" : "<green>+") + d), e -> {
                click(p);
                set(p, c, c.simulatedUsers() + d);
            });
        }
        m.set(22, Items.of(Material.NAME_TAG, "<white>Set exactly", "<yellow>Click to type a number"), e -> {
            click(p);
            plugin.prompt().ask(p, "<aqua>How many simulated users for <white>" + Items.esc(c.name()) + "</white>?", in -> {
                try {
                    set(p, c, Integer.parseInt(in.trim()));
                } catch (NumberFormatException ex) {
                    Msg.send(p, "<red>Enter a whole number.");
                    carrier(p, c);
                }
            });
        });
        m.set(31, Items.of(Material.BARRIER, "<white>Clear", "<gray>Remove all simulated users."), e -> {
            click(p);
            set(p, c, 0);
        });
        Frame.back(m, p, "All carriers", () -> open(p));
        m.open(p);
    }

    private void set(Player p, Carrier c, int n) {
        c.simulatedUsers(Math.clamp(n, 0, 100_000));
        plugin.store().markDirty();
        plugin.signal().tick(); // show the new load right away
        carrier(p, c);
    }
}
