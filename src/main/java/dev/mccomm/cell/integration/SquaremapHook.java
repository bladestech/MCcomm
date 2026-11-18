package dev.mccomm.cell.integration;

import dev.mccomm.cell.CellPlugin;
import dev.mccomm.cell.Settings;
import dev.mccomm.cell.band.BackhaulSpec;
import dev.mccomm.cell.band.Band;
import dev.mccomm.cell.model.Carrier;
import dev.mccomm.cell.model.Manufacturer;
import dev.mccomm.cell.model.Tower;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;
import xyz.jpenilla.squaremap.api.BukkitAdapter;
import xyz.jpenilla.squaremap.api.Key;
import xyz.jpenilla.squaremap.api.MapWorld;
import xyz.jpenilla.squaremap.api.Point;
import xyz.jpenilla.squaremap.api.SimpleLayerProvider;
import xyz.jpenilla.squaremap.api.Squaremap;
import xyz.jpenilla.squaremap.api.SquaremapProvider;
import xyz.jpenilla.squaremap.api.marker.Marker;
import xyz.jpenilla.squaremap.api.marker.MarkerOptions;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Shows MCcomm on squaremap: a "Cell towers" layer with an icon per tower (carrier colour, grey when
 * down) and a coverage layer per carrier with each tower's reach. Only loaded when squaremap is installed.
 */
public final class SquaremapHook {
    private static final Key TOWERS = Key.of("mccomm_towers");
    private static final Key STATIONS = Key.of("mccomm_stations");

    private final CellPlugin plugin;
    private final Squaremap api;
    /** world → (layer key → provider) that we registered. */
    private final Map<UUID, Map<Key, SimpleLayerProvider>> layers = new HashMap<>();
    private final Set<Key> icons = new HashSet<>();
    private BukkitTask task;

    public SquaremapHook(CellPlugin plugin) {
        this.plugin = plugin;
        this.api = SquaremapProvider.get();
    }

    private Settings s() {
        return plugin.settings();
    }

