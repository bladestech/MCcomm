package dev.mccomm.cell.workforce;

import dev.mccomm.cell.model.BlockKey;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.UUID;

/**
 * A company building registered on a wall sign. Phase 1 has one type, the maintenance station:
 * it houses technicians (one per bed) and is where repair trips start.
 */
public final class Facility {
    public enum Type {
        STATION("Maintenance station", "station", "[Station]", "bed per technician"),
        CALL_CENTRE("Call centre", "callcentre", "[Call Centre]", "seat (stairs) and lectern per agent"),
        NOC("Network operations centre", "noc", "[NOC]", "seat (stairs) and observer per engineer");

        public final String display, key, sign, needs;

        Type(String display, String key, String sign, String needs) {
            this.display = display;
            this.key = key;
            this.sign = sign;
            this.needs = needs;
        }

        public static Type of(String key) {
            for (Type t : values()) if (t.key.equalsIgnoreCase(key) || t.name().equalsIgnoreCase(key)) return t;
            return null;
        }
    }

    private final int id;
    private final String companyId;
    private final Type type;
    private String name;
    private final UUID worldId;
    private final int x, y, z;
    private int capacity;   // beds
    private int barrels;    // spare-parts storage
    private long checkedAt; // last successful room scan (epoch ms)
    private final long createdAt;

    public Facility(int id, String companyId, Type type, String name, UUID worldId, int x, int y, int z, long createdAt) {
        this.id = id;
        this.companyId = companyId;
        this.type = type;
        this.name = name;
        this.worldId = worldId;
        this.x = x;
        this.y = y;
        this.z = z;
        this.createdAt = createdAt;
    }

    public int id() { return id; }
    public String companyId() { return companyId; }
    public Type type() { return type; }
    public String name() { return name; }
    public void name(String n) { this.name = n; }
    public UUID worldId() { return worldId; }
    public int x() { return x; }
    public int y() { return y; }
    public int z() { return z; }
    public int capacity() { return capacity; }
    public void capacity(int c) { this.capacity = Math.max(0, c); }
    public int barrels() { return barrels; }
    public void barrels(int b) { this.barrels = Math.max(0, b); }
    public long checkedAt() { return checkedAt; }
    public void checkedAt(long t) { this.checkedAt = t; }
    public long createdAt() { return createdAt; }

    public World world() { return Bukkit.getWorld(worldId); }

    public Block block() {
        World w = world();
        return w == null ? null : w.getBlockAt(x, y, z);
    }

    public boolean isChunkLoaded() {
        World w = world();
        return w != null && w.isChunkLoaded(x >> 4, z >> 4);
    }

    public BlockKey key() { return new BlockKey(worldId, x, y, z); }

    /** Horizontal distance to a point in the same world, or -1 for another world. */
    public double distanceTo(UUID world, int bx, int bz) {
        if (!worldId.equals(world)) return -1;
        double dx = bx - x, dz = bz - z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
