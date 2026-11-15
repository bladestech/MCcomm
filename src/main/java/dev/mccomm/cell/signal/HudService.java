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
        Objective o = b.registerNewObjective(OBJECTIVE, Criteria.DUMMY, Msg.mm(plugin.settings().hudTitle));
        o.setDisplaySlot(DisplaySlot.SIDEBAR);
        o.numberFormat(NumberFormat.blank());
        return b;
    }

    /**
     * Layout:
     * <pre>
     *  » Carrier        data      (carrier in its style; "data" marks the data SIM on dual SIM)
     *    |||| 5G Mid
     *    -78 dBm · 312 m · LF1
     *    Plan · 3.2 GB             (plan in its style, data left)
     *
     *  ☎ Bob                       (in a call)
     *  Pilot+ · 14:05              (phone, in-game time)
     * </pre>
     */
    private List<Component> build(Player p, SignalService.Status st) {
        List<Component> out = new ArrayList<>();
        out.add(Component.empty());
        if (st.phone() == null) {
            out.add(Msg.mm(" <red>No phone"));
            out.add(Msg.mm(" <dark_gray>Get one: <gray>/cell eshop"));
        } else if (st.sims().isEmpty()) {
            if (st.emergency()) {
                out.add(Msg.mm(" <red>No Service"));
                out.add(Msg.mm(" <gray>SOS only <dark_gray>· <white><n>", Msg.p("n", plugin.settings().emergencyNumber)));
            } else {
                out.add(Msg.mm(" <dark_gray>No Signal"));
            }
            out.add(Msg.mm(" <dark_gray>Get a SIM: <gray>/cell"));
        } else {
            var prof = plugin.store().profile(p.getUniqueId());
            boolean dual = st.sims().size() > 1;
            for (SignalService.SimStatus sim : st.sims()) {
                Carrier c = sim.carrier();
                out.add(Msg.mm(" <dark_gray>»</dark_gray> " + c.styled() + (dual && st.isDataSim(sim) ? " <dark_gray>·</dark_gray> <aqua>data" : "")));
                SignalService.Link l = sim.link();
                if (l == null) {
                    out.add(sim.issue() != null ? Msg.mm("   <red><i>", Msg.p("i", sim.issue()))
                            : Msg.mm(st.emergency() ? "   <red>No Service <dark_gray>· SOS" : "   <dark_gray>No Signal"));
                } else {
                    out.add(Msg.mm("   " + Msg.bars(l.bars()) + " <white><band>", Msg.p("band", plugin.settings().spec(l.band()).name())));
                    out.add(Msg.mm("   <gray><dbm> dBm <dark_gray>·</dark_gray> <d> <dark_gray>·</dark_gray> <t>",
                            Msg.p("dbm", String.format(Locale.ROOT, "%.0f", l.dbm())), Msg.p("d", Msg.distance(l.distance())),
                            Msg.p("t", l.tower().name())));
                }
                if (sim.plan() != null && (l != null || sim.issue() == null)) {
                    String data = plugin.billing().dataLeftText(prof, c.id()).replace(" left", "");
                    out.add(Msg.mm("   " + sim.plan().styled(c) + " <dark_gray>·</dark_gray> " + (sim.throttled() ? "<gold>" : "<gray>") + "<d>"
                            + (sim.throttled() ? " (slow)" : ""), Msg.p("d", data)));
                }
                out.add(Component.empty());
            }
            out.removeLast();
        }
        String partner = plugin.calls().partnerName(p);
        String incoming = plugin.calls().incoming(p);
        if (partner != null || incoming != null) {
            out.add(Component.empty());
            out.add(partner != null ? Msg.mm(" <dark_aqua>☎</dark_aqua> <white><n>", Msg.p("n", partner))
                    : Msg.mm(" <green>☎ Incoming:</green> <white><n>", Msg.p("n", incoming)));
        }
        out.add(Component.empty());
        String phone = st.phone() == null ? "No phone" : st.phone().name();
        out.add(Msg.mm(" <dark_gray><ph> · <time>", Msg.p("ph", phone), Msg.p("time", clock(p))));
        return out;
    }

    private static String clock(Player p) {
        long t = (p.getWorld().getTime() + 6000) % 24000;
        return String.format(Locale.ROOT, "%02d:%02d", t / 1000, (t % 1000) * 60 / 1000);
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
