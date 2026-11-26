package dev.mccomm.cell.workforce;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * The {@code operations} section of config.yml (older configs: {@code workforce}): tower wear, repairs, contractors
 * and the operations budget.
 */
public record WorkforceSettings(
        boolean enabled,
        // wear (points per day, 0..100)
        double wearIdlePerDay, double wearPerSubscriberDay, double wearPerGb,
        double dispatchAt,
        double[] wearSteps, double[] wearSpeed, // wear thresholds and the speed at each; bands dropped = step index
        // repairs
        int setOutMinutes, int repairBaseMinutes, double repairPerWearMinutes,
        long partsBase, double partsPerWear,
        // contractors (when field crews are below min funding, or unfunded)
        int contractorAfterMinutes, int contractorDelayHours, double contractorMultiplier, long contractorMin,
        long contractorLabourPerDay,
        int timewarpStepMinutes,
        // the budget
        double[] defaultFunding, double averageDays, int selfService,
        double fieldPerMacro, double fieldPerSmall, double fieldPerSubscriber, double fieldMinFunding,
        double carePerSubscriber, double networkPerSubscriber, double networkMinSpeed,
        double ispFieldPerUpkeep, double ispFieldPerCustomer, double ispCarePerCustomer, double ispNetworkPerCustomer,
        double transitPerGb, double simGbPerDay) {

    public static WorkforceSettings read(FileConfiguration c) {
        ConfigurationSection w = c.getConfigurationSection("operations");
        if (w == null) w = c.getConfigurationSection("workforce"); // configs from before operations went virtual
        if (w == null) w = c.createSection("operations");
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
                Math.max(0, w.getInt("repairs.set-out-minutes", 5)),
                Math.max(0, w.getInt("repairs.base-minutes", 20)),
                Math.max(0, w.getDouble("repairs.minutes-per-wear", 0.5)),
                Math.max(0, w.getLong("repairs.parts-base", 5)),
                Math.max(0, w.getDouble("repairs.parts-per-wear", 0.2)),
                Math.max(0, w.getInt("contractors.after-minutes", 30)),
                Math.max(0, w.getInt("contractors.delay-hours", 6)),
                Math.max(1, w.getDouble("contractors.cost-multiplier", 3)),
                Math.max(0, w.getLong("contractors.minimum", 60)),
                Math.max(0, w.getLong("contractors.labour-per-day", w.getLong("technicians.wage", 150))),
                Math.max(1, w.getInt("timewarp-step-minutes", 10)),
                new double[]{
                        Math.clamp(w.getDouble("default-funding.field-crews", 1.0), 0, OpsPart.FIELD.max),
                        Math.clamp(w.getDouble("default-funding.customer-care", 1.0), 0, 1),
                        Math.clamp(w.getDouble("default-funding.network-ops", 1.0), 0, 1)},
                Math.max(0.1, w.getDouble("average-days", 3)),
                Math.max(0, w.getInt("self-service-subscribers", 25)),
                Math.max(0, w.getDouble("field-crews.per-macro-cell", 15)),
                Math.max(0, w.getDouble("field-crews.per-small-cell", 6)),
                Math.max(0, w.getDouble("field-crews.per-subscriber", 0.25)),
                Math.clamp(w.getDouble("field-crews.min-funding", 0.25), 0.01, 1),
                Math.max(0, w.getDouble("customer-care.per-subscriber", 1.7)),
                Math.max(0, w.getDouble("network-ops.per-subscriber", 2.0)),
                Math.clamp(w.getDouble("network-ops.min-speed", w.getDouble("engineers.min-speed", 0.6)), 0.05, 1),
                Math.max(0, w.getDouble("isp.field-crews-per-upkeep", 0.5)),
                Math.max(0, w.getDouble("isp.field-crews-per-customer", 0.1)),
                Math.max(0, w.getDouble("isp.customer-care-per-customer", 1.2)),
                Math.max(0, w.getDouble("isp.network-ops-per-customer", 1.5)),
                Math.max(0, w.getDouble("transit.cr-per-gb", 1)),
                Math.max(0, w.getDouble("transit.simulated-gb-per-day", 1)));
    }

    public double defaultFunding(OpsPart p) {
        return defaultFunding[p.ordinal()];
    }
}
