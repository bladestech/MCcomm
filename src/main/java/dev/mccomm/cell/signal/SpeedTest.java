package dev.mccomm.cell.signal;

import dev.mccomm.cell.CellPlugin;
import dev.mccomm.cell.util.Msg;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Animated three-phase speed test (ping, download, upload) shown in a boss bar. */
public final class SpeedTest {
    private static final int PING_TICKS = 20, DL_TICKS = 50, UL_TICKS = 50;

    private final CellPlugin plugin;
    private final Map<UUID, Long> cooldown = new HashMap<>();
    private final Map<UUID, BukkitTask> running = new HashMap<>();

    public SpeedTest(CellPlugin plugin) {
        this.plugin = plugin;
    }

    public void start(Player p, int simSlot) {
        UUID id = p.getUniqueId();
        if (running.containsKey(id)) {
            Msg.send(p, "<red>A speed test is already running.");
            return;
        }
        long now = System.currentTimeMillis();
        Long until = cooldown.get(id);
        if (until != null && until > now && !p.hasPermission("mccomm.admin")) {
            Msg.send(p, "<red>Wait <white><secs>s</white> before testing again.", Msg.p("secs", "" + ((until - now) / 1000 + 1)));
            return;
        }

        SignalService.Status st = plugin.signal().status(p);
        SignalService.SimStatus sim = simSlot > 0 ? st.slot(simSlot) : st.primary();
        if (sim == null || sim.link() == null) {
            if (st.sims().isEmpty() && st.emergency()) Msg.send(p, "<red>No Service</red> <gray>· Emergency calls only. Subscribe with <white>/cell subscribe <carrier>");
            else Msg.send(p, "<red>No service on that SIM. Nothing to test.");
            return;
        }

        double jitter = plugin.settings().jitterPercent / 100.0;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double ping = plugin.signal().pingMs(sim.link()) * (1 + r.nextDouble(-jitter, jitter));
        double dl = plugin.signal().downloadMbps(sim) * (1 + r.nextDouble(-jitter, jitter));
        double ul = plugin.signal().uploadMbps(sim) * (1 + r.nextDouble(-jitter, jitter));
        String band = plugin.settings().spec(sim.link().band()).name();

        // A speedtest really downloads and uploads: speed x duration, in megabytes.
        double usedMB = (dl * DL_TICKS / 20.0 + ul * UL_TICKS / 20.0) / 8.0;
        dev.mccomm.cell.model.PlayerProfile prof = plugin.store().profile(id);
        if (plugin.billing().dataBlockedOn(prof, sim, usedMB)) {
            Msg.send(p, "<red>Not enough data for a speedtest at this speed (needs about <white><mb></white>). Buy a data pack in your wallet.",
                    Msg.p("mb", dev.mccomm.cell.Settings.data(usedMB)));
            return;
        }
        plugin.billing().useDataOn(id, sim, usedMB);
        cooldown.put(id, now + plugin.settings().speedtestCooldown * 1000L);
        String dataLine = dev.mccomm.cell.Settings.data(usedMB) + " used · " + plugin.billing().dataLeftText(prof, sim.carrier().id());
        String carrier = sim.carrier().name();
        String towerName = sim.link().tower().name();

        BossBar bar = BossBar.bossBar(Msg.mm("<aqua>Speedtest <gray>· connecting…"), 0f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
        p.showBossBar(bar);
        final int[] tick = {0};
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int t = ++tick[0];
            if (!p.isOnline()) {
                stop(id, bar, p);
                return;
            }
            if (t <= PING_TICKS) {
                float f = t / (float) PING_TICKS;
                bar.progress(f);
                bar.name(Msg.mm("<aqua>Ping <white><v> ms", Msg.p("v", "" + Math.round(ping * (0.5 + f / 2)))));
            } else if (t <= PING_TICKS + DL_TICKS) {
                float f = (t - PING_TICKS) / (float) DL_TICKS;
                bar.color(BossBar.Color.GREEN);
                bar.progress(f);
                double ramp = dl * Math.min(1, f * 1.3) * (0.92 + 0.08 * Math.sin(t));
                bar.name(Msg.mm("<green>↓ Download <white><v>", Msg.p("v", Msg.speed(ramp))));
            } else if (t <= PING_TICKS + DL_TICKS + UL_TICKS) {
                float f = (t - PING_TICKS - DL_TICKS) / (float) UL_TICKS;
                bar.color(BossBar.Color.PURPLE);
                bar.progress(f);
                double ramp = ul * Math.min(1, f * 1.3) * (0.92 + 0.08 * Math.sin(t));
                bar.name(Msg.mm("<light_purple>↑ Upload <white><v>", Msg.p("v", Msg.speed(ramp))));
            } else {
                stop(id, bar, p);
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.8f, 1.6f);
                Msg.raw(p, "<dark_gray>━━━ <aqua>Speedtest</aqua> ━━━");
                Msg.raw(p, "<gray>Carrier: <white><c> <gray>· Tower <white><t> <gray>· <white><band>",
                        Msg.p("c", carrier), Msg.p("t", towerName), Msg.p("band", band));
                Msg.raw(p, "<gray>Ping: <white><v> ms", Msg.p("v", "" + Math.round(ping)));
                Msg.raw(p, "<green>↓ <gray>Download: <white><v>", Msg.p("v", Msg.speed(dl)));
                Msg.raw(p, "<light_purple>↑ <gray>Upload: <white><v>", Msg.p("v", Msg.speed(ul)));
                Msg.raw(p, "<dark_gray>Data: <gray><d>", Msg.p("d", dataLine));
            }
        }, 1L, 1L);
        running.put(id, task);
    }

    private void stop(UUID id, BossBar bar, Player p) {
        BukkitTask t = running.remove(id);
        if (t != null) t.cancel();
        p.hideBossBar(bar);
    }

    public void shutdown() {
        for (BukkitTask t : running.values()) t.cancel();
        running.clear();
    }
}
