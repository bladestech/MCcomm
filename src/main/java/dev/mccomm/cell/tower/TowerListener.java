package dev.mccomm.cell.tower;

import dev.mccomm.cell.CellPlugin;
import dev.mccomm.cell.band.Band;
import dev.mccomm.cell.model.Carrier;
import dev.mccomm.cell.model.Tower;
import dev.mccomm.cell.util.Msg;
import org.bukkit.Chunk;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.ArrayList;
import java.util.List;

/** Clicking, protecting and cleaning up tower signs. */
public final class TowerListener implements Listener {
    private final CellPlugin plugin;

    public TowerListener(CellPlugin plugin) {
        this.plugin = plugin;
    }

    private TowerManager towers() {
        return plugin.towers();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Tower t = towers().towerAt(e.getClickedBlock());
        if (t == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (towers().canManage(p, t)) {
            plugin.gui().open(p, t);
        } else {
            sendInfo(p, t);
        }
    }

    public void sendInfo(Player p, Tower t) {
        Carrier c = plugin.store().carrier(t.carrierId());
        List<String> bands = new ArrayList<>();
        for (Band b : t.enabledBands()) bands.add(plugin.settings().spec(b).name());
        Msg.raw(p, "<dark_gray>━━━ <aqua>Tower <id></aqua> ━━━");
        Msg.raw(p, "<gray>Carrier: <white><carrier>", Msg.p("carrier", c == null ? "?" : c.name()));
        Msg.raw(p, "<gray>Model: <white><model> <gray>(Gen <gen>)",
                Msg.p("model", plugin.settings().generation(t.generation()).model()), Msg.p("gen", "" + t.generation()));
        Msg.raw(p, "<gray>Bands: <white><bands>", Msg.p("bands", bands.isEmpty() ? "none" : String.join(", ", bands)));
        int pct = (int) Math.round((plugin.settings().rangeMultiplier(t.heightAboveGround()) - 1) * 100);
        Msg.raw(p, "<gray>Height: <white><h> blocks <dark_gray>(+<pct>% range)",
                Msg.p("h", "" + Math.round(t.heightAboveGround())), Msg.p("pct", "" + pct));
        Msg.raw(p, "<gray>Status: " + (t.online() ? "<green>Online" : "<red>Offline"));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        List<Tower> affected = new ArrayList<>();
        Tower direct = towers().towerAt(b);
        if (direct != null) affected.add(direct);
        affected.addAll(towers().towersAttachedTo(b));
        if (affected.isEmpty()) return;

        Player p = e.getPlayer();
        for (Tower t : affected) {
            if (!towers().canManage(p, t)) {
                e.setCancelled(true);
                Msg.send(p, "<red>This cell tower belongs to another carrier.");
                return;
            }
        }
        for (Tower t : affected) {
            towers().remove(t, false);
            Msg.send(p, "<yellow>Cell tower <id> was removed. Its backhaul block was destroyed.", Msg.p("id", t.name()));
        }
    }

    private boolean protectedBlock(Block b) {
        return towers().towerAt(b) != null || !towers().towersAttachedTo(b).isEmpty();
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(this::protectedBlock);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(this::protectedBlock);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        if (protectedBlock(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent e) {
        if (protectedBlock(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        for (Block b : e.getBlocks()) {
            if (protectedBlock(b)) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        for (Block b : e.getBlocks()) {
            if (protectedBlock(b)) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSignChange(SignChangeEvent e) {
        if (towers().towerAt(e.getBlock()) != null) e.setCancelled(true);
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        towers().cleanEntities(e.getEntities());
        Chunk ch = e.getChunk();
        for (Tower t : new ArrayList<>(plugin.store().towers())) {
            if (t.worldId().equals(ch.getWorld().getUID()) && t.x() >> 4 == ch.getX() && t.z() >> 4 == ch.getZ()) {
                towers().ensureEntities(t);
            }
        }
    }
}
