package dev.mccomm.cell.broadband;

import dev.mccomm.cell.CellPlugin;
import dev.mccomm.cell.model.BlockKey;
import dev.mccomm.cell.util.Msg;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.WallSign;
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
import org.bukkit.inventory.EquipmentSlot;

import java.util.ArrayList;
import java.util.List;

/** Clicking, protecting and cleaning up the signs that nodes, routers and computers sit on. */
public final class BroadbandListener implements Listener {
    private final CellPlugin plugin;

    public BroadbandListener(CellPlugin plugin) {
        this.plugin = plugin;
    }

    private BroadbandService bb() {
        return plugin.broadband();
    }

    private Device deviceAt(Block b) {
        return b == null ? null : bb().store().deviceAt(BlockKey.of(b));
    }

    /** Devices whose wall sign hangs on this block. */
    private List<Device> attachedTo(Block support) {
        List<Device> out = new ArrayList<>();
        for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Block rel = support.getRelative(f);
            Device d = deviceAt(rel);
            if (d != null && rel.getBlockData() instanceof WallSign ws && ws.getFacing() == f) out.add(d);
        }
        return out;
    }

    private boolean protectedBlock(Block b) {
        return deviceAt(b) != null || !attachedTo(b).isEmpty();
    }

    /** Who may take a device down: its owner, an ISP's staff for a node, or an admin. */
    private boolean canRemove(Player p, Device d) {
        if (p.hasPermission("mccomm.admin")) return true;
        return switch (d) {
            case Node n -> {
                var isp = bb().store().isp(n.ispId());
                yield isp != null && isp.isStaff(p.getUniqueId());
            }
            case Router r -> r.owner().equals(p.getUniqueId());
            case Computer c -> c.owner().equals(p.getUniqueId());
        };
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Device d = deviceAt(e.getClickedBlock());
        if (d == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        switch (d) {
            case Node n -> plugin.broadbandMenu().node(p, n);
            case Router r -> plugin.broadbandMenu().routerOrInfo(p, r);
            case Computer c -> plugin.broadbandMenu().computer(p, c);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        List<Device> affected = new ArrayList<>();
        Device direct = deviceAt(b);
        if (direct != null) affected.add(direct);
        affected.addAll(attachedTo(b));
        if (affected.isEmpty()) return;

        Player p = e.getPlayer();
        for (Device d : affected) {
            if (!canRemove(p, d)) {
                e.setCancelled(true);
                Msg.send(p, "<red>That <k> belongs to someone else.", Msg.p("k", d.kind()));
                return;
            }
        }
        for (Device d : affected) {
            bb().removeDevice(d, false);
            Msg.send(p, "<yellow>The <k> was removed.", Msg.p("k", d.kind()));
        }
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
        if (deviceAt(e.getBlock()) != null) e.setCancelled(true);
    }
}
