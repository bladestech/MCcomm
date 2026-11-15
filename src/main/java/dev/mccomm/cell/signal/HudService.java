package dev.mccomm.cell.signal;

import dev.mccomm.cell.CellPlugin;
import dev.mccomm.cell.model.Carrier;
import dev.mccomm.cell.model.PlayerProfile;
import dev.mccomm.cell.util.Msg;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Per-player signal HUD: a scoreboard sidebar (default) or an action bar line. */
public final class HudService {
    private static final String OBJECTIVE = "cellhud";

    private final CellPlugin plugin;
    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    private final Map<UUID, List<Component>> shown = new HashMap<>();

    public HudService(CellPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean enabled(Player p) {
        PlayerProfile prof = plugin.store().profile(p.getUniqueId());
        return prof.hud() != null ? prof.hud() : plugin.settings().hudDefault;
    }

    public void update(Player p, SignalService.Status st) {
        if (!enabled(p)) {
            clear(p);
            return;
        }
        if (!plugin.settings().hudSidebar) {
            clear(p);
            p.sendActionBar(Msg.mm(plugin.signal().hudLine(st)));
            return;
        }
        List<Component> lines = build(p, st);
        UUID id = p.getUniqueId();
        Scoreboard board = boards.computeIfAbsent(id, k -> newBoard());
        if (p.getScoreboard() != board) {
            p.setScoreboard(board);
            shown.remove(id);
        }
        List<Component> before = shown.get(id);
        if (lines.equals(before)) return;

        Objective o = board.getObjective(OBJECTIVE);
        if (o == null) return;
        for (int i = 0; i < lines.size(); i++) {
            var score = o.getScore("l" + i);
            score.setScore(lines.size() - i);
            score.customName(lines.get(i));
        }
        for (int i = lines.size(); before != null && i < before.size(); i++) board.resetScores("l" + i);
        shown.put(id, lines);
    }

    private Scoreboard newBoard() {
        Scoreboard b = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective o = b.registerNewObjective(OBJECTIVE, Criteria.DUMMY, Msg.mm("<aqua><b>CELL</b>"));
        o.setDisplaySlot(DisplaySlot.SIDEBAR);
        o.numberFormat(NumberFormat.blank());
        return b;
    }

    private List<Component> build(Player p, SignalService.Status st) {
        List<Component> out = new ArrayList<>();
        String emergency = plugin.settings().emergencyNumber;
        out.add(Component.empty());
        if (st.phone() == null) {
            out.add(Msg.mm(" <red>No phone"));
            out.add(Component.empty());
            out.add(Msg.mm(" <dark_gray>/cell eshop"));
        } else if (st.sims().isEmpty()) {
            if (st.emergency()) {
                out.add(Msg.mm(" <red>No Service"));
                out.add(Msg.mm(" <gray>Emergency calls only"));
                out.add(Msg.mm(" <gray>Dial <white><n>", Msg.p("n", emergency)));
            } else {
                out.add(Msg.mm(" <dark_gray>No Signal"));
            }
            out.add(Component.empty());
            out.add(Msg.mm(" <dark_gray>/cell subscribe"));
        } else {
            for (SignalService.SimStatus sim : st.sims()) {
                Carrier c = sim.carrier();
                String col = c.color().asHexString();
                out.add(Msg.mm(" <gray>SIM <n> <dark_gray>· <" + col + "><c>" + (st.sims().size() > 1 && st.isDataSim(sim) ? " <dark_gray>[<aqua>D</aqua>]" : ""),
                        Msg.p("n", "" + sim.slot()), Msg.p("c", c.name())));
                SignalService.Link l = sim.link();
                if (l == null) {
                    out.add(sim.issue() != null ? Msg.mm(" <red><i>", Msg.p("i", sim.issue()))
                            : Msg.mm(st.emergency() ? " <red>No Service <dark_gray>· SOS" : " <dark_gray>No Signal"));
                } else {
                    out.add(Msg.mm(" " + Msg.bars(l.bars()) + " <white><band>", Msg.p("band", plugin.settings().spec(l.band()).name())));
                    out.add(Msg.mm(" <gray><dbm> dBm <dark_gray>· <gray><d>",
                            Msg.p("dbm", String.format(Locale.ROOT, "%.0f", l.dbm())), Msg.p("d", Msg.distance(l.distance()))));
                    out.add(Msg.mm(" <dark_gray>Tower <id>", Msg.p("id", l.tower().name())));
                    if (st.isDataSim(sim)) {
                        String d = plugin.billing().dataLeftText(plugin.store().profile(p.getUniqueId()), c.id());
                        out.add(Msg.mm((sim.throttled() ? " <gold>" : " <gray>") + "Data: <d>" + (sim.throttled() ? " (slow)" : ""), Msg.p("d", d)));
                    }
                }
                out.add(Component.empty());
            }
            out.removeLast();
        }
        String partner = plugin.calls().partnerName(p);
        if (partner != null) {
            out.add(Component.empty());
            out.add(Msg.mm(" <dark_aqua>☎ <white><n>", Msg.p("n", partner)));
        }
        return out;
    }

    /** Takes the HUD off this player and gives them back the main scoreboard. */
    public void clear(Player p) {
        Scoreboard b = boards.remove(p.getUniqueId());
        shown.remove(p.getUniqueId());
        if (b != null && p.getScoreboard() == b) p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
    }

    public void forget(UUID id) {
        boards.remove(id);
        shown.remove(id);
    }

    public void shutdown() {
        for (Player p : Bukkit.getOnlinePlayers()) clear(p);
    }
}
