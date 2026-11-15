package dev.mccomm.cell.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** A simple click-action inventory menu. Players can't move items in or out of it. */
public final class Menu implements InventoryHolder {
    private final Inventory inventory;
    private final Map<Integer, Consumer<InventoryClickEvent>> actions = new HashMap<>();
    private final String page;

    public Menu(int rows, Component title, String page) {
        this.inventory = Bukkit.createInventory(this, rows * 9, title);
        this.page = page;
    }

    public String page() {
        return page;
    }

    public Menu fill(ItemStack item) {
        for (int i = 0; i < inventory.getSize(); i++) inventory.setItem(i, item);
        return this;
    }

    public Menu set(int slot, ItemStack item) {
        return set(slot, item, null);
    }

    public Menu set(int slot, ItemStack item, Consumer<InventoryClickEvent> action) {
        inventory.setItem(slot, item);
        if (action == null) actions.remove(slot);
        else actions.put(slot, action);
        return this;
    }

    public void clearActions() {
        actions.clear();
    }

    public void open(Player p) {
        p.openInventory(inventory);
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    public static final class Listen implements Listener {
        @EventHandler
        public void onClick(InventoryClickEvent e) {
            if (!(e.getView().getTopInventory().getHolder(false) instanceof Menu m)) return;
            e.setCancelled(true);
            if (e.getClickedInventory() != e.getView().getTopInventory()) return;
            Consumer<InventoryClickEvent> a = m.actions.get(e.getSlot());
            if (a != null) a.accept(e);
        }

        @EventHandler
        public void onDrag(InventoryDragEvent e) {
            if (e.getView().getTopInventory().getHolder(false) instanceof Menu) e.setCancelled(true);
        }
    }
}
