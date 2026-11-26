package dev.mccomm.cell.tower;

import dev.mccomm.cell.CellPlugin;
import dev.mccomm.cell.Settings;
import dev.mccomm.cell.band.BackhaulSpec;
import dev.mccomm.cell.band.Band;
import dev.mccomm.cell.model.BlockKey;
import dev.mccomm.cell.model.Carrier;
import dev.mccomm.cell.model.Tower;
import dev.mccomm.cell.util.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Creates, removes and renders towers: the sign, the screen panel and the screen text. */
public final class TowerManager {
    private static final int LINES = 5;
    private static final double LINE_PX = 10; // text display line height in font pixels
    private static final double CHAR_PX = 6;  // average glyph advance incl. spacing
    private static final double PANEL_DEPTH = 0.01;

    private final CellPlugin plugin;
    private final NamespacedKey towerKey;

    public TowerManager(CellPlugin plugin) {
        this.plugin = plugin;
        this.towerKey = new NamespacedKey(plugin, "tower");
    }

    public NamespacedKey towerKey() {
        return towerKey;
    }

    private Settings s() {
        return plugin.settings();
    }

    // ------------------------------------------------------------------ queries

    /** Only regular wall signs can be towers (no standing or hanging signs). */
    public static boolean isWallSign(Block b) {
        // Block data alone: getState() copies the whole sign and is far slower, and a WallSign block is always a sign.
        return b != null && b.getBlockData() instanceof WallSign;
    }

    public Tower towerAt(Block b) {
        return b == null ? null : plugin.store().towerAt(BlockKey.of(b));
    }

