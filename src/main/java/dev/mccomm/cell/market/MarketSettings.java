package dev.mccomm.cell.market;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/** The {@code market} section of config.yml (and {@code simulation.mode}). */
public record MarketSettings(
        boolean enabled,              // simulation.mode == market
        double referencePrice,        // fair price per day customers compare against
        double rivalsWeight,          // how much rivals' prices count in the reference (0..1)
        double baseChurn,             // share of customers leaving per day even when happy
        double unhappyChurn,          // extra churn per day at satisfaction 0
        double priceChurn,            // extra churn per day per (price ratio - 1)^2
        double refuseRatio,           // at this price ratio customers leave en masse and won't join
        double refuseChurn,
        double shopPerDay,            // share of carrier-less customers shopping each day
        double wQuality, double wCoverage, double wSupport, double wPrice,
        int cellSize,
        double[] bandWeights,
        double switchPerDay,          // share of each carrier's customers comparing carriers each day
        double loyalty,               // a rival must be this much more attractive (0.15 = 15%) to win them
        double speedWeight,           // share of the service score that comes from speed (rest: reliability)
        double speedReference,        // Mbps that counts as full marks for speed
        double growthPerDay,          // the market's pool of customers grows by this share per day (0 = never)
        int growthMax) {              // growth stops at this pool size (0 = no limit)

    public static MarketSettings read(FileConfiguration c) {
        ConfigurationSection m = c.getConfigurationSection("market");
        if (m == null) m = c.createSection("market");
        double[] bw = new double[8];
        double[] def = {0.3, 0.5, 0.75, 0.85, 0.9, 1, 1, 1};
        String[] keys = {"2g", "3g", "4g", "4g+", "5g-low", "5g-mid", "5g-high", "5g-mmwave"};
        for (int i = 0; i < 8; i++) bw[i] = Math.clamp(m.getDouble("band-weight." + keys[i], def[i]), 0, 1);
        return new MarketSettings(
                !"fixed".equalsIgnoreCase(c.getString("simulation.mode", "market")),
                Math.max(0.01, m.getDouble("reference-price-per-day", 12)),
                Math.clamp(m.getDouble("rivals-weight", 0.5), 0, 1),
                Math.clamp(m.getDouble("churn.base", 0.01), 0, 1),
                Math.clamp(m.getDouble("churn.unhappy", 0.12), 0, 1),
                Math.clamp(m.getDouble("churn.price", 0.03), 0, 1),
                Math.max(1.1, m.getDouble("refuse-price-ratio", 3)),
                Math.clamp(m.getDouble("churn.refused", 0.25), 0, 1),
                Math.clamp(m.getDouble("shop-per-day", 0.3), 0, 1),
                Math.max(0, m.getDouble("weights.quality", 0.35)),
                Math.max(0, m.getDouble("weights.coverage", 0.20)),
                Math.max(0, m.getDouble("weights.support", 0.20)),
                Math.max(0, m.getDouble("weights.price", 0.25)),
                Math.clamp(m.getInt("coverage-cell-size", 100), 16, 1000),
                bw,
                Math.clamp(m.getDouble("switching.per-day", 0.02), 0, 1),
                Math.max(0, m.getDouble("switching.loyalty", 0.15)),
                Math.clamp(m.getDouble("service.speed-weight", 0.4), 0, 1),
                Math.max(1, m.getDouble("service.speed-reference-mbps", 300)),
                Math.clamp(m.getDouble("growth.per-day", 0.01), 0, 1),
                Math.max(0, m.getInt("growth.max-size", 10_000)));
    }
}
