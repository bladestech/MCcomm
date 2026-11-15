package dev.mccomm.cell;

import dev.mccomm.cell.band.BackhaulSpec;
import dev.mccomm.cell.band.Band;
import dev.mccomm.cell.band.BandSpec;
import dev.mccomm.cell.band.Generation;
import dev.mccomm.cell.economy.PhoneModel;
import dev.mccomm.cell.model.Tower;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/** Immutable snapshot of config.yml. Rebuilt on /celladmin reload. */
public final class Settings {

    public final String prefix;
    public final String emergencyNumber;
    public final EnumMap<Band, BandSpec> bands = new EnumMap<>(Band.class);
    public final TreeMap<Integer, Generation> generations = new TreeMap<>();
    public final Map<Material, BackhaulSpec> backhaul = new LinkedHashMap<>();

    public final int updateInterval;
    public final double bestDbm, cutoffDbm, falloffExponent;
    public final double[] barThresholds;
    public final int obstructionScanDistance, maxObstructionBlocks;
    public final double rainDb, thunderDb, undergroundGrace, undergroundDbPerBlock, undergroundMaxDb, antennaHeight;

    public final int usersBeforeSlowdown;
    private final List<Integer> capacityPerGen;
    public final double simulationWeight;
    public final boolean squaremapEnabled, squaremapCoverage, squaremapPerBand, squaremapCoverageHidden;
    public final int squaremapIntervalSeconds, squaremapIconSize;
    public final int speedtestCooldown;
    public final double jitterPercent, minSignalFactor;

    public final int maxCarriersPerPlayer, maxTowersPerCarrier;
    public final Pattern carrierNamePattern, towerNamePattern;
    public final long towerCost;
    public final boolean heightBonus;
    public final double heightFree, heightPerBlock, heightMax;
    public final int heightSampleRadius;
    public final int maxActive, maxInactive;

    public final int smsExpireMinutes, callRingSeconds, garbleBars;
    public final double garbleChance;
    public final boolean hudDefault, hudSidebar;
    public final String hudTitle;
    public final int inboxMax, contactsMax, promptTimeout;

    // economy
    public final String currencyName, currencySymbol;
    public final Map<Material, Long> oreValues = new LinkedHashMap<>();
    public final Map<String, PhoneModel> phones = new LinkedHashMap<>();
    public final String starterPhone;
    public final double resalePercent;
    public final int warnHours, overdueMultiplier, billingCheckSeconds, minPeriodHours, maxPeriodHours;
    public final String defaultPlanName;
    public final long defaultPlanPrice;
    public final int defaultPlanPeriod;
    public final int upkeepPeriodHours;
    public final long upkeepBase, upkeepPerGen;
    public final Map<Material, Long> upkeepBackhaul = new LinkedHashMap<>();
    public final int decayStepHours;
    public final double[] decaySpeed;
    public final double throttleMbps, mbPerCharSend, mbPerCharReceive;
    public final int internetMaxLength;
    public final int noticeHours, mvnoGraceHours;
    // manufacturing
    public final boolean genericEnabled;
    public final double genericMargin, upkeepSharePercent, rpPerCredit;
    public final long mfgBase, mfgPerGen;
    public final java.util.EnumMap<Band, Long> mfgBandCost = new java.util.EnumMap<>(Band.class);
    public final java.util.EnumMap<Band, Long> researchBandCost = new java.util.EnumMap<>(Band.class);
    public final Map<Integer, Long> researchGenCost = new java.util.TreeMap<>();
    public final int maxManufacturersPerPlayer;
    // companies
    public final double transferFeePercent, liquidationFeePercent, mvnoFarewellPercent, towerScrapPercent,
            stockRefundPercent, paperweightPercent;
    public final int valuationDays;
    public final double deprioritizedSpeed;

    /** Sign board geometry, in blocks, relative to the sign block's centre. */
    public record SignGeom(double width, double height, double centerY, double faceOffset) {
    }

    public final Material panelMaterial;
    public final double textFill;
    public final boolean fullBright;
    public final float yawOffset;
    public final SignGeom wallGeom;

