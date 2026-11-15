package dev.mccomm.cell.tower;

import dev.mccomm.cell.CellPlugin;
import dev.mccomm.cell.Settings;
import dev.mccomm.cell.band.BackhaulSpec;
import dev.mccomm.cell.band.Band;
import dev.mccomm.cell.model.Carrier;
import dev.mccomm.cell.model.Tower;
import dev.mccomm.cell.util.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The tower's screen: a layered set of display entities exactly covering the sign face.
 * <pre>
 *  ▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔  accent stripe (carrier colour)
 *  Nortus-Telecom          ● LIVE  header: carrier name (its gradient) + status badge
 *  LF1                [2G][3G][4G][4G+]
 *  CT-400 NR · Gen 4  [5L][5M][5H][mm]   big tower name, model; band chips
 *  ||||| 3 users        Diamond 70% · +26%   footer bar: load, backhaul, height bonus
 * </pre>
 * Every entity has a role; they're spawned once and updated in place.
 */
public final class TowerScreen {
    private static final List<String> ROLES = List.of("panel", "accent", "footbar", "head", "badge", "name", "model",
            "chip0", "chip1", "chip2", "chip3", "chip4", "chip5", "chip6", "chip7", "footl", "footr");
    private static final String[] CHIP_LABELS = {" 2G ", " 3G ", " 4G ", "4G+", " 5L ", " 5M ", " 5H ", "mm"};
    private static final double M = 0.035; // side margin in blocks

    private final CellPlugin plugin;

    public TowerScreen(CellPlugin plugin) {
        this.plugin = plugin;
    }

    private Settings s() {
        return plugin.settings();
    }

    // ------------------------------------------------------------------ geometry

    private record Geo(World w, Location face, Vector f, Vector right, float yaw, double W, double H) {
        Location at(double x, double y, double depth) {
            return face.clone().add(right.clone().multiply(x)).add(0, y, 0).add(f.clone().multiply(depth));
        }
    }

    private Geo geo(Tower t) {
        World w = t.world();
        Block b = t.block();
        if (w == null || b == null || !(b.getBlockData() instanceof WallSign ws)) return null;
        Settings.SignGeom g = s().wallGeom;
        Vector f = ws.getFacing().getDirection();
        Vector right = new Vector(f.getZ(), 0, -f.getX());
        Location face = new Location(w, t.x() + 0.5, t.y() + g.centerY(), t.z() + 0.5).add(f.clone().multiply(g.faceOffset()));
        float yaw = (float) Math.toDegrees(Math.atan2(-f.getX(), f.getZ()));
        return new Geo(w, face, f, right, yaw, g.width(), g.height());
    }

    // ------------------------------------------------------------------ lifecycle

    /** Spawns the screen if any part is missing (after removing leftovers), then updates it. */
    public void ensure(Tower t) {
        World w = t.world();
        if (w == null || !w.isChunkLoaded(t.x() >> 4, t.z() >> 4)) return;
        if (!w.getChunkAt(t.x() >> 4, t.z() >> 4).isEntitiesLoaded()) return;
        Geo g = geo(t);
        if (g == null) return;
        // Entities from the old single-text screen.
        removeId(t.displayId());
        removeId(t.panelId());
        t.displayId(null);
        t.panelId(null);

        boolean complete = true;
        for (String role : ROLES) {
            Entity e = entity(t, role);
            if (e == null || !e.isValid()) {
                complete = false;
                break;
            }
        }
        if (!complete) {
            remove(t);
            spawn(t, g);
            plugin.store().markDirty();
        }
        update(t);
    }

    public void remove(Tower t) {
        for (UUID id : t.screen().values()) removeId(id);
        t.screen().clear();
    }

    private static void removeId(UUID id) {
        if (id == null) return;
        Entity e = Bukkit.getEntity(id);
        if (e != null) e.remove();
    }

    private Entity entity(Tower t, String role) {
        UUID id = t.screen().get(role);
        return id == null ? null : Bukkit.getEntity(id);
    }

