package dev.mccomm.cell.broadband;

import dev.mccomm.cell.CellPlugin;
import dev.mccomm.cell.Settings;
import dev.mccomm.cell.broadband.BroadbandService.Link;
import dev.mccomm.cell.broadband.BroadbandSettings.TechSpec;
import dev.mccomm.cell.model.BlockKey;
import dev.mccomm.cell.tower.TowerScreen;
import dev.mccomm.cell.util.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The same layered screen cell towers have, for ISP nodes, routers and computers: display entities that
 * exactly cover the sign face.
 * <pre>
 *  ▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔  accent stripe (ISP colour)
 *  Provider                ● ONLINE   header + status badge
 *  Name                  [chips]      big name; chips (technology, Wi-Fi generation...)
 *  sub line                  detail   what it is, and one more detail
 *  ▏ load / speed            right    footer bar
 * </pre>
 * Entities are tagged with the sign's position, spawned once and updated in place. They are persistent, so
 * after a restart the first pass removes strays and rebuilds from the saved devices.
 */
public final class DeviceScreen {
    private static final List<String> ROLES = List.of("panel", "accent", "footbar", "head", "badge", "name", "sub",
            "chip0", "chip1", "chip2", "chip3", "mid", "footl", "footr");
    private static final double M = 0.035; // side margin in blocks

    private enum Align { LEFT, CENTER, RIGHT }

    private record Geo(World w, Location face, Vector f, Vector right, float yaw, double W, double H) {
        Location at(double x, double y, double depth) {
            return face.clone().add(right.clone().multiply(x)).add(0, y, 0).add(f.clone().multiply(depth));
        }
    }

    private final CellPlugin plugin;
    private final NamespacedKey tagKey;
    private final Map<BlockKey, Map<String, UUID>> parts = new HashMap<>();

    public DeviceScreen(CellPlugin plugin) {
        this.plugin = plugin;
        this.tagKey = new NamespacedKey(plugin, "bbdevice");
    }

    private Settings s() {
        return plugin.settings();
    }

    private BroadbandService bb() {
        return plugin.broadband();
    }

    private static String tag(BlockKey k) {
        return k.world() + ":" + k.x() + ":" + k.y() + ":" + k.z();
    }

