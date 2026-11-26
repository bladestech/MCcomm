package dev.mccomm.cell.tower;

import dev.mccomm.cell.band.Band;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.EnumSet;
import java.util.Set;

/** The {@code small-cells} section of config.yml. */
public record SmallCellSettings(
        boolean enabled,
        double rangePercent,      // share of the band's range (no height bonus)
        double wallLoss,          // wall loss multiplier near the cell
        double capacityMultiplier,
        int minGeneration,        // small models need this generation, and only bands from it up
        long upkeepBase, long upkeepPerGen,
        double wearFactor,        // wear per subscriber multiplier
        double repairFactor,      // repair time multiplier
        int wirelessRange,        // blocks to the macro cell it can link to without a backhaul block
        double wirelessMultiplier,
        int perMacro,             // small cells allowed per macro cell
        int minSpacing,           // blocks between a carrier's small cells
        double costPercent,       // manufacturing cost compared with a macro with the same bands
        long researchPoints,
        double coverageWeight,
        Set<Band> fullRangeBands) { // bands where a small cell reaches as far as a macro cell's base range (100%, not rangePercent)

    /** Share of the band's range a small cell reaches: all of it on the full-range bands, else {@code rangePercent}. */
    public double rangeShare(Band b) {
        return fullRangeBands.contains(b) ? 1.0 : rangePercent;
    }

    private static Set<Band> fullBands(ConfigurationSection s) {
        Set<Band> out = EnumSet.noneOf(Band.class);
        if (!s.contains("full-range-bands")) {
            out.add(Band.G5_HIGH);
            out.add(Band.G5_MMWAVE);
            return out;
        }
        for (String key : s.getStringList("full-range-bands")) {
            Band b = Band.fromKey(key);
            if (b != null) out.add(b);
        }
        return out;
    }

    public static SmallCellSettings read(FileConfiguration c) {
        ConfigurationSection s = c.getConfigurationSection("small-cells");
        if (s == null) s = c.createSection("small-cells");
        return new SmallCellSettings(
                s.getBoolean("enabled", true),
                Math.clamp(s.getDouble("range-percent", 15) / 100.0, 0.01, 1),
                Math.clamp(s.getDouble("wall-loss", 0.5), 0, 1),
                Math.max(1, s.getDouble("capacity-multiplier", 2)),
                Math.max(1, s.getInt("min-generation", 3)),
                Math.max(0, s.getLong("upkeep-base", 1)),
                Math.max(0, s.getLong("upkeep-per-generation", 1)),
                Math.max(0, s.getDouble("wear-factor", 0.5)),
                Math.max(0.05, s.getDouble("repair-time-factor", 0.5)),
                Math.max(0, s.getInt("wireless-backhaul-range", 200)),
                Math.clamp(s.getDouble("wireless-backhaul-multiplier", 0.8), 0, 1),
                Math.max(0, s.getInt("per-macro", 8)),
                Math.max(0, s.getInt("min-spacing", 8)),
                Math.clamp(s.getDouble("cost-percent", 40) / 100.0, 0.01, 2),
                Math.max(0, s.getLong("research-points", 300)),
                Math.clamp(s.getDouble("coverage-weight", 0.5), 0, 1),
                fullBands(s));
    }
}