    public Settings(FileConfiguration c, Logger log) {
        prefix = c.getString("prefix", "<dark_gray>[<aqua>Cell</aqua>]</dark_gray> ");
        emergencyNumber = c.getString("emergency-number", "909");

        ConfigurationSection bs = c.getConfigurationSection("bands");
        for (Band b : Band.values()) {
            ConfigurationSection s = bs == null ? null : bs.getConfigurationSection(b.key());
            if (s == null) {
                log.warning("Band " + b.key() + " missing in config, using fallback values.");
                bands.put(b, new BandSpec(b, b.key().toUpperCase(), 500, 10, 0.3, 50, 1, 10, 20, 1, Material.PAPER));
                continue;
            }
            bands.put(b, new BandSpec(
                    b,
                    s.getString("name", b.key()),
                    s.getDouble("range", 500),
                    s.getDouble("max-mbps", 10),
                    s.getDouble("upload-ratio", 0.3),
                    s.getInt("ping-ms", 50),
                    s.getDouble("obstruction-db", 1),
                    s.getDouble("max-obstruction-db", 10),
                    s.getInt("sms-delay-ticks", 20),
                    s.getInt("min-generation", 1),
                    material(s.getString("icon"), Material.PAPER, log)));
        }

        ConfigurationSection gs = c.getConfigurationSection("generations");
        if (gs != null) {
            for (String key : gs.getKeys(false)) {
                int lvl;
                try {
                    lvl = Integer.parseInt(key);
                } catch (NumberFormatException e) {
                    log.warning("Generation key '" + key + "' is not a number.");
                    continue;
                }
                ConfigurationSection g = gs.getConfigurationSection(key);
                if (g == null) continue;
                Material m = null;
                int amt = 0;
                ConfigurationSection cost = g.getConfigurationSection("upgrade-cost");
                if (cost != null) {
                    m = material(cost.getString("material"), null, log);
                    amt = Math.max(1, cost.getInt("amount", 1));
                }
                generations.put(lvl, new Generation(lvl, g.getString("model", "Gen " + lvl), m, amt));
            }
        }
        if (generations.isEmpty()) generations.put(1, new Generation(1, "CT-100", null, 0));

        ConfigurationSection hs = c.getConfigurationSection("backhaul");
        if (hs != null) {
            for (String key : hs.getKeys(false)) {
                Material m = material(key, null, log);
                if (m == null) continue;
                ConfigurationSection h = hs.getConfigurationSection(key);
                if (h == null) continue;
                backhaul.put(m, new BackhaulSpec(m, h.getString("name", key), h.getDouble("multiplier", 0.1)));
            }
        }

        updateInterval = Math.max(5, c.getInt("signal.update-interval-ticks", 20));
        bestDbm = c.getDouble("signal.best-dbm", -50);
        cutoffDbm = c.getDouble("signal.cutoff-dbm", -115);
        falloffExponent = Math.max(0.1, c.getDouble("signal.falloff-exponent", 1.0));
        List<Double> bars = c.getDoubleList("signal.bars");
        barThresholds = bars.size() >= 3
                ? new double[]{bars.get(0), bars.get(1), bars.get(2)}
                : new double[]{-75, -88, -101};
        obstructionScanDistance = c.getInt("signal.obstruction-scan-distance", 6);
        maxObstructionBlocks = c.getInt("signal.max-obstruction-blocks", 6);
        rainDb = c.getDouble("signal.rain-db", 3);
        thunderDb = c.getDouble("signal.thunder-db", 6);
        undergroundGrace = c.getDouble("signal.underground-grace-blocks", 8);
        undergroundDbPerBlock = c.getDouble("signal.underground-db-per-block", 0.4);
        undergroundMaxDb = c.getDouble("signal.underground-max-db", 18);
        antennaHeight = c.getDouble("signal.antenna-height", 1.5);

        usersBeforeSlowdown = Math.max(1, c.getInt("congestion.users-before-slowdown", 4));
        List<Integer> cap = c.getIntegerList("congestion.capacity-per-generation");
        simulationWeight = 1.0 / Math.max(1, c.getDouble("simulation.users-per-real-user", 500));
        squaremapEnabled = c.getBoolean("squaremap.enabled", true);
        squaremapCoverage = c.getBoolean("squaremap.coverage", true);
        squaremapPerBand = "bands".equalsIgnoreCase(c.getString("squaremap.coverage-style", "max"));
        squaremapCoverageHidden = c.getBoolean("squaremap.coverage-hidden-by-default", false);
        squaremapIntervalSeconds = Math.max(5, c.getInt("squaremap.update-seconds", 30));
        squaremapIconSize = Math.clamp(c.getInt("squaremap.icon-size", 20), 8, 64);
        capacityPerGen = cap.isEmpty() ? List.of(4, 6, 9, 13, 18) : cap;
        speedtestCooldown = c.getInt("speedtest.cooldown-seconds", 10);
        jitterPercent = c.getDouble("speedtest.jitter-percent", 10);
        minSignalFactor = c.getDouble("speedtest.min-signal-factor", 0.15);

        maxCarriersPerPlayer = c.getInt("carriers.max-per-player", 1);
        maxTowersPerCarrier = c.getInt("carriers.max-towers-per-carrier", -1);
        carrierNamePattern = Pattern.compile(c.getString("carriers.name-pattern", "^[A-Za-z0-9_&.\\-]{2,20}$"));
        towerNamePattern = Pattern.compile(c.getString("towers.name-pattern", "^[A-Za-z0-9_\\-]{1,12}$"));
        towerCost = Math.max(0, c.getLong("towers.creation-cost", 0));
        heightBonus = c.getBoolean("towers.height-bonus.enabled", true);
        heightFree = Math.max(0, c.getDouble("towers.height-bonus.free-blocks", 4));
        heightPerBlock = Math.max(0, c.getDouble("towers.height-bonus.percent-per-block", 1.0)) / 100.0;
        heightMax = Math.max(0, c.getDouble("towers.height-bonus.max-percent", 50)) / 100.0;
        heightSampleRadius = Math.clamp(c.getInt("towers.height-bonus.sample-radius", 12), 4, 64);
        maxActive = Math.max(1, c.getInt("subscriptions.max-active", 2));
        maxInactive = Math.max(0, c.getInt("subscriptions.max-inactive", 4));

        smsExpireMinutes = c.getInt("messaging.sms-expire-minutes", 10);
        callRingSeconds = c.getInt("messaging.call-ring-seconds", 30);
        garbleBars = c.getInt("messaging.garble-at-or-below-bars", 1);
        garbleChance = c.getDouble("messaging.garble-chance", 0.15);
        hudDefault = c.getBoolean("hud.default-enabled", true);
        hudTitle = c.getString("hud.title", "<gradient:#5EE7DF:#B490CA><b>MCcomm</b></gradient>");
        hudSidebar = !"actionbar".equalsIgnoreCase(c.getString("hud.style", "sidebar"));
        inboxMax = Math.clamp(c.getInt("phone.inbox-max", 45), 1, 45);
        contactsMax = Math.clamp(c.getInt("phone.contacts-max", 45), 1, 45);
        promptTimeout = Math.max(10, c.getInt("phone.prompt-timeout-seconds", 60));

        currencyName = c.getString("currency.name", "credits");
        currencySymbol = c.getString("currency.symbol", "cr");
        ConfigurationSection ores = c.getConfigurationSection("currency.ores");
        if (ores != null) {
            for (String k : ores.getKeys(false)) {
                Material m = material(k, null, log);
                if (m != null && ores.getLong(k) > 0) oreValues.put(m, ores.getLong(k));
            }
        }
        ConfigurationSection ph = c.getConfigurationSection("phones");
        if (ph != null) {
            for (String id : ph.getKeys(false)) {
                ConfigurationSection s = ph.getConfigurationSection(id);
                if (s == null) continue;
                Band max = Band.fromKey(s.getString("max-band", "3g"));
                if (max == null) {
                    log.warning("Phone " + id + " has an unknown max-band, using 3G.");
                    max = Band.G3;
                }
                phones.put(id.toLowerCase(), new PhoneModel(id.toLowerCase(), s.getString("name", id), max,
                        Math.clamp(s.getInt("sims", 1), 1, Math.max(1, c.getInt("subscriptions.max-active", 2))),
                        Math.max(0, s.getLong("price", 100)), material(s.getString("icon"), Material.PAPER, log)));
            }
        }
        String sp = c.getString("starter-phone", "");
        starterPhone = sp == null || sp.isBlank() || !phones.containsKey(sp.toLowerCase()) ? null : sp.toLowerCase();
        resalePercent = Math.clamp(c.getDouble("phone-resale-percent", 50), 0, 100);
        warnHours = Math.max(0, c.getInt("billing.warn-hours-before", 6));
        overdueMultiplier = Math.max(1, c.getInt("billing.overdue-multiplier", 4));
        billingCheckSeconds = Math.max(10, c.getInt("billing.check-interval-seconds", 60));
        minPeriodHours = Math.max(1, c.getInt("billing.min-period-hours", 1));
        maxPeriodHours = Math.max(minPeriodHours, c.getInt("billing.max-period-hours", 720));
        defaultPlanName = c.getString("billing.default-plan.name", "Standard");
        defaultPlanPrice = Math.max(0, c.getLong("billing.default-plan.price", 12));
        defaultPlanPeriod = Math.clamp(c.getInt("billing.default-plan.period-hours", 24), minPeriodHours, maxPeriodHours);
        upkeepPeriodHours = Math.max(1, c.getInt("upkeep.period-hours", 24));
        upkeepBase = Math.max(0, c.getLong("upkeep.base-per-tower", 4));
        upkeepPerGen = Math.max(0, c.getLong("upkeep.per-generation", 4));
        ConfigurationSection ub = c.getConfigurationSection("upkeep.backhaul");
        if (ub != null) {
            for (String k : ub.getKeys(false)) {
                Material m = material(k, null, log);
                if (m != null) upkeepBackhaul.put(m, Math.max(0, ub.getLong(k)));
            }
        }
        decayStepHours = Math.max(1, c.getInt("upkeep.decay.step-hours", 6));
        List<Double> ds = c.getDoubleList("upkeep.decay.speed");
        throttleMbps = Math.max(0.01, c.getDouble("data.throttle-mbps", 0.5));
        mbPerCharSend = Math.max(0, c.getDouble("internet.mb-per-char-send", 1.0));
        mbPerCharReceive = Math.max(0, c.getDouble("internet.mb-per-char-receive", 0.1));
        genericEnabled = c.getBoolean("manufacturing.generic.enabled", true);
        genericMargin = c.getDouble("manufacturing.generic.margin-percent", 40);
        upkeepSharePercent = Math.clamp(c.getDouble("manufacturing.upkeep-share-percent", 10), 0, 100);
        rpPerCredit = Math.max(0.01, c.getDouble("manufacturing.research-points-per-credit", 1));
        mfgBase = Math.max(0, c.getLong("manufacturing.cost.base", 20));
        mfgPerGen = Math.max(0, c.getLong("manufacturing.cost.per-generation", 15));
        maxManufacturersPerPlayer = c.getInt("manufacturing.max-per-player", 1);
        for (Band b : Band.values()) {
            mfgBandCost.put(b, Math.max(0, c.getLong("manufacturing.cost.bands." + b.key(), 5)));
            researchBandCost.put(b, Math.max(0, c.getLong("manufacturing.research.bands." + b.key(), 0)));
        }
        ConfigurationSection rg = c.getConfigurationSection("manufacturing.research.generations");
        if (rg != null) for (String k : rg.getKeys(false)) {
            try {
                researchGenCost.put(Integer.parseInt(k), Math.max(0, rg.getLong(k)));
            } catch (NumberFormatException ignored) {
            }
        }
        transferFeePercent = Math.clamp(c.getDouble("companies.transfer-fee-percent", 5), 0, 100);
        liquidationFeePercent = Math.clamp(c.getDouble("companies.liquidation-fee-percent", 10), 0, 100);
        mvnoFarewellPercent = Math.clamp(c.getDouble("companies.mvno-farewell-percent", 20), 0, 100);
        towerScrapPercent = Math.clamp(c.getDouble("companies.tower-scrap-percent", 30), 0, 100);
        stockRefundPercent = Math.clamp(c.getDouble("companies.stock-refund-percent", 50), 0, 100);
        paperweightPercent = Math.clamp(c.getDouble("companies.paperweight-resale-percent", 5), 0, 100);
        valuationDays = Math.max(0, c.getInt("companies.valuation-income-days", 30));
        noticeHours = Math.max(0, c.getInt("partners.notice-hours", 24));
        deprioritizedSpeed = Math.clamp(c.getDouble("partners.deprioritized-speed", 0.5), 0.05, 1.0);
        mvnoGraceHours = Math.max(0, c.getInt("partners.mvno-grace-hours", 24));
        internetMaxLength = Math.clamp(c.getInt("internet.max-length", 200), 1, 256);
        decaySpeed = ds.isEmpty() ? new double[]{0.6, 0.4, 0.25} : ds.stream().mapToDouble(Double::doubleValue).toArray();

        panelMaterial = material(c.getString("display.panel-material"), Material.BLACK_CONCRETE, log);
        textFill = Math.clamp(c.getDouble("display.text-fill", 0.88), 0.3, 1.0);
        fullBright = c.getBoolean("display.full-bright", true);
        yawOffset = (float) c.getDouble("display.yaw-offset", 0);
        wallGeom = geom(c, "display.wall-sign", new SignGeom(1.0, 0.5, 0.5208, -0.3958));
    }