    public void start() {
        stop();
        long ticks = Math.max(5, s().squaremapIntervalSeconds) * 20L;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::update, 40L, ticks);
        plugin.getLogger().info("squaremap found: showing cell towers and coverage on the map.");
    }

    public void stop() {
        if (task != null) task.cancel();
        task = null;
        for (var e : layers.entrySet()) {
            World w = Bukkit.getWorld(e.getKey());
            if (w == null) continue;
            api.getWorldIfEnabled(BukkitAdapter.worldIdentifier(w)).ifPresent(mw -> {
                for (Key k : e.getValue().keySet()) {
                    if (mw.layerRegistry().hasEntry(k)) mw.layerRegistry().unregister(k);
                }
            });
        }
        layers.clear();
        for (Key k : icons) if (api.iconRegistry().hasEntry(k)) api.iconRegistry().unregister(k);
        icons.clear();
    }

    // ---------------------------------------------------------------- update

    private void update() {
        // Group towers by world.
        Map<UUID, List<Tower>> byWorld = new HashMap<>();
        for (Tower t : plugin.store().towers()) byWorld.computeIfAbsent(t.worldId(), k -> new ArrayList<>()).add(t);

        for (World w : Bukkit.getWorlds()) {
            MapWorld mw = api.getWorldIfEnabled(BukkitAdapter.worldIdentifier(w)).orElse(null);
            if (mw == null) continue;
            Map<Key, SimpleLayerProvider> mine = layers.computeIfAbsent(w.getUID(), k -> new HashMap<>());
            List<Tower> towers = byWorld.getOrDefault(w.getUID(), List.of());

            SimpleLayerProvider towerLayer = layer(mw, mine, TOWERS, "Cell towers", 20, 300, false);
            towerLayer.clearMarkers();
            Map<String, List<Tower>> perCarrier = new HashMap<>();
            for (Tower t : towers) {
                perCarrier.computeIfAbsent(t.carrierId(), k -> new ArrayList<>()).add(t);
                Carrier c = plugin.store().carrier(t.carrierId());
                boolean up = working(t);
                Key icon = icon(up && c != null ? c.color().value() : 0x8A8F9A);
                String html = popup(t, c, up);
                towerLayer.addMarker(Key.of("t" + t.id()), Marker.icon(Point.of(t.x() + 0.5, t.z() + 0.5), icon, s().squaremapIconSize)
                        .markerOptions(MarkerOptions.builder().hoverTooltip(html).clickTooltip(html)));
            }

            // Maintenance stations and how far their technicians go.
            boolean anyStation = false;
            for (var f : plugin.workforce().facilities()) if (f.worldId().equals(w.getUID())) anyStation = true;
            if (anyStation && plugin.workforce().enabled()) {
                SimpleLayerProvider st = layer(mw, mine, STATIONS, "Maintenance stations", 22, 290, false);
                st.clearMarkers();
                for (var f : plugin.workforce().facilities()) {
                    if (!f.worldId().equals(w.getUID())) continue;
                    Carrier c = plugin.store().carrier(f.companyId());
                    int rgb = c == null ? 0xFFFFFF : c.color().value();
                    int staff = plugin.workforce().staffAt(f).size();
                    String html = "<div style='font-family:sans-serif'><b style='color:" + (c == null ? "#fff" : c.color().asHexString()) + "'>"
                            + esc(c == null ? "?" : c.name()) + "</b> &middot; <b>" + esc(f.name()) + "</b><br>Maintenance station<br>Technicians: "
                            + staff + "/" + f.capacity() + "<br>Reach: " + plugin.workforce().w().serviceRadius() + " blocks</div>";
                    st.addMarker(Key.of("s" + f.id()), Marker.icon(Point.of(f.x() + 0.5, f.z() + 0.5), stationIcon(rgb), s().squaremapIconSize)
                            .markerOptions(MarkerOptions.builder().hoverTooltip(html).clickTooltip(html)));
                    Color col = new Color(rgb);
                    st.addMarker(Key.of("r" + f.id()), Marker.circle(Point.of(f.x() + 0.5, f.z() + 0.5), plugin.workforce().w().serviceRadius())
                            .markerOptions(MarkerOptions.builder().fill(false).strokeColor(col).strokeOpacity(0.6).strokeWeight(2)
                                    .hoverTooltip(esc(f.name() + " · service area"))));
                }
            } else if (mine.containsKey(STATIONS)) {
                if (mw.layerRegistry().hasEntry(STATIONS)) mw.layerRegistry().unregister(STATIONS);
                mine.remove(STATIONS);
            }

            if (s().squaremapCoverage) {
                Set<Key> keep = new HashSet<>();
                keep.add(TOWERS);
                keep.add(STATIONS);
                for (Carrier c : plugin.store().carriers()) {
                    List<Tower> list = perCarrier.getOrDefault(c.id(), List.of());
                    if (list.isEmpty()) continue;
                    Key key = Key.of("mccomm_cov_" + safe(c.id()));
                    keep.add(key);
                    SimpleLayerProvider cov = layer(mw, mine, key, "Coverage · " + c.name(), 21, 250, s().squaremapCoverageHidden);
                    cov.clearMarkers();
                    Color col = new Color(c.color().value());
                    for (Tower t : list) {
                        if (!working(t)) continue;
                        double mult = s().rangeMultiplier(t.heightAboveGround());
                        List<Band> bands = new ArrayList<>();
                        for (Band b : t.enabledBands()) if (plugin.towers().available(t, b)) bands.add(b);
                        if (bands.isEmpty()) continue;
                        if (s().squaremapPerBand) {
                            for (Band b : bands) {
                                double r = s().spec(b).range() * mult;
                                cov.addMarker(Key.of("c" + t.id() + "_" + b.ordinal()), circle(t, r, col, 0.05, s().spec(b).name() + " · " + t.name()));
                            }
                        } else {
                            double r = 0;
                            for (Band b : bands) r = Math.max(r, s().spec(b).range() * mult);
                            cov.addMarker(Key.of("c" + t.id()), circle(t, r, col, 0.12, t.name() + " · " + Math.round(r) + " blocks"));
                        }
                    }
                }
                // One layer per band (toggle bands individually), each in its own colour.
                if (s().squaremapBandLayers) {
                    for (Band b : Band.values()) {
                        Key key = Key.of("mccomm_band_" + safe(b.key().replace("+", "plus")));
                        keep.add(key);
                        SimpleLayerProvider bl = layer(mw, mine, key, "Band · " + s().spec(b).name(), 30 + b.ordinal(), 200, s().squaremapBandLayersHidden);
                        bl.clearMarkers();
                        Color col = new Color(BAND_COLORS[b.ordinal()]);
                        for (Tower t : towers) {
                            if (!working(t) || !t.enabledBands().contains(b) || !plugin.towers().available(t, b)) continue;
                            Carrier c = plugin.store().carrier(t.carrierId());
                            double r = s().spec(b).range() * s().rangeMultiplier(t.heightAboveGround());
                            bl.addMarker(Key.of("b" + t.id()), Marker.circle(Point.of(t.x() + 0.5, t.z() + 0.5), r).markerOptions(MarkerOptions.builder()
                                    .fillColor(col).fillOpacity(0.07).strokeColor(col).strokeOpacity(0.9).strokeWeight(2)
                                    .hoverTooltip(esc(s().spec(b).name() + " · " + t.name() + " (" + (c == null ? "?" : c.name()) + ") · " + Math.round(r) + " blocks"))));
                        }
                    }
                }
                // Remove coverage layers of carriers that no longer have towers here.
                for (Key k : new ArrayList<>(mine.keySet())) {
                    if (!keep.contains(k)) {
                        if (mw.layerRegistry().hasEntry(k)) mw.layerRegistry().unregister(k);
                        mine.remove(k);
                    }
                }
            }
        }
    }

    /** 2G grey, 3G orange, 4G yellow, 4G+ lime, 5G Low cyan, 5G Mid blue, 5G High purple, mmWave magenta. */
    private static final int[] BAND_COLORS = {0x9E9E9E, 0xFF9800, 0xFFEB3B, 0x8BC34A, 0x00BCD4, 0x2196F3, 0x9C27B0, 0xE91E63};

    private Marker circle(Tower t, double radius, Color col, double opacity, String tip) {
        return Marker.circle(Point.of(t.x() + 0.5, t.z() + 0.5), radius).markerOptions(MarkerOptions.builder()
                .fillColor(col).fillOpacity(opacity).strokeColor(col).strokeOpacity(0.55).strokeWeight(1)
                .hoverTooltip(esc(tip)));
    }

    private SimpleLayerProvider layer(MapWorld mw, Map<Key, SimpleLayerProvider> mine, Key key, String label,
                                      int priority, int z, boolean hidden) {
        SimpleLayerProvider p = mine.get(key);
        if (p != null && mw.layerRegistry().hasEntry(key)) return p;
        p = SimpleLayerProvider.builder(label).showControls(true).defaultHidden(hidden).layerPriority(priority).zIndex(z).build();
        if (mw.layerRegistry().hasEntry(key)) mw.layerRegistry().unregister(key);
        mw.layerRegistry().register(key, p);
        mine.put(key, p);
        return p;
    }

    private boolean working(Tower t) {
        return t.online() && plugin.billing().decayStage(t) < s().silentStage() && !plugin.workforce().silent(t);
    }

    // ---------------------------------------------------------------- popup

    private String popup(Tower t, Carrier c, boolean up) {
        StringBuilder sb = new StringBuilder();
        String hex = c == null ? "#ffffff" : c.color().asHexString();
        sb.append("<div style='font-family:sans-serif;min-width:180px'>");
        sb.append("<b style='color:").append(hex).append("'>").append(esc(c == null ? "?" : c.name())).append("</b> &middot; <b>")
                .append(esc(t.name())).append("</b><br>");
        if (c != null) sb.append("<span style='color:#f5c542'>").append(esc(plugin.market().ratingPlain(c))).append("</span><br>");
        Manufacturer m = plugin.store().manufacturer(t.manufacturerId());
        sb.append(esc(plugin.towers().modelName(t))).append(" (Gen ").append(t.generation()).append(")");
        if (m != null) sb.append(" by ").append(esc(m.name()));
        sb.append("<br>");
        List<String> bands = new ArrayList<>();
        for (Band b : t.enabledBands()) bands.add(s().spec(b).name());
        sb.append("Bands: ").append(esc(bands.isEmpty() ? "none" : String.join(", ", bands))).append("<br>");
        int stage = plugin.billing().decayStage(t);
        String status = !t.online() ? "<span style='color:#9aa0aa'>Offline</span>"
                : stage >= s().silentStage() ? "<span style='color:#e05555'>Silent (upkeep unpaid)</span>"
                : stage > 0 ? "<span style='color:#f6c255'>Degraded (upkeep unpaid)</span>"
                : plugin.workforce().silent(t) ? "<span style='color:#e05555'>Silent (worn out)</span>"
                : plugin.workforce().wearStage(t) > 0 ? "<span style='color:#f6c255'>Worn</span>"
                : "<span style='color:#5ee38a'>Online</span>";
        sb.append("Status: ").append(status).append("<br>");
        if (plugin.workforce().enabled()) {
            sb.append("Wear: ").append(Math.round(t.wear())).append("%");
            var job = plugin.workforce().job(t);
            if (job != null) sb.append(job.started() ? " &middot; repair in progress" : " &middot; repair queued");
            sb.append("<br>");
        }
        if (up) {
            int users = plugin.signal().usersOn(t), sims = plugin.signal().simOn(t);
            sb.append("Users: ").append(users);
            if (sims > 0) sb.append(" + ").append(sims).append(" simulated");
            sb.append("<br>");
            BackhaulSpec bh = s().backhaul.get(t.backhaul());
            if (bh != null) sb.append("Backhaul: ").append(esc(bh.name())).append(" ").append(Math.round(bh.multiplier() * 100)).append("%<br>");
        }
        int bonus = (int) Math.round((s().rangeMultiplier(t.heightAboveGround()) - 1) * 100);
        sb.append("Height: ").append(Math.round(t.heightAboveGround())).append(" blocks");
        if (bonus > 0) sb.append(" (+").append(bonus).append("% range)");
        sb.append("</div>");
        return sb.toString();
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;").replace("\"", "&quot;");
    }

    private static String safe(String id) {
        return id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
    }

    // ----------------------------------------------------------------- icons

    /** A small antenna-mast icon in the given colour, registered once per colour. */
    private Key icon(int rgb) {
        Key key = Key.of("mccomm_tower_" + String.format("%06x", rgb & 0xFFFFFF));
        if (icons.contains(key) && api.iconRegistry().hasEntry(key)) return key;
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Color c = new Color(rgb);
        Color dark = new Color(20, 22, 28);
        // Outline for contrast on any map colour.
        g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(dark);
        drawMast(g);
        g.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(c);
        drawMast(g);
        g.fillOval(13, 4, 6, 6);
        g.dispose();
        if (api.iconRegistry().hasEntry(key)) api.iconRegistry().unregister(key);
        api.iconRegistry().register(key, img);
        icons.add(key);
        return key;
    }

    /** A small rounded square with a wrench, for maintenance stations. */
    private Key stationIcon(int rgb) {
        Key key = Key.of("mccomm_station_" + String.format("%06x", rgb & 0xFFFFFF));
        if (icons.contains(key) && api.iconRegistry().hasEntry(key)) return key;
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(20, 22, 28));
        g.fillRoundRect(3, 3, 26, 26, 9, 9);
        g.setColor(new Color(rgb));
        g.setStroke(new BasicStroke(2f));
        g.drawRoundRect(4, 4, 24, 24, 8, 8);
        // wrench: handle and open jaw
        g.setStroke(new BasicStroke(3.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(10, 22, 18, 14);
        g.fillOval(16, 8, 9, 9);
        g.setColor(new Color(20, 22, 28));
        g.fillRect(19, 7, 4, 6);
        g.dispose();
        if (api.iconRegistry().hasEntry(key)) api.iconRegistry().unregister(key);
        api.iconRegistry().register(key, img);
        icons.add(key);
        return key;
    }

    private static void drawMast(Graphics2D g) {
        g.drawLine(16, 8, 9, 29);   // left leg
        g.drawLine(16, 8, 23, 29);  // right leg
        g.drawLine(11, 22, 21, 22); // cross brace
        g.drawLine(13, 15, 19, 15);
        g.drawArc(6, 1, 20, 16, 20, 140);  // signal arcs
        g.drawArc(1, -3, 30, 24, 25, 130);
    }
}
