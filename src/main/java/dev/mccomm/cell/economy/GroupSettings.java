package dev.mccomm.cell.economy;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/** The {@code groups} section of config.yml: parent companies. */
public record GroupSettings(boolean enabled, int maxPerPlayer, int maxMembers, double transferFeePercent,
                            int bankruptcyExtraDays, double opsDiscountPerMember, double opsDiscountMax) {

    public static GroupSettings read(FileConfiguration c) {
        ConfigurationSection g = c.getConfigurationSection("groups");
        if (g == null) g = c.createSection("groups");
        return new GroupSettings(
                g.getBoolean("enabled", true),
                g.getInt("max-per-player", -1),
                g.getInt("max-members", -1),
                Math.clamp(g.getDouble("transfer-fee-percent", 2.5), 0, 100),
                Math.max(0, g.getInt("bankruptcy-extra-days", 7)),
                Math.clamp(g.getDouble("ops-discount-per-member", 0.05), 0, 1),
                Math.clamp(g.getDouble("ops-discount-max", 0.15), 0, 0.9));
    }
}