    /** Range multiplier for an antenna this many blocks above the surrounding ground (1.0 = no bonus). */
    public double rangeMultiplier(double heightAboveGround) {
        if (!heightBonus) return 1.0;
        return 1.0 + Math.min(heightMax, Math.max(0, heightAboveGround - heightFree) * heightPerBlock);
    }

    /** "750 MB", "3.2 GB", "Unlimited" (for negative). */
    public static String data(double mb) {
        if (mb < 0) return "Unlimited";
        if (mb >= 1000) return String.format(java.util.Locale.ROOT, "%.1f GB", mb / 1000);
        return String.format(java.util.Locale.ROOT, "%.0f MB", mb);
    }

    public String money(long credits) {
        return String.format(java.util.Locale.ROOT, "%,d %s", credits, currencySymbol);
    }

    /** Upkeep one tower costs per upkeep period. */
    public long upkeepCost(Tower t) {
        long cost = upkeepBase + upkeepPerGen * Math.max(0, t.generation() - 1);
        if (t.backhaul() != null) cost += upkeepBackhaul.getOrDefault(t.backhaul(), 0L);
        return cost;
    }

    /** Decay stages before towers go silent: one per listed speed, the next stage is silence. */
    public int silentStage() {
        return decaySpeed.length + 1;
    }