    /** Tower whose sign hangs on this block, if any. */
    public List<Tower> towersAttachedTo(Block support) {
        List<Tower> out = new ArrayList<>();
        for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Block rel = support.getRelative(f);
            Tower t = towerAt(rel);
            if (t != null && rel.getBlockData() instanceof WallSign ws && ws.getFacing() == f) out.add(t);
        }
        return out;
    }

    public Tower towerOf(Entity e) {
        Integer id = e.getPersistentDataContainer().get(towerKey, PersistentDataType.INTEGER);
        return id == null ? null : plugin.store().tower(id);
    }

    public boolean canManage(Player p, Tower t) {
        if (p.hasPermission("mccomm.admin")) return true;
        Carrier c = plugin.store().carrier(t.carrierId());
        return c != null && c.isStaff(p.getUniqueId());
    }

    // ------------------------------------------------------------- lifecycle

    public Tower create(Player creator, Block sign, Carrier carrier, String name,
                        dev.mccomm.cell.model.Manufacturer maker, dev.mccomm.cell.model.TowerModel model) {
        Tower t = new Tower(plugin.store().allocateTowerId(), carrier.id(), sign.getWorld().getUID(),
                sign.getX(), sign.getY(), sign.getZ(), 1, creator.getUniqueId(), System.currentTimeMillis());
        t.name(name);
        t.manufacturerId(maker.id());
        t.modelName(model.name());
        t.generation(model.generation());
        t.modelBands().addAll(model.bands());
        t.enabledBands().addAll(model.bands());
        t.small(model.small());
        plugin.store().addTower(t);
        refresh(t);
        return t;
    }

    /** Removes the tower and its entities. The backhaul block is destroyed, never dropped. */
    public void remove(Tower t, boolean clearSign) {
        plugin.gui().closeAll(t);
        removeEntity(t.displayId());
        removeEntity(t.panelId());
        plugin.screen().remove(t);
        if (clearSign && t.isChunkLoaded()) {
            Block b = t.block();
            if (b != null && b.getState() instanceof org.bukkit.block.Sign sign) {
                SignSide side = sign.getSide(Side.FRONT);
                for (int i = 0; i < 4; i++) side.line(i, Component.empty());
                sign.setWaxed(false);
                sign.update();
            }
        }
        plugin.store().removeTower(t);
        plugin.signal().forget(t);
    }

    private static void removeEntity(UUID id) {
        if (id == null) return;
        Entity e = Bukkit.getEntity(id);
        if (e != null) e.remove();
    }

    /** Re-renders sign, screen and any open GUIs. Safe to call often. */
    public void refresh(Tower t) {
        plugin.store().markDirty();
        if (plugin.billing().warping()) return; // every tower is refreshed once when the timewarp ends
        if (t.isChunkLoaded()) {
            measureHeight(t);
            Block b = t.block();
            if (b != null && isWallSign(b)) {
                writeSign(t, b);
                plugin.screen().ensure(t);
            }
        }
        plugin.gui().refreshAll(t);
    }

    // ---------------------------------------------------------------- entities

    /** Spawns or repairs the tower's screen. Only acts once the chunk's entities are loaded. */
    public void ensureEntities(Tower t) {
        plugin.screen().ensure(t);
    }

    /** Reach of one band from this tower: macro cells get the height bonus, small cells a fixed share (all of it on 5G High and mmWave). */
    public double range(Tower t, Band b) {
        double base = s().spec(b).range();
        return t.small() ? base * s().smallCells.rangeShare(b) : base * s().rangeMultiplier(t.heightAboveGround());
    }

    /** Small cells linked wirelessly to this macro cell. */
    public int linkedCount(Tower macro) {
        int n = 0;
        for (Tower t : plugin.store().towers()) if (t.small() && t.backhaul() == null && t.wirelessLink() == macro.id()) n++;
        return n;
    }

    /**
     * Links every small cell without a backhaul block to the nearest working macro cell of its
     * carrier within range (or unlinks it). Runs with the periodic validation.
     */
    public void updateWireless() {
        int range = s().smallCells.wirelessRange();
        for (Tower t : plugin.store().towers()) {
            if (!t.small()) continue;
            int before = t.wirelessLink();
            int link = 0;
            if (t.backhaul() == null && range > 0) {
                double best = (double) range * range;
                for (Tower m : plugin.store().towers()) {
                    if (m.small() || m.backhaul() == null || m.enabledBands().isEmpty() || !m.carrierId().equals(t.carrierId())
                            || !m.worldId().equals(t.worldId())) continue;
                    if (plugin.billing().decayStage(m) >= s().silentStage() || plugin.workforce().silent(m)) continue;
                    double dx = m.x() - t.x(), dy = m.y() - t.y(), dz = m.z() - t.z();
                    double d2 = dx * dx + dy * dy + dz * dz;
                    if (d2 <= best) {
                        best = d2;
                        link = m.id();
                    }
                }
            }
            t.wirelessLink(link);
            if (before != link) refresh(t);
        }
    }

    /** The model name shown for this tower. */
    public String modelName(Tower t) {
        return t.modelName() != null ? t.modelName() : s().generation(t.generation()).model();
    }

    /** True if this tower's model includes the band (it can be switched on). */
    public boolean available(Tower t, Band b) {
        if (!s().supports(t.generation(), b)) return false;
        if (t.small() && s().spec(b).minGeneration() < s().smallCells.minGeneration()) return false;
        return t.modelBands().isEmpty() || t.modelBands().contains(b);
    }

    private void writeSign(Tower t, Block b) {
        if (!(b.getState() instanceof org.bukkit.block.Sign sign)) return;
        Carrier c = plugin.store().carrier(t.carrierId());
        SignSide side = sign.getSide(Side.FRONT);
        side.line(0, Msg.mm(t.small() ? "<dark_aqua>[Small Cell]" : "<dark_aqua>[Cell Tower]"));
        side.line(1, Component.text(c == null ? "?" : c.name()));
        side.line(2, Component.text(s().generation(t.generation()).model()));
        side.line(3, Component.text(t.name() + " · Gen " + t.generation()));
        sign.setWaxed(true);
        sign.update(false, false);
    }

    // --------------------------------------------------------------- upkeep

    /** Removes towers whose sign is gone, and respawns missing screens. Runs periodically. */
    public void validateLoaded() {
        updateWireless();
        for (Tower t : new ArrayList<>(plugin.store().towers())) {
            if (!t.isChunkLoaded()) continue;
            Block b = t.block();
            if (!isWallSign(b)) {
                plugin.getLogger().info("Tower " + t.name() + " lost its sign, removing.");
                remove(t, false);
                continue;
            }
            ensureEntities(t);
            if (measureHeight(t)) plugin.gui().refreshAll(t);
        }
    }

    /**
     * Measures the antenna's height above the surrounding terrain: the median ground height
     * (ignoring leaves) at 16 points around the tower, in loaded chunks only.
     *
     * @return true if the stored height changed noticeably
     */
    public boolean measureHeight(Tower t) {
        World w = t.world();
        if (w == null) return false;
        int r = s().heightSampleRadius;
        List<Integer> ground = new ArrayList<>();
        for (int ring = 1; ring <= 2; ring++) {
            for (int i = 0; i < 8; i++) {
                double a = Math.PI / 4 * i;
                int x = t.x() + (int) Math.round(Math.cos(a) * r * ring);
                int z = t.z() + (int) Math.round(Math.sin(a) * r * ring);
                if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                ground.add(w.getHighestBlockYAt(x, z, org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES));
            }
        }
        if (ground.size() < 4) return false;
        ground.sort(Integer::compare);
        double median = ground.get(ground.size() / 2);
        double antennaY = t.y() + 0.5 + s().antennaHeight;
        double h = Math.max(0, antennaY - (median + 1));
        if (Math.abs(h - t.heightAboveGround()) < 0.5) return false;
        t.heightAboveGround(h);
        plugin.store().markDirty();
        return true;
    }

    /** Called when a chunk's entities load: drops orphaned or duplicate screen entities. */
    public void cleanEntities(List<Entity> entities) {
        for (Entity e : entities) {
            Integer id = e.getPersistentDataContainer().get(towerKey, PersistentDataType.INTEGER);
            if (id == null) continue;
            Tower t = plugin.store().tower(id);
            if (t == null || !plugin.screen().owns(t, e.getUniqueId())) {
                e.remove();
            }
        }
    }

    public void refreshAll() {
        for (Tower t : new ArrayList<>(plugin.store().towers())) refresh(t);
    }
}
