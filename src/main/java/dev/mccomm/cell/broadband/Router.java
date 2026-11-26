package dev.mccomm.cell.broadband;

import dev.mccomm.cell.model.BlockKey;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.UUID;

/**
 * A home's router/modem on a wall sign. It holds the household's internet subscription: which ISP,
 * which plan, and the billing state. Its Wi-Fi reaches {@code broadband.wifi-range} blocks.
 */
public final class Router implements Device {
    public enum State {
        /** Installed, but no internet plan yet. */
        NONE,
        /** Ordered; the technician is on the way. */
        INSTALLING,
        /** Live. */
        ACTIVE,
        /** A payment was missed. No service until it is paid, and the line is cut if it stays unpaid. */
        SUSPENDED
    }

    private final int id;
    private final UUID owner;
    private final BlockKey key;
    private String name;
    private State state = State.NONE;
    private String ispId;
    private String planId;
    private Technology tech;      // technology of the current plan, kept in case the plan is deleted
    private int nodeId;           // the node serving this home (0 = none found yet)
    private long installAt;       // when the technician finishes (epoch ms)
    private long nextBill;        // next renewal (epoch ms)
    private long suspendedSince;
    private boolean warned;
    private boolean open;         // share the Wi-Fi with everyone
    private boolean business;     // the site is a business: it sees business plans instead of home ones
    private int tier = 1;         // Wi-Fi generation: range and how many guests it takes
    private InternetPlan terms;   // the plan as it was at the start of this period (changes apply at renewal)
    private double dataLeftMB;    // data allowance left this period (ignored on unlimited plans)
    private int towerId;          // fixed wireless: the carrier tower serving this home
    private final long createdAt;

    public Router(int id, UUID owner, String name, BlockKey key, long createdAt) {
        this.id = id;
        this.owner = owner;
        this.name = name;
        this.key = key;
        this.createdAt = createdAt;
    }

    public int id() { return id; }
    public boolean business() { return business; }
    public void business(boolean b) { this.business = b; }
    public UUID owner() { return owner; }
    public String name() { return name; }
    public void name(String n) { this.name = n; }
    public State state() { return state; }
    public void state(State s) { this.state = s; }
    public String ispId() { return ispId; }
    public void ispId(String i) { this.ispId = i; }
    public String planId() { return planId; }
    public void planId(String p) { this.planId = p; }
    public Technology tech() { return tech; }
    public void tech(Technology t) { this.tech = t; }
    public int nodeId() { return nodeId; }
    public void nodeId(int n) { this.nodeId = n; }
    public long installAt() { return installAt; }
    public void installAt(long t) { this.installAt = t; }
    public long nextBill() { return nextBill; }
    public void nextBill(long t) { this.nextBill = t; }
    public long suspendedSince() { return suspendedSince; }
    public void suspendedSince(long t) { this.suspendedSince = t; }
    public boolean warned() { return warned; }
    public void warned(boolean w) { this.warned = w; }
    public boolean open() { return open; }
    public void open(boolean o) { this.open = o; }
    public long createdAt() { return createdAt; }
    public int tier() { return tier; }
    public void tier(int t) { this.tier = Math.max(1, t); }
    public InternetPlan terms() { return terms; }
    public void terms(InternetPlan t) { this.terms = t; }
    public double dataLeftMB() { return dataLeftMB; }
    public void dataLeftMB(double mb) { this.dataLeftMB = Math.max(0, mb); }
    public int towerId() { return towerId; }
    public void towerId(int t) { this.towerId = t; }

    /** True when the router has (or is getting) an ISP plan. */
    public boolean subscribed() {
        return state != State.NONE && ispId != null;
    }

    /** Clears the subscription, keeping the router. */
    public void clearService() {
        state = State.NONE;
        ispId = null;
        planId = null;
        tech = null;
        nodeId = 0;
        towerId = 0;
        terms = null;
        dataLeftMB = 0;
        installAt = 0;
        nextBill = 0;
        suspendedSince = 0;
        warned = false;
    }

    @Override
    public BlockKey key() { return key; }

    @Override
    public String kind() { return "router"; }

    public World world() {
        return Bukkit.getWorld(key.world());
    }

    public Block block() {
        World w = world();
        return w == null ? null : w.getBlockAt(key.x(), key.y(), key.z());
    }
}
