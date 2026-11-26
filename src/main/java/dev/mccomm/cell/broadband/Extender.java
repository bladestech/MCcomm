package dev.mccomm.cell.broadband;

import dev.mccomm.cell.model.BlockKey;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.UUID;

/**
 * A Wi-Fi extender on a wall sign. It picks up one of its owner's routers (one whose Wi-Fi reaches the sign)
 * and repeats it, so the Wi-Fi reaches further, at a cost in speed.
 */
public final class Extender implements Device {
    private final int id;
    private final UUID owner;
    private final BlockKey key;
    private String name;
    private int routerId; // the router to repeat, 0 = whichever of the owner's routers works best
    private final long createdAt;

    public Extender(int id, UUID owner, String name, BlockKey key, long createdAt) {
        this.id = id;
        this.owner = owner;
        this.name = name;
        this.key = key;
        this.createdAt = createdAt;
    }

    public int id() { return id; }
    public UUID owner() { return owner; }
    public String name() { return name; }
    public void name(String n) { this.name = n; }
    public int routerId() { return routerId; }
    public void routerId(int r) { this.routerId = r; }
    public long createdAt() { return createdAt; }

    @Override
    public BlockKey key() { return key; }

    @Override
    public String kind() { return "extender"; }

    public World world() {
        return Bukkit.getWorld(key.world());
    }

    public Block block() {
        World w = world();
        return w == null ? null : w.getBlockAt(key.x(), key.y(), key.z());
    }
}