    public boolean owns(Tower t, UUID entity) {
        return t.screen().containsValue(entity) || entity.equals(t.displayId()) || entity.equals(t.panelId());
    }

    private void spawn(Tower t, Geo g) {
        double W = g.W(), H = g.H();
        rect(t, g, "panel", 0, 0, W, H, 0.006, 0.01, s().panelMaterial);
        rect(t, g, "accent", 0, H / 2 - 0.012, W, 0.024, 0.013, 0.004, Material.LIGHT_BLUE_CONCRETE);
        rect(t, g, "footbar", 0, -H / 2 + 0.042, W, 0.084, 0.013, 0.004, Material.GRAY_CONCRETE);
        text(t, g, "head", -W / 2 + M, H / 2 - 0.068);
        text(t, g, "badge", W / 2 - M, H / 2 - 0.068);
        text(t, g, "name", -W / 2 + M, 0.035);
        text(t, g, "model", -W / 2 + M, -0.075);
        for (int i = 0; i < 8; i++) {
            double[] c = chipCenter(g, i);
            text(t, g, "chip" + i, c[0], c[1]);
        }
        text(t, g, "footl", -W / 2 + M, -H / 2 + 0.042);
        text(t, g, "footr", W / 2 - M, -H / 2 + 0.042);
    }

    private static double[] chipCenter(Geo g, int i) {
        double left = 0.02, right = g.W() / 2 - M, gap = 0.012;
        double cw = (right - left - 3 * gap) / 4;
        int col = i % 4, row = i / 4;
        return new double[]{left + cw / 2 + col * (cw + gap), row == 0 ? 0.062 : -0.008};
    }

    private void rect(Tower t, Geo g, String role, double x, double y, double w, double h, double depth, double thick, Material m) {
        Location loc = g.at(x, y, depth + thick / 2);
        loc.setYaw(g.yaw());
        loc.setPitch(0);
        BlockDisplay bd = g.w().spawn(loc, BlockDisplay.class, e -> {
            e.setBlock(m.createBlockData());
            e.setTransformation(new Transformation(
                    new Vector3f((float) -w / 2, (float) -h / 2, (float) -thick / 2), new Quaternionf(),
                    new Vector3f((float) w, (float) h, (float) thick), new Quaternionf()));
            if (s().fullBright) e.setBrightness(new Display.Brightness(15, 15));
            tag(e, t);
        });
        t.screen().put(role, bd.getUniqueId());
    }