    private static BlockKey parse(String tag) {
        try {
            String[] p = tag.split(":");
            return new BlockKey(UUID.fromString(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ geometry

    private Geo geo(BlockKey k) {
        World w = Bukkit.getWorld(k.world());
        if (w == null || !w.isChunkLoaded(k.x() >> 4, k.z() >> 4)) return null;
        Block b = w.getBlockAt(k.x(), k.y(), k.z());
        if (!(b.getBlockData() instanceof WallSign ws)) return null;
        Settings.SignGeom g = s().wallGeom;
        Vector f = ws.getFacing().getDirection();
        Vector right = new Vector(f.getZ(), 0, -f.getX());
        Location face = new Location(w, k.x() + 0.5, k.y() + g.centerY(), k.z() + 0.5).add(f.clone().multiply(g.faceOffset()));
        float yaw = (float) Math.toDegrees(Math.atan2(-f.getX(), f.getZ()));
        return new Geo(w, face, f, right, yaw, g.width(), g.height());
    }

    // ------------------------------------------------------------------ lifecycle

    /** Spawns the screen if any part is missing (removing leftovers first), then updates it. */
    public void ensure(Device d) {
        BlockKey k = d.key();
        World w = Bukkit.getWorld(k.world());
        if (w == null || !w.isChunkLoaded(k.x() >> 4, k.z() >> 4)) return;
        if (!w.getChunkAt(k.x() >> 4, k.z() >> 4).isEntitiesLoaded()) return;
        Geo g = geo(k);
        if (g == null) return;
        Map<String, UUID> m = parts.get(k);
        boolean complete = m != null;
        if (complete) {
            for (String role : ROLES) {
                Entity e = m.get(role) == null ? null : Bukkit.getEntity(m.get(role));
                if (e == null || !e.isValid()) {
                    complete = false;
                    break;
                }
            }
        }
        if (!complete) {
            remove(k);
            sweep(g, k);
            spawn(k, g);
        }
        update(d);
    }

    /** Removes every part of the screen at this sign. */
    public void remove(BlockKey k) {
        Map<String, UUID> m = parts.remove(k);
        if (m != null) {
            for (UUID id : m.values()) {
                Entity e = Bukkit.getEntity(id);
                if (e != null) e.remove();
            }
        }
        World w = Bukkit.getWorld(k.world());
        if (w != null && w.isChunkLoaded(k.x() >> 4, k.z() >> 4)) {
            Location c = new Location(w, k.x() + 0.5, k.y() + 0.5, k.z() + 0.5);
            String t = tag(k);
            for (Entity e : w.getNearbyEntities(c, 1.3, 1.3, 1.3)) {
                if (t.equals(e.getPersistentDataContainer().get(tagKey, PersistentDataType.STRING))) e.remove();
            }
        }
    }

    /** Removes leftover screen entities of this sign from before a restart. */
    private void sweep(Geo g, BlockKey k) {
        String t = tag(k);
        for (Entity e : g.w().getNearbyEntities(g.face(), 1.3, 1.3, 1.3)) {
            if (t.equals(e.getPersistentDataContainer().get(tagKey, PersistentDataType.STRING))) e.remove();
        }
    }

    /** Called when a chunk's entities load: drops screen entities nothing owns, and rebuilds the ones that are missing. */
    public void onEntitiesLoad(List<Entity> entities, int chunkX, int chunkZ, UUID world) {
        for (Entity e : entities) {
            String t = e.getPersistentDataContainer().get(tagKey, PersistentDataType.STRING);
            if (t == null) continue;
            BlockKey k = parse(t);
            Map<String, UUID> m = k == null ? null : parts.get(k);
            if (k == null || bb().store().deviceAt(k) == null || m == null || !m.containsValue(e.getUniqueId())) e.remove();
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Device d : bb().allDevices()) {
                BlockKey k = d.key();
                if (k.world().equals(world) && k.x() >> 4 == chunkX && k.z() >> 4 == chunkZ) ensure(d);
            }
        });
    }

    private void spawn(BlockKey k, Geo g) {
        double W = g.W(), H = g.H();
        rect(k, g, "panel", 0, 0, W, H, 0.006, 0.01, s().panelMaterial);
        rect(k, g, "accent", 0, H / 2 - 0.012, W, 0.024, 0.013, 0.004, Material.LIGHT_BLUE_CONCRETE);
        rect(k, g, "footbar", 0, -H / 2 + 0.042, W, 0.084, 0.013, 0.004, Material.GRAY_CONCRETE);
        text(k, g, "head", -W / 2 + M, H / 2 - 0.068);
        text(k, g, "badge", W / 2 - M, H / 2 - 0.068);
        text(k, g, "name", -W / 2 + M, 0.035);
        text(k, g, "sub", -W / 2 + M, -0.075);
        for (int i = 0; i < 4; i++) {
            double[] c = chipCenter(g, i);
            text(k, g, "chip" + i, c[0], c[1]);
        }
        text(k, g, "mid", W / 2 - M, -0.008);
        text(k, g, "footl", -W / 2 + M, -H / 2 + 0.042);
        text(k, g, "footr", W / 2 - M, -H / 2 + 0.042);
    }

    private static double[] chipCenter(Geo g, int i) {
        double left = 0.02, right = g.W() / 2 - M, gap = 0.012;
        double cw = (right - left - 3 * gap) / 4;
        return new double[]{left + cw / 2 + i * (cw + gap), 0.062};
    }

    private void put(BlockKey k, String role, Entity e) {
        parts.computeIfAbsent(k, x -> new HashMap<>()).put(role, e.getUniqueId());
    }

    private void tagEntity(Entity e, BlockKey k) {
        e.setPersistent(true);
        e.getPersistentDataContainer().set(tagKey, PersistentDataType.STRING, tag(k));
    }

    private void rect(BlockKey k, Geo g, String role, double x, double y, double w, double h, double depth, double thick, Material m) {
        Location loc = g.at(x, y, depth + thick / 2);
        loc.setYaw(g.yaw());
        loc.setPitch(0);
        BlockDisplay bd = g.w().spawn(loc, BlockDisplay.class, e -> {
            e.setBlock(m.createBlockData());
            e.setTransformation(new Transformation(
                    new Vector3f((float) -w / 2, (float) -h / 2, (float) -thick / 2), new Quaternionf(),
                    new Vector3f((float) w, (float) h, (float) thick), new Quaternionf()));
            if (s().fullBright) e.setBrightness(new Display.Brightness(15, 15));
            tagEntity(e, k);
        });
        put(k, role, bd);
    }

    private void text(BlockKey k, Geo g, String role, double x, double y) {
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
            tagEntity(e, k);
        });
        put(k, role, td);
    }

