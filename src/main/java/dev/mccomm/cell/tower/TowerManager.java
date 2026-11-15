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
        return b != null && b.getBlockData() instanceof WallSign
                && b.getState() instanceof org.bukkit.block.Sign;
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
        if (p.hasPermission("celltowers.admin")) return true;
        Carrier c = plugin.store().carrier(t.carrierId());
        return c != null && c.isStaff(p.getUniqueId());
    }

    // ------------------------------------------------------------- lifecycle

    public Tower create(Player creator, Block sign, Carrier carrier, String name) {
        Tower t = new Tower(plugin.store().allocateTowerId(), carrier.id(), sign.getWorld().getUID(),
                sign.getX(), sign.getY(), sign.getZ(), 1, creator.getUniqueId(), System.currentTimeMillis());
        t.name(name);
        for (Band b : Band.values()) if (s().supports(1, b)) t.enabledBands().add(b);
        plugin.store().addTower(t);
        refresh(t);
        return t;
    }

    /** Removes the tower and its entities. The backhaul block is destroyed, never dropped. */
    public void remove(Tower t, boolean clearSign) {
        plugin.gui().closeAll(t);
        removeEntity(t.displayId());
        removeEntity(t.panelId());
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
        if (t.isChunkLoaded()) {
            measureHeight(t);
            Block b = t.block();
            if (b != null && isWallSign(b)) {
                writeSign(t, b);
                ensureEntities(t);
                Entity d = t.displayId() == null ? null : Bukkit.getEntity(t.displayId());
                if (d instanceof TextDisplay td) applyText(t, td);
            }
        }
        plugin.gui().refreshAll(t);
    }

    // ---------------------------------------------------------------- entities

    /** Spawns the panel/text if they are missing. Only acts once the chunk's entities are loaded. */
    public void ensureEntities(Tower t) {
        World w = t.world();
        if (w == null || !w.isChunkLoaded(t.x() >> 4, t.z() >> 4)) return;
        if (!w.getChunkAt(t.x() >> 4, t.z() >> 4).isEntitiesLoaded()) return;
        Block b = t.block();
        if (b == null || !(b.getBlockData() instanceof WallSign ws)) return;

        Settings.SignGeom g = s().wallGeom;
        Vector facing = ws.getFacing().getDirection();
        Location face = new Location(w, t.x() + 0.5, t.y() + g.centerY(), t.z() + 0.5)
                .add(facing.clone().multiply(g.faceOffset()));
        float yaw = (float) Math.toDegrees(Math.atan2(-facing.getX(), facing.getZ()));

        Entity panel = t.panelId() == null ? null : Bukkit.getEntity(t.panelId());
        if (panel == null || !panel.isValid()) {
            Location loc = face.clone().add(facing.clone().multiply(PANEL_DEPTH / 2 + 0.002));
            loc.setYaw(yaw);
            loc.setPitch(0);
            BlockData data = s().panelMaterial.createBlockData();
            BlockDisplay bd = w.spawn(loc, BlockDisplay.class, e -> {
                e.setBlock(data);
                e.setTransformation(new Transformation(
                        new Vector3f((float) -g.width() / 2, (float) -g.height() / 2, (float) -PANEL_DEPTH / 2),
                        new Quaternionf(),
                        new Vector3f((float) g.width(), (float) g.height(), (float) PANEL_DEPTH),
                        new Quaternionf()));
                if (s().fullBright) e.setBrightness(new Display.Brightness(15, 15));
                tag(e, t);
            });
            t.panelId(bd.getUniqueId());
            plugin.store().markDirty();
        }

        Entity text = t.displayId() == null ? null : Bukkit.getEntity(t.displayId());
        if (text == null || !text.isValid()) {
            Location loc = face.clone().add(facing.clone().multiply(PANEL_DEPTH + 0.006));
            loc.setYaw(yaw + s().yawOffset);
            loc.setPitch(0);
            TextDisplay td = w.spawn(loc, TextDisplay.class, e -> {
                e.setBillboard(Display.Billboard.FIXED);
                e.setAlignment(TextDisplay.TextAlignment.CENTER);
                e.setDefaultBackground(false);
                e.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                e.setShadowed(false);
                e.setSeeThrough(false);
                e.setLineWidth(1000);
                if (s().fullBright) e.setBrightness(new Display.Brightness(15, 15));
                tag(e, t);
            });
            t.displayId(td.getUniqueId());
            plugin.store().markDirty();
            applyText(t, td);
        }
    }

    private void tag(Entity e, Tower t) {
        e.setPersistent(true);
        e.getPersistentDataContainer().set(towerKey, PersistentDataType.INTEGER, t.id());
    }

    /** Sets text and scales it so the whole block of text fits inside the sign board. */
    private void applyText(Tower t, TextDisplay td) {
        List<Component> lines = screenLines(t);
        Component text = Component.join(net.kyori.adventure.text.JoinConfiguration.newlines(), lines);
        double maxPx = 0;
        for (Component line : lines) {
            String plain = PlainTextComponentSerializer.plainText().serialize(line);
            maxPx = Math.max(maxPx, plain.length() * CHAR_PX);
        }
        Settings.SignGeom g = s().wallGeom;
        double fill = s().textFill;
        double scaleH = fill * g.height() * 40 / (LINES * LINE_PX);
        double scaleW = maxPx <= 0 ? scaleH : fill * g.width() * 40 / maxPx;
        float scale = (float) Math.min(scaleH, scaleW);
        float textHeight = (float) (LINES * LINE_PX * scale / 40);

        td.text(text);
        td.setTransformation(new Transformation(
                new Vector3f(0, -textHeight / 2, 0),
                new Quaternionf(),
                new Vector3f(scale, scale, scale),
                new Quaternionf()));
    }

    private List<Component> screenLines(Tower t) {
        Carrier c = plugin.store().carrier(t.carrierId());
        TextColor color = c == null ? NamedTextColor.WHITE : c.color();
        List<Component> out = new ArrayList<>(LINES);
        out.add(Component.text(c == null ? "?" : c.name(), color, TextDecoration.BOLD));
        out.add(Component.text(t.name(), NamedTextColor.WHITE)
                .append(Component.text(" · " + s().generation(t.generation()).model() + " (G" + t.generation() + ")", NamedTextColor.GRAY)));

        Component lte = Component.empty();
        Band[] low = {Band.G2, Band.G3, Band.G4, Band.G4_PLUS};
        for (int i = 0; i < low.length; i++) {
            if (i > 0) lte = lte.append(Component.space());
            lte = lte.append(bandText(t, low[i], s().spec(low[i]).name()));
        }
        out.add(lte);

        Component nr = Component.text("5G ", NamedTextColor.WHITE)
                .append(bandText(t, Band.G5_LOW, "L")).append(Component.space())
                .append(bandText(t, Band.G5_MID, "M")).append(Component.space())
                .append(bandText(t, Band.G5_HIGH, "H")).append(Component.space())
                .append(bandText(t, Band.G5_MMWAVE, "mm"));
        out.add(nr);

        int stage = plugin.billing().decayStage(t);
        if (t.backhaul() == null) {
            out.add(Component.text("● Offline · no backhaul", NamedTextColor.RED));
        } else if (stage >= s().silentStage()) {
            out.add(Component.text("● Silent · upkeep unpaid", NamedTextColor.DARK_RED));
        } else if (stage > 0) {
            out.add(Component.text("● Degraded · upkeep unpaid", NamedTextColor.GOLD));
        } else if (t.enabledBands().isEmpty()) {
            out.add(Component.text("● Offline · no bands", NamedTextColor.RED));
        } else {
            BackhaulSpec bh = s().backhaul.get(t.backhaul());
            String name = bh == null ? t.backhaul().name() : bh.name();
            int pct = bh == null ? 0 : (int) Math.round(bh.multiplier() * 100);
            int users = plugin.signal().usersOn(t);
            out.add(Component.text("● ", NamedTextColor.GREEN)
                    .append(Component.text(name + " " + pct + "% · " + users + (users == 1 ? " user" : " users"), NamedTextColor.GRAY)));
        }
        return out;
    }

    private Component bandText(Tower t, Band b, String label) {
        NamedTextColor col = t.enabledBands().contains(b) ? NamedTextColor.GREEN
                : s().supports(t.generation(), b) ? NamedTextColor.RED : NamedTextColor.DARK_GRAY;
        return Component.text(label, col);
    }

    private void writeSign(Tower t, Block b) {
        if (!(b.getState() instanceof org.bukkit.block.Sign sign)) return;
        Carrier c = plugin.store().carrier(t.carrierId());
        SignSide side = sign.getSide(Side.FRONT);
        side.line(0, Msg.mm("<dark_aqua>[Cell Tower]"));
        side.line(1, Component.text(c == null ? "?" : c.name()));
        side.line(2, Component.text(s().generation(t.generation()).model()));
        side.line(3, Component.text(t.name() + " · Gen " + t.generation()));
        sign.setWaxed(true);
        sign.update(false, false);
    }

    // --------------------------------------------------------------- upkeep

    /** Removes towers whose sign is gone, and respawns missing screens. Runs periodically. */
    public void validateLoaded() {
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
            if (t == null || !(e.getUniqueId().equals(t.displayId()) || e.getUniqueId().equals(t.panelId()))) {
                e.remove();
            }
        }
    }

    public void refreshAll() {
        for (Tower t : new ArrayList<>(plugin.store().towers())) refresh(t);
    }
}
