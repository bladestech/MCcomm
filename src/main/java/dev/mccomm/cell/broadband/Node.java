package dev.mccomm.cell.broadband;

import dev.mccomm.cell.model.BlockKey;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.UUID;

/**
 * An ISP's point of presence on a wall sign: a DSL exchange, a cable headend, a fiber cabinet or a
 * satellite ground station. Customers connect to the nearest working node of their plan's technology.
 */
public final class Node implements Device {
    private final int id;
    private final String ispId;
    private final Technology tech;
    private String name;
    private final BlockKey key;
    private int tier = 1;
    private long faultAt; // when the node developed a fault (epoch ms), 0 = healthy; a faulted node serves nobody
    private final UUID createdBy;
    private final long createdAt;

    public Node(int id, String ispId, Technology tech, String name, BlockKey key, UUID createdBy, long createdAt) {
        this.id = id;
        this.ispId = ispId;
        this.tech = tech;
        this.name = name;
        this.key = key;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public int id() { return id; }
    public String ispId() { return ispId; }
    public Technology tech() { return tech; }
    public String name() { return name; }
    public void name(String n) { this.name = n; }
    public int tier() { return tier; }
    public void tier(int t) { this.tier = Math.max(1, t); }
    public long faultAt() { return faultAt; }
    public void faultAt(long t) { this.faultAt = t; }
    public boolean faulted() { return faultAt > 0; }
    public UUID createdBy() { return createdBy; }
    public long createdAt() { return createdAt; }

    @Override
    public BlockKey key() { return key; }

    @Override
    public String kind() { return "node"; }

    public World world() {
        return Bukkit.getWorld(key.world());
    }

    public Block block() {
        World w = world();
        return w == null ? null : w.getBlockAt(key.x(), key.y(), key.z());
    }

    public Location location() {
        World w = world();
        return w == null ? null : new Location(w, key.x() + 0.5, key.y() + 0.5, key.z() + 0.5);
    }
}
