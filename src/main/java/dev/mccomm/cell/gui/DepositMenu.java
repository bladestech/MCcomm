package dev.mccomm.cell.gui;

import dev.mccomm.cell.CellPlugin;
import dev.mccomm.cell.model.Carrier;
import dev.mccomm.cell.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A drop box: put ores in the top 3 rows, close it (or click Deposit) and they become credits
 * in the player's or a carrier's wallet. Anything that isn't currency is handed back.
 */
public final class DepositMenu implements Listener {
    private static final int FREE = 27;
    private static final int SLOT_ALL = 29, SLOT_INFO = 31, SLOT_DONE = 33;

    private final CellPlugin plugin;

    public DepositMenu(CellPlugin plugin) {
        this.plugin = plugin;
    }

    private static final class Holder implements InventoryHolder {
        final String carrierId; // null = the player's own wallet
        final Runnable after;
        Inventory inv;
        boolean done;

        Holder(String carrierId, Runnable after) {
            this.carrierId = carrierId;
            this.after = after;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inv;
        }
    }

    /** Opens a deposit box for the player's wallet ({@code carrier == null}) or a carrier's. */
    public void open(Player p, Carrier carrier, Runnable after) {
        Holder h = new Holder(carrier == null ? null : carrier.id(), after);
        h.inv = Bukkit.createInventory(h, 36, Msg.mm("<dark_gray>Deposit · " + (carrier == null ? "your wallet"
                : Items.esc(carrier.name()))));
        ItemStack bar = Items.of(Material.GRAY_STAINED_GLASS_PANE, "<gray> ");
        for (int i = FREE; i < 36; i++) h.inv.setItem(i, bar);
        h.inv.setItem(SLOT_ALL, Items.of(Material.HOPPER, "<yellow>Add all ores from your inventory"));
        List<String> rates = new ArrayList<>();
        rates.add("<gray>Put ores in the slots above.");
        rates.add("<gray>Closing deposits them; other items come back.");
        rates.add("");
        int shown = 0;
        for (Map.Entry<Material, Long> e : plugin.settings().oreValues.entrySet()) {
            if (shown++ >= 10) break;
            rates.add("<gray>" + TowerGuiNames.pretty(e.getKey()) + ": <white>" + plugin.settings().money(e.getValue()));
        }
        h.inv.setItem(SLOT_INFO, Items.of(Material.GOLD_NUGGET, "<gold>Ore values", rates));
        h.inv.setItem(SLOT_DONE, Items.of(Material.LIME_CONCRETE, "<green>Deposit"));
        p.openInventory(h.inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder(false) instanceof Holder h)) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() == e.getView().getTopInventory() && e.getSlot() >= FREE) {
            e.setCancelled(true);
            if (e.getSlot() == SLOT_ALL) {
                for (int i = 0; i < p.getInventory().getStorageContents().length; i++) {
                    ItemStack it = p.getInventory().getItem(i);
                    if (it == null || !plugin.settings().oreValues.containsKey(it.getType())) continue;
                    Map<Integer, ItemStack> left = addToFree(h.inv, it);
                    p.getInventory().setItem(i, left.isEmpty() ? null : left.values().iterator().next());
                }
            } else if (e.getSlot() == SLOT_DONE) {
                p.closeInventory();
            }
        }
    }

    private static Map<Integer, ItemStack> addToFree(Inventory inv, ItemStack it) {
        // Only the free slots: temporarily work on a copy of those 27.
        Inventory tmp = Bukkit.createInventory(null, FREE);
        for (int i = 0; i < FREE; i++) tmp.setItem(i, inv.getItem(i));
        Map<Integer, ItemStack> left = tmp.addItem(it.clone());
        for (int i = 0; i < FREE; i++) inv.setItem(i, tmp.getItem(i));
        return left;
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getView().getTopInventory().getHolder(false) instanceof Holder)) return;
        for (int raw : e.getRawSlots()) {
            if (raw >= FREE && raw < 36) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder(false) instanceof Holder h) || h.done) return;
        if (!(e.getPlayer() instanceof Player p)) return;
        h.done = true;
        long total = 0;
        for (int i = 0; i < FREE; i++) {
            ItemStack it = h.inv.getItem(i);
            if (it == null || it.getType().isAir()) continue;
            long v = plugin.billing().valueOf(it);
            if (v > 0) total += v;
            else for (ItemStack back : p.getInventory().addItem(it).values()) p.getWorld().dropItem(p.getLocation(), back);
            h.inv.setItem(i, null);
        }
        if (total > 0) {
            if (h.carrierId == null) {
                plugin.billing().depositPlayer(p, total);
            } else {
                Carrier c = plugin.store().carrier(h.carrierId);
                if (c != null) plugin.billing().depositCarrier(p, c, total);
            }
        }
        if (h.after != null) Bukkit.getScheduler().runTask(plugin, h.after);
    }

    /** Item-name helper shared with other menus. */
    public static final class TowerGuiNames {
        private TowerGuiNames() {
        }

        public static String pretty(Material m) {
            return dev.mccomm.cell.tower.TowerGui.pretty(m);
        }
    }
}