    // ------------------------------------------------------------------ content

    private enum State { OK, WARN, BAD, OFF }

    private static String badgeText(State s, String label) {
        return switch (s) {
            case OK -> "<#D9FFE6>● " + label;
            case WARN -> "<#FFF3D6>▲ " + label;
            case BAD -> "<#FFD6D6>■ " + label;
            case OFF -> "<#D0D0D0>○ " + label;
        };
    }

    private static Color badgeBg(State s) {
        return switch (s) {
            case OK -> Color.fromARGB(255, 31, 122, 63);
            case WARN -> Color.fromARGB(255, 183, 121, 31);
            case BAD -> Color.fromARGB(255, 122, 31, 31);
            case OFF -> Color.fromARGB(255, 58, 61, 69);
        };
    }

    /** Updates every part's text and colours. Cheap enough to call on every refresh. */
    public void update(Device d) {
        BlockKey k = d.key();
        if (geo(k) == null || parts.get(k) == null) return;
        switch (d) {
            case Node n -> updateNode(k, n);
            case Router r -> updateRouter(k, r);
            case Computer c -> updateComputer(k, c);
            case Extender x -> updateExtender(k, x);
        }
    }

    private void accent(BlockKey k, int rgb) {
        if (part(k, "accent") instanceof BlockDisplay acc) acc.setBlock(TowerScreen.nearestConcrete(rgb).createBlockData());
    }

    private void chip(BlockKey k, int i, String label, boolean on, boolean available) {
        String fg = on ? "<#E6FFEE>" : available ? "<#8A8F9A>" : "<#3A3D45>";
        Color bg = on ? Color.fromARGB(255, 31, 122, 63) : available ? Color.fromARGB(255, 42, 45, 53) : Color.fromARGB(255, 22, 24, 29);
        setText(k, "chip" + i, Msg.mm(fg + "<b>" + label), 0.13, 1, Align.CENTER, bg);
    }

    private void updateNode(BlockKey k, Node n) {
        Isp isp = bb().store().isp(n.ispId());
        TechSpec spec = bb().cfg().spec(n.tech());
        double W = geo(k).W();
        int rgb = isp == null ? 0x55FFFF : isp.colors().getFirst().value();
        accent(k, rgb);
        State st = isp == null || !isp.paidUp() ? State.OFF : n.faulted() ? State.BAD : State.OK;
        boolean dim = st != State.OK;
        setText(k, "head", isp == null ? Msg.mm("<white><b>?") : Msg.mm("<b>" + isp.styled()), 0.19, W * 0.62, Align.LEFT, null);
        setText(k, "badge", Msg.mm(badgeText(st, n.faulted() ? "FAULT" : isp != null && !isp.paidUp() ? "DOWN" : "ONLINE")), 0.14, W * 0.32, Align.RIGHT, badgeBg(st));
        setText(k, "name", Msg.mm((dim ? "<#7A7F8A>" : "<white>") + "<b>" + Msg.escape(n.name())), 0.6, W * 0.44, Align.LEFT, null);
        String stars = isp == null ? "" : " · <#FFD24A>★<#9AA0AA>" + String.format(java.util.Locale.ROOT, "%.1f", bb().rating(isp));
        setText(k, "sub", Msg.mm("<#9AA0AA>" + Msg.escape(spec.name()) + " node · Tier " + n.tier() + stars), 0.15, W * 0.46, Align.LEFT, null);
        Technology[] techs = Technology.nodeTechs();
        String[] labels = {" DSL", " CBL", " FBR", " SAT"};
        for (int i = 0; i < 4; i++) chip(k, i, labels[i], n.tech() == techs[i] && !dim, n.tech() == techs[i]);
        setText(k, "mid", Msg.mm("<#9AA0AA>" + (spec.range() > 0 ? "Reach " + Math.round(spec.range()) + " blocks" : "Reaches anywhere")), 0.13, W * 0.46, Align.RIGHT, null);

        int users = bb().users(n);
        double load = bb().load(n);
        int lit = Math.min(5, (int) Math.ceil(5.0 * load));
        String barCol = lit >= 4 ? "<#FF8A5C>" : lit >= 3 ? "<#FFD86B>" : "<#5EE38A>";
        StringBuilder bars = new StringBuilder("<b>");
        for (int i = 0; i < 5; i++) bars.append(i < lit ? barCol : "<#3A3D45>").append('|');
        String left = n.faulted() ? "<#FF8A8A>Fault · customers offline"
                : !dim ? bars + "</b> <#C8CCD4>" + users + "/" + Math.round(bb().capacity(n)) + " customers"
                : "<#9AA0AA>Network down · upkeep unpaid";
        setText(k, "footl", Msg.mm(left), 0.14, W * 0.5, Align.LEFT, null);
        setText(k, "footr", Msg.mm("<#9AA0AA>Upkeep " + Msg.escape(s().money(bb().nodeUpkeep(n)))), 0.13, W * 0.46, Align.RIGHT, null);
    }

