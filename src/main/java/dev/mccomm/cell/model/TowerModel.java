package dev.mccomm.cell.model;

import dev.mccomm.cell.band.Band;

import java.util.EnumSet;

/**
 * A tower model a manufacturer sells: its own name, a generation and the bands it includes
 * (any subset of what that generation allows). Specs per band are the same for every manufacturer.
 */
public final class TowerModel {
    private final String id;
    private String name;
    private int generation = 1;
    private final EnumSet<Band> bands = EnumSet.noneOf(Band.class);
    private double marginPercent = 20;
    private boolean published;
    private int sold;
    private boolean small; // small cell form factor

    public TowerModel(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public String id() { return id; }
    public String name() { return name; }
    public void name(String n) { this.name = n; }
    public int generation() { return generation; }
    public void generation(int g) { this.generation = g; }
    public EnumSet<Band> bands() { return bands; }
    public double marginPercent() { return marginPercent; }
    public void marginPercent(double m) { this.marginPercent = m; }
    public boolean published() { return published; }
    public void published(boolean p) { this.published = p; }
    public int sold() { return sold; }
    public void sold(int n) { this.sold = n; }
    public boolean small() { return small; }
    public void small(boolean s) { this.small = s; }
}
