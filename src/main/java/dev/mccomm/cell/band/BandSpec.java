package dev.mccomm.cell.band;

import org.bukkit.Material;

public record BandSpec(
        Band band,
        String name,
        double range,
        double maxMbps,
        double uploadRatio,
        int pingMs,
        double obstructionDb,
        double maxObstructionDb,
        int smsDelayTicks,
        int minGeneration,
        Material icon
) {
}
