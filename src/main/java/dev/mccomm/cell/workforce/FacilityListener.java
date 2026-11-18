package dev.mccomm.cell.workforce;

import dev.mccomm.cell.CellPlugin;
import dev.mccomm.cell.model.Carrier;
import dev.mccomm.cell.util.Msg;
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

/** Clicking and protecting facility signs, and cleaning up technician figures. */
public final class FacilityListener implements Listener {
    private final CellPlugin plugin;

    public FacilityListener(CellPlugin plugin) {
        this.plugin = plugin;
    }

    private WorkforceService wf() {
        return plugin.workforce();
    }

    private boolean canManage(Player p, Facility f) {
        if (p.hasPermission("mccomm.admin")) return true;
        Carrier c = plugin.store().carrier(f.companyId());
        return c != null && c.isStaff(p.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Facility f = wf().facilityAt(e.getClickedBlock());
        if (f == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        Carrier c = plugin.store().carrier(f.companyId());
        if (c != null && canManage(p, f)) {
            plugin.operationsMenu().station(p, c, f);
        } else {
            Msg.send(p, "<gray><t> <white><n></white> of <c> · <white><s>/<b></white> technicians",
                    Msg.p("t", f.type().display), Msg.p("n", f.name()), Msg.c("c", Msg.mm(c == null ? "?" : c.styled())),
                    Msg.p("s", "" + wf().staffAt(f).size()), Msg.p("b", "" + f.capacity()));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        List<Facility> affected = new ArrayList<>();
        Facility direct = wf().facilityAt(b);
        if (direct != null) affected.add(direct);
        affected.addAll(wf().facilitiesAttachedTo(b));
        if (affected.isEmpty()) return;
        Player p = e.getPlayer();
        for (Facility f : affected) {
            if (!canManage(p, f)) {
                e.setCancelled(true);
                Msg.send(p, "<red>This facility belongs to another company.");
                return;
            }
        }
        for (Facility f : affected) {
            wf().remove(f, false);
            Msg.send(p, "<yellow><t> <white><n></white> was removed. Its staff are idle until there's room elsewhere.",
                    Msg.p("t", f.type().display), Msg.p("n", f.name()));
        }
    }

    private boolean protectedBlock(Block b) {
        return wf().facilityAt(b) != null || !wf().facilitiesAttachedTo(b).isEmpty();
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
        for (Block b : e.getBlocks()) if (protectedBlock(b)) {
            e.setCancelled(true);
            return;
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        for (Block b : e.getBlocks()) if (protectedBlock(b)) {
            e.setCancelled(true);
            return;
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSignChange(SignChangeEvent e) {
        if (wf().facilityAt(e.getBlock()) != null) e.setCancelled(true);
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        wf().cleanEntities(e.getEntities());
    }
}