    /** What the eShop pays when you sell this phone back. */
    public long resaleValue(PhoneModel m) {
        return (long) Math.floor(m.price() * resalePercent / 100.0);
    }

    public PhoneModel phone(String id) {
        return id == null ? null : phones.get(id);
    }

    /** Users one band of a tower of this generation serves at full speed before slowing down. */
    public int capacity(int generation) {
        if (generation < 1) return usersBeforeSlowdown;
        int idx = Math.min(generation, capacityPerGen.size()) - 1;
        return Math.max(1, capacityPerGen.get(idx));
    }

    public BandSpec spec(Band b) {
        return bands.get(b);
    }

    public Generation generation(int level) {
        Generation g = generations.get(level);
        return g != null ? g : generations.floorEntry(level) != null ? generations.floorEntry(level).getValue() : generations.firstEntry().getValue();
    }

    public int maxGeneration() {
        return generations.lastKey();
    }

    public boolean supports(int generation, Band band) {
        return spec(band).minGeneration() <= generation;
    }

    private static SignGeom geom(FileConfiguration c, String path, SignGeom def) {
        ConfigurationSection s = c.getConfigurationSection(path);
        if (s == null) return def;
        return new SignGeom(s.getDouble("width", def.width()), s.getDouble("height", def.height()),
                s.getDouble("center-y", def.centerY()), s.getDouble("face-offset", def.faceOffset()));
    }

    private static Material material(String name, Material def, Logger log) {
        if (name == null) return def;
        Material m = Material.matchMaterial(name);
        if (m == null) {
            log.warning("Unknown material in config: " + name);
            return def;
        }
        return m;
    }
}