    private void updateRouter(BlockKey k, Router r) {
        Isp isp = r.ispId() == null ? null : bb().store().isp(r.ispId());
        double W = geo(k).W();
        Link l = bb().link(r);
        accent(k, isp == null ? 0x55FFFF : isp.colors().getFirst().value());
        State st;
        String label;
        switch (r.state()) {
            case NONE -> {
                st = State.OFF;
                label = "NO PLAN";
            }
            case INSTALLING -> {
                st = State.WARN;
                label = "INSTALLING";
            }
            case SUSPENDED -> {
                st = State.BAD;
                label = "SUSPENDED";
            }
            default -> {
                st = l == null ? State.BAD : State.OK;
                label = l == null ? "NO SIGNAL" : "ONLINE";
            }
        }
        setText(k, "head", isp == null ? Msg.mm("<white><b>Router") : Msg.mm("<b>" + isp.styled()), 0.19, W * 0.62, Align.LEFT, null);
        setText(k, "badge", Msg.mm(badgeText(st, label)), 0.14, W * 0.32, Align.RIGHT, badgeBg(st));
        setText(k, "name", Msg.mm((st == State.OK ? "<white>" : "<#7A7F8A>") + "<b>" + Msg.escape(r.name())), 0.6, W * 0.44, Align.LEFT, null);
        InternetPlan plan = isp == null ? null : isp.plans().get(r.planId());
        String sub = r.state() == Router.State.NONE || plan == null ? "Right-click to pick a provider"
                : (r.terms() != null ? r.terms().name() : plan.name()) + " · " + bb().cfg().spec(r.tech() == null ? plan.tech() : r.tech()).name();
        if (r.business()) sub = "Business · " + sub;
        setText(k, "sub", Msg.mm("<#9AA0AA>" + Msg.escape(sub)), 0.15, W * 0.46, Align.LEFT, null);
        int max = bb().cfg().maxWifiTier();
        for (int i = 0; i < 4; i++) chip(k, i, "WF" + (i + 4), i + 1 <= r.tier() && st == State.OK, i < max);
        var tier = bb().cfg().tier(r.tier());
        setText(k, "mid", Msg.mm("<#9AA0AA>" + Msg.escape(tier.name()) + " · " + Math.round(bb().reach(r)) + " blocks"), 0.13, W * 0.46, Align.RIGHT, null);
        String left;
        if (l != null) {
            left = "<#5EE38A>↓ <#E6FFEE>" + Msg.escape(Msg.speed(l.downMbps())) + " <#C58AFF>↑ <#E6FFEE>" + Msg.escape(Msg.speed(l.upMbps())) + " <#7A7F8A>· " + Math.round(l.pingMs()) + " ms";
        } else {
            String why = bb().problem(r);
            left = "<#9AA0AA>" + Msg.escape(why == null ? "" : why);
        }
        setText(k, "footl", Msg.mm(left), 0.14, W * 0.62, Align.LEFT, null);
        String right = bb().throttled(r) ? "<#FFD86B>Data used up" : r.open() ? "<#7FDBFF>Wi-Fi shared" : "<#9AA0AA>Wi-Fi private";
        setText(k, "footr", Msg.mm(right), 0.13, W * 0.34, Align.RIGHT, null);
    }

