package dev.mccomm.cell.tower;

import dev.mccomm.cell.CellPlugin;
import dev.mccomm.cell.Settings;
import dev.mccomm.cell.band.BackhaulSpec;
import dev.mccomm.cell.band.Band;
import dev.mccomm.cell.band.BandSpec;
import dev.mccomm.cell.band.Generation;
import dev.mccomm.cell.model.Carrier;
import dev.mccomm.cell.model.Tower;
import dev.mccomm.cell.util.Msg;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The tower management inventory: band toggles, the locked backhaul slot, discard and upgrade. */
public final class TowerGui implements Listener {
    public static final int SLOT_INFO = 4;
    public static final int FIRST_BAND = 10; // 8 bands: 10..17
    public static final int SLOT_BACKHAUL = 29;
    public static final int SLOT_DISCARD = 30;
    public static final int SLOT_UPGRADE = 33;
    public static final int SLOT_CLOSE = 49;
    private static final long CONFIRM_MS = 5000;

    private final CellPlugin plugin;

    public TowerGui(CellPlugin plugin) {
        this.plugin = plugin;
    }

    public static final class Holder implements InventoryHolder {
        final int towerId;
        final Map<UUID, Long> discardConfirm = new HashMap<>();
        Inventory inventory;

        Holder(int towerId) {
            this.towerId = towerId;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    private Settings s() {
        return plugin.settings();
    }

    public void open(Player p, Tower t) {
        Holder h = new Holder(t.id());
        Carrier c = plugin.store().carrier(t.carrierId());
        h.inventory = Bukkit.createInventory(h, 54,
                Msg.mm("Tower <id> · <carrier>", Msg.p("id", t.name()), Msg.p("carrier", c == null ? "?" : c.name())));
        render(h, t);
        p.openInventory(h.inventory);
    }

    public void refreshAll(Tower t) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder(false) instanceof Holder h && h.towerId == t.id()) {
                render(h, t);
            }
        }
    }

    public void closeAll(Tower t) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder(false) instanceof Holder h && h.towerId == t.id()) {
                p.closeInventory();
            }
        }
    }

    // ------------------------------------------------------------------ render

    private void render(Holder h, Tower t) {
        Inventory inv = h.inventory;
        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, "<gray> ", List.of());
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);

        Generation gen = s().generation(t.generation());
        BackhaulSpec bh = t.backhaul() == null ? null : s().backhaul.get(t.backhaul());
        Carrier c = plugin.store().carrier(t.carrierId());
        inv.setItem(SLOT_INFO, item(Material.LIGHTNING_ROD, "<aqua><b>" + esc(gen.model()) + "</b> <gray>· Gen " + t.generation(), List.of(
                "<gray>Carrier: <white>" + esc(c == null ? "?" : c.name()),
                "<gray>Tower: <white>" + esc(t.name()),
                "<gray>Location: <white>" + t.x() + ", " + t.y() + ", " + t.z(),
                "<gray>Height: <white>" + Math.round(t.heightAboveGround()) + " blocks <dark_gray>(+" + bonusPct(t) + "% range)",
                "<gray>Status: " + (t.online() ? "<green>Online" : "<red>Offline"),
                "<gray>Users: <white>" + plugin.signal().usersOn(t)
                        + (plugin.signal().simOn(t) > 0 ? " <dark_gray>+ " + plugin.signal().simOn(t) + " simulated" : ""))));

        int slot = FIRST_BAND;
        for (Band b : Band.values()) {
            BandSpec spec = s().spec(b);
            List<String> lore = new ArrayList<>();
            double mult = s().rangeMultiplier(t.heightAboveGround());
            lore.add("<gray>Range: <white>" + Msg.distance(spec.range() * mult)
                    + (mult > 1.0001 ? " <dark_gray>(base " + Msg.distance(spec.range()) + ")" : ""));
            lore.add("<gray>Peak: <white>" + Msg.speed(spec.maxMbps()) + (bh != null
                    ? " <dark_gray>(" + Msg.speed(spec.maxMbps() * bh.multiplier()) + " with " + esc(bh.name()) + ")" : ""));
            lore.add("<gray>Ping: <white>" + spec.pingMs() + " ms");
            lore.add("");
            ItemStack it;
            if (!plugin.towers().available(t, b)) {
                lore.add("<red>Not in this model");
                it = item(Material.BARRIER, "<dark_gray>" + esc(spec.name()), lore);
            } else if (t.enabledBands().contains(b)) {
                lore.add("<green>Enabled <dark_gray>· click to disable");
                it = item(spec.icon(), "<green><b>" + esc(spec.name()), lore);
                it.editMeta(m -> m.setEnchantmentGlintOverride(true));
            } else {
                lore.add("<red>Disabled <dark_gray>· click to enable");
                it = item(spec.icon(), "<red>" + esc(spec.name()), lore);
            }
            inv.setItem(slot++, it);
        }

        if (t.backhaul() == null) {
            List<String> lore = new ArrayList<>();
            lore.add("<gray>Place a block here to bring the tower online.");
            lore.add("<red>Once installed it is locked and can only be discarded.");
            lore.add("");
            for (BackhaulSpec spec : s().backhaul.values()) {
                lore.add("<gray>• <white>" + esc(spec.name()) + " <dark_gray>— " + Math.round(spec.multiplier() * 100) + "%");
            }
            inv.setItem(SLOT_BACKHAUL, item(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "<yellow>Backhaul slot (empty)", lore));
            inv.setItem(SLOT_DISCARD, filler);
        } else {
            String name = bh == null ? t.backhaul().name() : bh.name();
            int pct = bh == null ? 0 : (int) Math.round(bh.multiplier() * 100);
            inv.setItem(SLOT_BACKHAUL, item(t.backhaul(), "<aqua>Backhaul: " + esc(name), List.of(
                    "<gray>Capacity: <white>" + pct + "%", "<dark_gray>Locked")));
            inv.setItem(SLOT_DISCARD, item(Material.LAVA_BUCKET, "<red>Discard backhaul", List.of(
                    "<gray>Destroys the installed block.", "<gray>It will <red>not</red> be returned.",
                    "", "<yellow>Click twice to confirm")));
        }

        // Towers are fixed models: no upgrades. Show what this one is.
        inv.setItem(SLOT_UPGRADE, item(Material.LIGHTNING_ROD, "<gold>" + esc(plugin.towers().modelName(t)), List.of(
                "<gray>Generation: <white>" + t.generation(),
                "<gray>Made by: <white>" + esc(makerName(t)),
                "<gray>Fixed model: install a newer one to upgrade.")));

        inv.setItem(SLOT_CLOSE, item(Material.BARRIER, "<red>Close", List.of()));
    }

    // ------------------------------------------------------------------ events

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getView().getTopInventory().getHolder(false) instanceof Holder)) return;
        int top = e.getView().getTopInventory().getSize();
        for (int raw : e.getRawSlots()) {
            if (raw < top) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder(false) instanceof Holder h)) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Tower t = plugin.store().tower(h.towerId);
        if (t == null || !plugin.towers().canManage(p, t)) {
            e.setCancelled(true);
            p.closeInventory();
            return;
        }

        boolean top = e.getClickedInventory() == e.getView().getTopInventory();
        if (!top) {
            // Bottom inventory: normal clicks are fine, shift-click may install a backhaul.
            if (e.isShiftClick()) {
                e.setCancelled(true);
                ItemStack it = e.getCurrentItem();
                if (it != null && s().backhaul.containsKey(it.getType())) {
                    if (install(p, t, it.getType())) {
                        it.setAmount(it.getAmount() - 1);
                        e.setCurrentItem(it.getAmount() <= 0 ? null : it);
                    }
                }
            } else if (e.getClick() == ClickType.DOUBLE_CLICK) {
                e.setCancelled(true);
            }
            return;
        }

        e.setCancelled(true);
        int slot = e.getSlot();
        if (slot == SLOT_CLOSE) {
            p.closeInventory();
        } else if (slot >= FIRST_BAND && slot < FIRST_BAND + Band.values().length) {
            toggleBand(p, t, Band.values()[slot - FIRST_BAND]);
        } else if (slot == SLOT_BACKHAUL) {
            ItemStack cursor = e.getCursor();
            if (t.backhaul() != null) {
                Msg.send(p, "<red>The backhaul block is locked. Use <white>Discard</white> to destroy it first.");
            } else if (cursor.getType().isAir() || !s().backhaul.containsKey(cursor.getType())) {
                Msg.send(p, "<gray>Hold a backhaul block on your cursor and click the slot.");
            } else if (install(p, t, cursor.getType())) {
                cursor.setAmount(cursor.getAmount() - 1);
                p.setItemOnCursor(cursor.getAmount() <= 0 ? null : cursor);
            }
        } else if (slot == SLOT_DISCARD && t.backhaul() != null) {
            long now = System.currentTimeMillis();
            Long until = h.discardConfirm.get(p.getUniqueId());
            if (until != null && until > now) {
                h.discardConfirm.remove(p.getUniqueId());
                String name = pretty(t.backhaul());
                t.backhaul(null);
                p.playSound(p.getLocation(), Sound.BLOCK_LAVA_EXTINGUISH, 1f, 1f);
                Msg.send(p, "<red>Discarded the <white><block></white> backhaul. Tower <id> is offline.",
                        Msg.p("block", name), Msg.p("id", t.name()));
                plugin.towers().refresh(t);
            } else {
                h.discardConfirm.put(p.getUniqueId(), now + CONFIRM_MS);
                Msg.send(p, "<yellow>Click discard again within 5 seconds to destroy the backhaul block.");
            }
        }
    }

    private int bonusPct(Tower t) {
        return (int) Math.round((s().rangeMultiplier(t.heightAboveGround()) - 1) * 100);
    }

    private boolean install(Player p, Tower t, Material m) {
        if (t.backhaul() != null) {
            Msg.send(p, "<red>A backhaul block is already installed and locked.");
            return false;
        }
        t.backhaul(m);
        p.playSound(p.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.7f, 1.4f);
        Msg.send(p, "<green>Installed <white><block></white> backhaul on tower <id>. It is now locked.",
                Msg.p("block", pretty(m)), Msg.p("id", t.name()));
        plugin.towers().refresh(t);
        return true;
    }

    private void toggleBand(Player p, Tower t, Band b) {
        if (!plugin.towers().available(t, b)) {
            Msg.send(p, "<red>This tower model doesn't include <band>.", Msg.p("band", s().spec(b).name()));
            return;
        }
        if (!t.enabledBands().remove(b)) t.enabledBands().add(b);
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, t.enabledBands().contains(b) ? 1.4f : 0.8f);
        plugin.towers().refresh(t);
    }

    private void upgrade(Player p, Tower t) {
        Generation next = s().generations.get(t.generation() + 1);
        if (next == null) return;
        boolean free = p.hasPermission("mccomm.admin");
        if (!free && next.upgradeMaterial() != null) {
            ItemStack cost = new ItemStack(next.upgradeMaterial(), next.upgradeAmount());
            if (!p.getInventory().containsAtLeast(cost, next.upgradeAmount())) {
                Msg.send(p, "<red>You need <white><amount>× <item></white> to upgrade.",
                        Msg.p("amount", "" + next.upgradeAmount()), Msg.p("item", pretty(next.upgradeMaterial())));
                return;
            }
            p.getInventory().removeItem(cost);
        }
        t.generation(next.level());
        for (Band b : Band.values()) if (s().spec(b).minGeneration() == next.level()) t.enabledBands().add(b);
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
        Msg.send(p, "<green>Tower <id> upgraded to <white><model></white> (Gen <gen>).", Msg.p("id", t.name()),
                Msg.p("model", next.model()), Msg.p("gen", "" + next.level()));
        plugin.towers().refresh(t);
    }

    // ----------------------------------------------------------------- helpers

    private static ItemStack item(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        it.editMeta(meta -> {
            meta.displayName(Msg.item(name));
            List<Component> l = new ArrayList<>();
            for (String line : lore) l.add(Msg.item(line));
            meta.lore(l);
        });
        return it;
    }

    private static String esc(String s) {
        return net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(s);
    }

    private String makerName(Tower t) {
        var m = plugin.store().manufacturer(t.manufacturerId());
        return m == null ? "Unknown" : m.name();
    }

    public static String pretty(Material m) {
        String[] parts = m.name().toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }
}
