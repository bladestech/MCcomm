package dev.mccomm.cell.broadband;

import dev.mccomm.cell.model.BlockKey;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.UUID;

/** A player's computer on a wall sign. It joins the Wi-Fi of a router in range and gives a full internet screen. */
public final class Computer implements Device {
    private final int id;
    private final UUID owner;
    private final BlockKey key;
    private String name;
    private int routerId; // the router the owner picked, 0 = whichever works best
    private final long createdAt;

    public Computer(int id, UUID owner, String name, BlockKey key, long createdAt) {
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
    public String kind() { return "computer"; }

    public World world() {
        return Bukkit.getWorld(key.world());
    }

    public Block block() {
        World w = world();
        return w == null ? null : w.getBlockAt(key.x(), key.y(), key.z());
    }
}