    private void updateComputer(BlockKey k, Computer c) {
        double W = geo(k).W();
        Link l = bb().computerLink(c, c.owner());
        accent(k, 0x55FFFF);
        State st = l == null ? State.OFF : State.OK;
        setText(k, "head", Msg.mm("<white><b>Computer"), 0.19, W * 0.62, Align.LEFT, null);
        setText(k, "badge", Msg.mm(badgeText(st, l == null ? "OFFLINE" : "ONLINE")), 0.14, W * 0.32, Align.RIGHT, badgeBg(st));
        setText(k, "name", Msg.mm((st == State.OK ? "<white>" : "<#7A7F8A>") + "<b>" + Msg.escape(c.name())), 0.6, W * 0.44, Align.LEFT, null);
        String owner = Bukkit.getOfflinePlayer(c.owner()).getName();
        setText(k, "sub", Msg.mm("<#9AA0AA>" + Msg.escape(owner == null ? "?" : owner)), 0.15, W * 0.46, Align.LEFT, null);
        String[] labels = {" WWW", "NEWS", "SHOP", "MAIL"};
        for (int i = 0; i < 4; i++) chip(k, i, labels[i], l != null, true);
        setText(k, "mid", Msg.mm("<#9AA0AA>" + (l == null ? "No Wi-Fi in reach" : "Wi-Fi · " + Msg.escape(l.router().name()))), 0.13, W * 0.46, Align.RIGHT, null);
        String left = l == null ? "<#9AA0AA>Not connected"
                : "<#5EE38A>↓ <#E6FFEE>" + Msg.escape(Msg.speed(l.downMbps())) + " <#7A7F8A>· " + Math.round(l.pingMs()) + " ms";
        setText(k, "footl", Msg.mm(left), 0.14, W * 0.62, Align.LEFT, null);
        setText(k, "footr", Msg.mm("<#9AA0AA>Right-click"), 0.13, W * 0.34, Align.RIGHT, null);
    }

    private void updateExtender(BlockKey k, Extender x) {
        double W = geo(k).W();
        Router src = bb().sourceOf(x);
        Link l = bb().extenderLink(x);
        accent(k, l == null ? 0x55FFFF : l.isp().colors().getFirst().value());
        State st = l == null ? State.OFF : State.OK;
        setText(k, "head", Msg.mm("<white><b>Extender"), 0.19, W * 0.62, Align.LEFT, null);
        setText(k, "badge", Msg.mm(badgeText(st, l == null ? "NO ROUTER" : "ONLINE")), 0.14, W * 0.32, Align.RIGHT, badgeBg(st));
        setText(k, "name", Msg.mm((st == State.OK ? "<white>" : "<#7A7F8A>") + "<b>" + Msg.escape(x.name())), 0.6, W * 0.44, Align.LEFT, null);
        setText(k, "sub", Msg.mm("<#9AA0AA>" + (src == null ? "Put it within reach of your router" : "Repeats " + Msg.escape(src.name()))), 0.15, W * 0.46, Align.LEFT, null);
        int max = bb().cfg().maxWifiTier();
        for (int i = 0; i < 4; i++) chip(k, i, "WF" + (i + 4), src != null && i + 1 <= src.tier() && st == State.OK, i < max);
        setText(k, "mid", Msg.mm("<#9AA0AA>" + (src == null ? "No signal to repeat" : "Reach " + Math.round(bb().extenderReach(src)) + " blocks")), 0.13, W * 0.46, Align.RIGHT, null);
        String left = l == null ? "<#9AA0AA>Not repeating anything"
                : "<#5EE38A>↓ <#E6FFEE>" + Msg.escape(Msg.speed(l.downMbps())) + " <#7A7F8A>· " + Math.round(l.pingMs()) + " ms";
        setText(k, "footl", Msg.mm(left), 0.14, W * 0.62, Align.LEFT, null);
        long keep = Math.round(bb().cfg().wifi().extender().speedFactor() * 100);
        setText(k, "footr", Msg.mm("<#9AA0AA>Keeps " + keep + "% speed"), 0.13, W * 0.34, Align.RIGHT, null);
    }

    private Entity part(BlockKey k, String role) {
        Map<String, UUID> m = parts.get(k);
        UUID id = m == null ? null : m.get(role);
        return id == null ? null : Bukkit.getEntity(id);
    }

    /** Sets a text part: scale (shrunk if wider than maxWidth blocks), anchored left, centre or right, vertically centred. */
    private void setText(BlockKey k, String role, Component text, double scale, double maxWidth, Align align, Color bg) {
        if (!(part(k, role) instanceof TextDisplay td)) return;
        String plain = PlainTextComponentSerializer.plainText().serialize(text);
        double px = TowerScreen.widthPx(plain, true);
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
}