    private void text(Tower t, Geo g, String role, double x, double y) {
        Location loc = g.at(x, y, 0.022);
        loc.setYaw(g.yaw() + s().yawOffset);
        loc.setPitch(0);
        TextDisplay td = g.w().spawn(loc, TextDisplay.class, e -> {
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
        t.screen().put(role, td.getUniqueId());
    }

    private void tag(Entity e, Tower t) {
        e.setPersistent(true);
        e.getPersistentDataContainer().set(plugin.towers().towerKey(), PersistentDataType.INTEGER, t.id());
    }

    // ------------------------------------------------------------------ content

    private enum State { LIVE, DEGRADED, SILENT, OFFLINE }

    /** Updates every part's text and colours. Cheap enough to call on every refresh. */
    public void update(Tower t) {
        Geo g = geo(t);
        if (g == null) return;
        Carrier c = plugin.store().carrier(t.carrierId());
        int stage = plugin.billing().decayStage(t);
        State state = t.backhaul() == null || t.enabledBands().isEmpty() ? State.OFFLINE
                : stage >= s().silentStage() ? State.SILENT : stage > 0 ? State.DEGRADED : State.LIVE;
        boolean dim = state == State.OFFLINE || state == State.SILENT;

        if (entity(t, "accent") instanceof BlockDisplay acc) {
            acc.setBlock(nearestConcrete(c == null ? 0x55FFFF : c.color().value()).createBlockData());
        }

        double W = g.W();
        setText(t, "head", c == null ? Msg.mm("<white><b>?") : Msg.mm("<b>" + c.styled()), 0.19, W * 0.62, Align.LEFT, null);
        String badge = switch (state) {
            case LIVE -> "<#D9FFE6>● LIVE";
            case DEGRADED -> "<#FFF3D6>▲ DEGRADED";
            case SILENT -> "<#FFD6D6>■ SILENT";
            case OFFLINE -> "<#D0D0D0>○ OFFLINE";
        };
        Color badgeBg = switch (state) {
            case LIVE -> Color.fromARGB(255, 31, 122, 63);
            case DEGRADED -> Color.fromARGB(255, 183, 121, 31);
            case SILENT -> Color.fromARGB(255, 122, 31, 31);
            case OFFLINE -> Color.fromARGB(255, 58, 61, 69);
        };
        setText(t, "badge", Msg.mm(badge), 0.14, W * 0.32, Align.RIGHT, badgeBg);
        setText(t, "name", Msg.mm((dim ? "<#7A7F8A>" : "<white>") + "<b>" + Msg.escape(t.name())), 0.6, W * 0.44, Align.LEFT, null);
        setText(t, "model", Msg.mm("<#9AA0AA>" + Msg.escape(plugin.towers().modelName(t)) + " · Gen " + t.generation()),
                0.15, W * 0.46, Align.LEFT, null);

        Band[] bands = Band.values();
        for (int i = 0; i < 8 && i < bands.length; i++) {
            Band b = bands[i];
            boolean available = plugin.towers().available(t, b);
            boolean on = available && t.enabledBands().contains(b);
            String fg = on ? (dim ? "<#9FB8A8>" : "<#E6FFEE>") : available ? "<#8A8F9A>" : "<#3A3D45>";
            Color bg = on ? (dim ? Color.fromARGB(255, 40, 66, 50) : Color.fromARGB(255, 31, 122, 63))
                    : available ? Color.fromARGB(255, 42, 45, 53) : Color.fromARGB(255, 22, 24, 29);
            setText(t, "chip" + i, Msg.mm(fg + "<b>" + CHIP_LABELS[i]), 0.13, 1, Align.CENTER, bg);
        }

        String left;
        String right;
        switch (state) {
            case OFFLINE -> {
                left = t.backhaul() == null ? "<#9AA0AA>No backhaul installed" : "<#9AA0AA>All bands switched off";
                right = "";
            }
            case SILENT -> {
                left = "<#FF8A8A>No service · upkeep unpaid";
                right = "";
            }
            default -> {
                int users = plugin.signal().usersOn(t);
                int capacity = Math.max(1, s().capacity(t.generation()) * Math.max(1, t.enabledBands().size()));
                int lit = Math.min(5, (int) Math.ceil(5.0 * users / capacity));
                String barCol = lit >= 4 ? "<#FF8A5C>" : lit >= 3 ? "<#FFD86B>" : "<#5EE38A>";
                StringBuilder bars = new StringBuilder("<b>");
                for (int i = 0; i < 5; i++) bars.append(i < lit ? barCol : "<#3A3D45>").append('|');
                left = bars + "</b> <#C8CCD4>" + users + (users == 1 ? " user" : " users");
                BackhaulSpec bh = s().backhaul.get(t.backhaul());
                int bonus = (int) Math.round((s().rangeMultiplier(t.heightAboveGround()) - 1) * 100);
                String bhName = bh == null ? t.backhaul().name() : bh.name();
                right = "<#9AA0AA>" + Msg.escape(bhName) + " " + Math.round(bh == null ? 0 : bh.multiplier() * 100) + "%"
                        + (bonus > 0 ? " · <#7FDBFF>+" + bonus + "%" : "");
                if (state == State.DEGRADED) {
                    right = "<#FFD86B>Unpaid · " + Math.round(plugin.billing().decaySpeed(t) * 100) + "% speed";
                }
            }
        }
        setText(t, "footl", Msg.mm(left), 0.14, W * 0.5, Align.LEFT, null);
        setText(t, "footr", Msg.mm(right), 0.13, W * 0.46, Align.RIGHT, null);
    }

    private enum Align { LEFT, CENTER, RIGHT }

    /**
     * Sets a text part: scale (shrunk if wider than maxWidth blocks), position relative to its
     * anchor (left edge, centre or right edge) and vertically centred on it.
     */
    private void setText(Tower t, String role, Component text, double scale, double maxWidth, Align align, Color bg) {
        if (!(entity(t, role) instanceof TextDisplay td)) return;
        String plain = PlainTextComponentSerializer.plainText().serialize(text);
        double px = widthPx(plain, true);
        double sc = px <= 0 ? scale : Math.min(scale, maxWidth * 40 / px);
        double width = px * sc / 40;
        double height = 10 * sc / 40;
        double dx = switch (align) {
            case LEFT -> width / 2;
            case CENTER -> 0;
            case RIGHT -> -width / 2;
        };
        if (Math.abs(((s().yawOffset % 360) + 360) % 360 - 180) < 1) dx = -dx; // text rotated round: flip
        td.text(text);
        td.setBackgroundColor(bg == null ? Color.fromARGB(0, 0, 0, 0) : bg);
        td.setTransformation(new Transformation(
                new Vector3f((float) dx, (float) -height / 2, 0), new Quaternionf(),
                new Vector3f((float) sc, (float) sc, (float) sc), new Quaternionf()));
    }

    /** Rough width of Minecraft's default font in pixels (bold adds one per glyph). */
    private static double widthPx(String s, boolean bold) {
        double w = 0;
        for (char ch : s.toCharArray()) {
            w += switch (ch) {
                case 'i', '!', '.', ',', ':', ';', '|', '\'' -> 2;
                case 'l', '`' -> 3;
                case 'I', 't', ' ', '[', ']' -> 4;
                case 'f', 'k', '<', '>', '(', ')', '{', '}', '"', '*' -> 5;
                case '●', '▲', '■', '○' -> 8;
                default -> 6;
            };
            if (bold) w += 1;
        }
        return w;
    }

    private static final Map<Material, Integer> CONCRETE = Map.ofEntries(
            Map.entry(Material.WHITE_CONCRETE, 0xCFD5D6), Map.entry(Material.LIGHT_GRAY_CONCRETE, 0x7D7D73),
            Map.entry(Material.GRAY_CONCRETE, 0x36393D), Map.entry(Material.RED_CONCRETE, 0x8E2121),
            Map.entry(Material.ORANGE_CONCRETE, 0xE06101), Map.entry(Material.YELLOW_CONCRETE, 0xF1AF15),
            Map.entry(Material.LIME_CONCRETE, 0x5EA918), Map.entry(Material.GREEN_CONCRETE, 0x495B24),
            Map.entry(Material.CYAN_CONCRETE, 0x157788), Map.entry(Material.LIGHT_BLUE_CONCRETE, 0x2489C7),
            Map.entry(Material.BLUE_CONCRETE, 0x2D2F8F), Map.entry(Material.PURPLE_CONCRETE, 0x64209C),
            Map.entry(Material.MAGENTA_CONCRETE, 0xA9309F), Map.entry(Material.PINK_CONCRETE, 0xD5658F),
            Map.entry(Material.BROWN_CONCRETE, 0x603C20));

    /** The concrete block whose colour is closest to {@code rgb} (for the accent stripe). */
    static Material nearestConcrete(int rgb) {
        Material best = Material.LIGHT_BLUE_CONCRETE;
        double bestD = Double.MAX_VALUE;
        int r = rgb >> 16 & 255, gr = rgb >> 8 & 255, b = rgb & 255;
        for (var e : CONCRETE.entrySet()) {
            int c = e.getValue();
            double dr = r - (c >> 16 & 255), dg = gr - (c >> 8 & 255), db = b - (c & 255);
            double d = 0.3 * dr * dr + 0.59 * dg * dg + 0.11 * db * db;
            if (d < bestD) {
                bestD = d;
                best = e.getKey();
            }
        }
        return best;
    }
}
