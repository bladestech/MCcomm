package dev.mccomm.cell.workforce;

import org.bukkit.Material;

/** The three parts of a company's operations budget. */
public enum OpsPart {
    FIELD("field-crews", "Field crews", 1.5, Material.IRON_PICKAXE),
    CARE("customer-care", "Customer care", 1.0, Material.BELL),
    NETWORK("network-ops", "Network ops", 1.0, Material.OBSERVER);

    public final String key, display;
    /** The highest funding level the owner can choose (1.0 = 100%). */
    public final double max;
    public final Material icon;

    OpsPart(String key, String display, double max, Material icon) {
        this.key = key;
        this.display = display;
        this.max = max;
        this.icon = icon;
    }
}
