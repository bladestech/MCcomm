package dev.mccomm.cell.workforce;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/** The {@code workforce} section of config.yml: wear, stations, technicians, repairs and contractors. */
public record WorkforceSettings(
        boolean enabled,
        // wear (points per day, 0..100)
        double wearIdlePerDay, double wearPerSubscriberDay, double wearPerGb,
        double dispatchAt,
        double[] wearSteps, double[] wearSpeed, // wear thresholds and the speed at each; bands dropped = step index
        // technicians
        long technicianWage, double[] levelWage, double[] levelSpeed, double[] levelOdds,
        int recruitDays, int severanceDays, int quitAfterOwedDays, int maxStaff,
        // stations and dispatch
        long rentBase, long rentPerSlot, int serviceRadius, double blocksPerMinute,
        int setOutMinutes, int repairBaseMinutes, double repairPerWearMinutes,
        int barrelsPerStep, double barrelStep, double barrelMax,
        int maxRoomBlocks, int recheckMinutes,
        long partsBase, double partsPerWear,
        // contractors
        int contractorAfterMinutes, int contractorDelayHours, double contractorMultiplier, long contractorMin,
        int timewarpStepMinutes) {

    public static WorkforceSettings read(FileConfiguration c) {
        ConfigurationSection w = c.getConfigurationSection("workforce");
        if (w == null) w = c.createSection("workforce");
        return new WorkforceSettings(
                w.getBoolean("enabled", true),
                w.getDouble("wear.idle-per-day", 2),
                w.getDouble("wear.per-subscriber-per-day", 0.10),
                w.getDouble("wear.per-gb", 0.02),
                w.getDouble("wear.dispatch-at", 30),
                new double[]{40, 60, 80, 100},
                new double[]{
                        w.getDouble("wear.speed-at-40", 0.85),
                        w.getDouble("wear.speed-at-60", 0.6),
                        w.getDouble("wear.speed-at-80", 0.4),
                        0},
                Math.max(0, w.getLong("technicians.wage", 150)),
                new double[]{w.getDouble("technicians.junior-wage", 0.75), 1.0, w.getDouble("technicians.senior-wage", 1.4)},
                new double[]{w.getDouble("technicians.junior-repair-time", 1.33), 1.0, w.getDouble("technicians.senior-repair-time", 0.74)},
                new double[]{w.getDouble("technicians.junior-odds", 0.45), w.getDouble("technicians.regular-odds", 0.40),
                        w.getDouble("technicians.senior-odds", 0.15)},
                Math.max(0, w.getInt("technicians.recruitment-fee-days", 3)),
                Math.max(0, w.getInt("technicians.severance-days", 7)),
                Math.max(1, w.getInt("technicians.quit-after-unpaid-days", 3)),
                Math.max(1, w.getInt("technicians.max-per-company", 60)),
                Math.max(0, w.getLong("stations.rent-base", 10)),
                Math.max(0, w.getLong("stations.rent-per-bed", 2)),
                Math.max(16, w.getInt("stations.service-radius", 2000)),
                Math.max(1, w.getDouble("stations.travel-blocks-per-minute", 150)),
                Math.max(0, w.getInt("repairs.set-out-minutes", 5)),
                Math.max(0, w.getInt("repairs.base-minutes", 20)),
                Math.max(0, w.getDouble("repairs.minutes-per-wear", 0.5)),
                Math.max(1, w.getInt("stations.barrels-per-step", 4)),
                Math.max(0, w.getDouble("stations.barrel-step", 0.05)),
                Math.clamp(w.getDouble("stations.barrel-max", 0.25), 0, 0.9),
                Math.max(64, w.getInt("stations.max-room-blocks", 4096)),
                Math.max(1, w.getInt("stations.recheck-minutes", 10)),
                Math.max(0, w.getLong("repairs.parts-base", 5)),
                Math.max(0, w.getDouble("repairs.parts-per-wear", 0.2)),
                Math.max(0, w.getInt("contractors.after-minutes", 30)),
                Math.max(0, w.getInt("contractors.delay-hours", 6)),
                Math.max(1, w.getDouble("contractors.cost-multiplier", 3)),
                Math.max(0, w.getLong("contractors.minimum", 60)),
                Math.max(1, w.getInt("timewarp-step-minutes", 10)));
    }

    /** Daily wage for a technician of this level (0 junior, 1 regular, 2 senior). */
    public long wage(int level) {
        return Math.round(technicianWage * levelWage[Math.clamp(level, 0, 2)]);
    }
}
