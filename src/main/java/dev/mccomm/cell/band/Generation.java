package dev.mccomm.cell.band;

import org.bukkit.Material;

/** A tower generation. {@code upgradeMaterial} is null for generation 1 (no cost). */
public record Generation(int level, String model, Material upgradeMaterial, int upgradeAmount) {
}
