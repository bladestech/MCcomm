package dev.mccomm.cell.economy;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

/** The {@code sales} section of config.yml: temporary plan discounts and the regulator that limits them. */
public record SaleSettings(boolean enabled, int minPercent, int maxPercent, int maxHours, int cleanPriceDays,
                           int windowDays, int allowedDays, List<Integer> warnAt, long fineMin, double finePerCustomer,
                           int offenceMemoryDays) {

    public static SaleSettings read(FileConfiguration c) {
        ConfigurationSection s = c.getConfigurationSection("sales");
        if (s == null) s = c.createSection("sales");
        List<Integer> warn = s.getIntegerList("warn-at");
        int min = Math.clamp(s.getInt("min-percent", 5), 1, 99);
        return new SaleSettings(
                s.getBoolean("enabled", true),
                min,
                Math.clamp(s.getInt("max-percent", 70), min, 99),
                Math.max(1, s.getInt("max-hours", 168)),
                Math.max(0, s.getInt("clean-price-days", 7)),
                Math.max(1, s.getInt("window-days", 30)),
                Math.max(0, s.getInt("allowed-days", 10)),
                warn.isEmpty() ? List.of(8, 10) : List.copyOf(warn),
                Math.max(0, s.getLong("fine-min", 100)),
                Math.max(0, s.getDouble("fine-per-customer", 2)),
                Math.max(1, s.getInt("offence-memory-days", 90)));
    }
}
