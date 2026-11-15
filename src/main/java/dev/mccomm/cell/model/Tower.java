package dev.mccomm.cell.model;

import dev.mccomm.cell.band.Band;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.EnumSet;
import java.util.UUID;

public final class Tower {
    private final int id;
    private String carrierId;
    private String name;
    private final UUID worldId;
    private final int x, y, z;
    private int generation;
    private final EnumSet<Band> enabledBands = EnumSet.noneOf(Band.class);
    private Material backhaul; // null = offline
    private double heightAboveGround; // antenna height over the surrounding terrain, measured when loaded
    private UUID displayId, panelId; // old single-text screen, removed on next load
    private final java.util.Map<String, UUID> screen = new java.util.LinkedHashMap<>(); // role -> entity
    private final UUID createdBy;
    private final long createdAt;

    public Tower(int id, String carrierId, UUID worldId, int x, int y, int z, int generation,
                 UUID createdBy, long createdAt) {
        this.id = id;
        this.carrierId = carrierId;
        this.worldId = worldId;
        this.x = x;
        this.y = y;
        this.z = z;
        this.generation = generation;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public int id() { return id; }
    public String carrierId() { return carrierId; }
    public String name() { return name == null ? "T" + id : name; }
    public void name(String name) { this.name = name; }
    public UUID worldId() { return worldId; }
    public int x() { return x; }
    public int y() { return y; }
    public int z() { return z; }
    public int generation() { return generation; }
    public void generation(int g) { this.generation = g; }
    public EnumSet<Band> enabledBands() { return enabledBands; }
    public double heightAboveGround() { return heightAboveGround; }
    public void heightAboveGround(double h) { this.heightAboveGround = h; }
    public Material backhaul() { return backhaul; }
    public void backhaul(Material m) { this.backhaul = m; }
    public java.util.Map<String, UUID> screen() { return screen; }
    public UUID displayId() { return displayId; }
    public void displayId(UUID id) { this.displayId = id; }
    public UUID panelId() { return panelId; }
    public void panelId(UUID id) { this.panelId = id; }
    public UUID createdBy() { return createdBy; }
    public long createdAt() { return createdAt; }

    public World world() {
        return Bukkit.getWorld(worldId);
    }

    public Block block() {
        World w = world();
        return w == null ? null : w.getBlockAt(x, y, z);
    }

    public boolean isChunkLoaded() {
        World w = world();
        return w != null && w.isChunkLoaded(x >> 4, z >> 4);
    }

    /** Antenna point used for distance and line-of-sight. */
    public Location antenna(double height) {
        World w = world();
        return w == null ? null : new Location(w, x + 0.5, y + 0.5 + height, z + 0.5);
    }

    public boolean online() {
        return backhaul != null && !enabledBands.isEmpty();
    }

    public BlockKey key() {
        return new BlockKey(worldId, x, y, z);
    }
}
