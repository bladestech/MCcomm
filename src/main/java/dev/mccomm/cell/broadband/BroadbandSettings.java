package dev.mccomm.cell.broadband;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.regex.Pattern;

/** The {@code broadband} section of config.yml: ISPs, access technologies and home networks. */
public record BroadbandSettings(
        boolean enabled,
        EnumMap<Technology, TechSpec> techs,
        int maxIspsPerPlayer,        // -1 = unlimited
        int maxNodesPerIsp,          // -1 = unlimited
        int maxRoutersPerPlayer,     // -1 = unlimited
        int maxComputersPerPlayer,   // -1 = unlimited
        int maxPlansPerIsp,
        int nodeMinSpacing,          // blocks between one ISP's nodes of the same technology
        Pattern ispNamePattern,
        Pattern nodeNamePattern,
        double wifiRange,            // blocks from a Wi-Fi 4 router that its Wi-Fi reaches
        int installDelaySeconds,     // the "technician visit" between ordering and going live
        int suspendHours,            // how long a missed payment keeps the line suspended before it is cut
        int warnHours,               // warn this long before a renewal if the wallet is short
        int upkeepPeriodHours,
        int minPeriodHours,
        int maxPeriodHours,
        int speedtestCooldown,
        double jitterPercent,
        boolean phonesUseWifi,       // phones and /internet use home Wi-Fi first, free of mobile data
        boolean guestWifiDefault,
        double tierUpgradePercent,   // node upgrade cost: this share of the node cost, times the current tier
        double tierUpkeepStep,       // each tier above 1 adds this share of the base upkeep
        double throttleMbps,         // fair-use speed once a plan's data allowance is used up
        double bundleMaxDiscount,    // the most a bundle can take off a price, in percent
        Wifi wifi,
        Transit transit,
        Faults faults,
        FixedWireless fixedWireless,
        Market market,
        Browser browser,
        boolean screens) {       // layered display screens on node, router and computer signs (like cell towers)

    /** One access technology. {@code range <= 0} means it reaches anywhere in the world (satellite). */
    public record TechSpec(
            Technology tech,
            String name,
            Material icon,
            String blurb,
            double range,
            double maxMbps,
            double uploadRatio,
            int pingMs,
            double falloff,         // 0 = distance doesn't matter; 1 = linear loss; higher = flat, then a cliff
            double minFactor,       // speed share kept at the edge of the range
            boolean needsSky,       // a clear view of the sky above the router (a dish)
            double rainFactor,      // speed multiplier in rain
            double thunderFactor,   // speed multiplier in thunderstorms
            int nodeCapacity,       // customers one node (or tower, for fixed wireless) serves at full speed (per tier)
            long nodeCost,
            long nodeUpkeep,        // per upkeep period
            long installFee,        // default installation fee of a plan
            long starterPrice,      // price of the plan a new ISP starts with
            int maxTier,
            double dropCostPerBlock, // last mile: install cost per block from the node to the home
            double faultPerHour,     // chance per hour that a node develops a fault
            double stormFaultFactor, // fault chance multiplier in storms
            long repairCost) {       // paying a technician to fix a fault at once
    }

    /** A Wi-Fi router generation: how far it reaches, how many guests it takes, and what the upgrade costs. */
    public record WifiTier(String name, double rangeFactor, int maxGuests, long price) {
    }

    /** Wi-Fi extenders: they repeat a router, reaching {@code rangeFactor} of its range at {@code speedFactor} of its speed. */
    public record ExtenderSpec(double rangeFactor, double speedFactor, double extraPingMs, long price, int maxPerPlayer) {
    }

    public record Wifi(boolean calling, boolean autoConnect, double wallLoss, List<WifiTier> tiers, ExtenderSpec extender) {
    }

    public record Transit(double mbpsPerTier, int maxTier, long upkeepPerTier, long upgradeCost,
                          double utilization, double peerBonus) {
    }

    public record Faults(boolean enabled, int autoRepairHours) {
    }

    public record FixedWireless(double indoorDb) {
    }

    public record Market(int households, int stepMinutes, boolean pays) {
    }

    public record Browser(boolean enabled, double pageMb, int maxListings, int listingsPerPlayer, int listingDays,
                          int mailInbox, int mailLength, List<String> news) {
    }

    public TechSpec spec(Technology t) {
        return techs.get(t);
    }

    public WifiTier tier(int tier) {
        List<WifiTier> t = wifi.tiers();
        return t.get(Math.clamp(tier, 1, t.size()) - 1);
    }

    public int maxWifiTier() {
        return wifi.tiers().size();
    }

    /** The farthest any router can reach, used to skip far-away checks cheaply. */
    public double maxReach() {
        double f = 1;
        for (WifiTier t : wifi.tiers()) f = Math.max(f, t.rangeFactor());
        return wifiRange * f;
    }

    public static BroadbandSettings read(FileConfiguration c, java.util.logging.Logger log) {
        ConfigurationSection s = c.getConfigurationSection("broadband");
        if (s == null) s = c.createSection("broadband");

        EnumMap<Technology, TechSpec> techs = new EnumMap<>(Technology.class);
        ConfigurationSection ts = s.getConfigurationSection("technologies");
        for (Technology t : Technology.values()) {
            TechSpec def = fallback(t);
            ConfigurationSection x = ts == null ? null : ts.getConfigurationSection(t.key());
            if (x == null) {
                techs.put(t, def);
                continue;
            }
            techs.put(t, new TechSpec(t,
                    x.getString("name", def.name()),
                    material(x.getString("icon"), def.icon(), log),
                    x.getString("description", def.blurb()),
                    x.getDouble("range", def.range()),
                    Math.max(0.1, x.getDouble("max-mbps", def.maxMbps())),
                    Math.max(0, x.getDouble("upload-ratio", def.uploadRatio())),
                    Math.max(1, x.getInt("ping-ms", def.pingMs())),
                    Math.max(0, x.getDouble("falloff", def.falloff())),
                    Math.clamp(x.getDouble("min-factor", def.minFactor()), 0.01, 1),
                    x.getBoolean("needs-sky", def.needsSky()),
                    Math.clamp(x.getDouble("rain-factor", def.rainFactor()), 0, 1),
                    Math.clamp(x.getDouble("thunder-factor", def.thunderFactor()), 0, 1),
                    Math.max(1, x.getInt("node-capacity", def.nodeCapacity())),
                    Math.max(0, x.getLong("node-cost", def.nodeCost())),
                    Math.max(0, x.getLong("node-upkeep", def.nodeUpkeep())),
                    Math.max(0, x.getLong("install-fee", def.installFee())),
                    Math.max(0, x.getLong("starter-price", def.starterPrice())),
                    Math.clamp(x.getInt("max-tier", def.maxTier()), 1, 10),
                    Math.max(0, x.getDouble("drop-cost-per-block", def.dropCostPerBlock())),
                    Math.clamp(x.getDouble("fault-chance-per-hour", def.faultPerHour()), 0, 1),
                    Math.max(1, x.getDouble("storm-fault-factor", def.stormFaultFactor())),
                    Math.max(0, x.getLong("repair-cost", def.repairCost()))));
        }

        Pattern ispPat, nodePat;
        try {
            ispPat = Pattern.compile(s.getString("isp-name-pattern", "^[A-Za-z0-9_&.\\-]{2,20}$"));
        } catch (Exception e) {
            ispPat = Pattern.compile("^[A-Za-z0-9_&.\\-]{2,20}$");
        }
        try {
            nodePat = Pattern.compile(s.getString("node-name-pattern", "^[A-Za-z0-9_\\-]{1,12}$"));
        } catch (Exception e) {
            nodePat = Pattern.compile("^[A-Za-z0-9_\\-]{1,12}$");
        }

        // Wi-Fi generations.
        List<WifiTier> tiers = new ArrayList<>();
        ConfigurationSection wf = s.getConfigurationSection("wifi");
        ConfigurationSection tl = wf == null ? null : wf.getConfigurationSection("tiers");
        if (tl != null) {
            for (String key : tl.getKeys(false)) {
                ConfigurationSection t = tl.getConfigurationSection(key);
                if (t == null) continue;
                tiers.add(new WifiTier(t.getString("name", "Wi-Fi " + key), Math.max(0.25, t.getDouble("range-factor", 1)),
                        Math.max(0, t.getInt("max-guests", 2)), Math.max(0, t.getLong("price", 0))));
            }
        }
        if (tiers.isEmpty()) {
            tiers.add(new WifiTier("Wi-Fi 4", 1.0, 2, 0));
            tiers.add(new WifiTier("Wi-Fi 5", 1.25, 4, 80));
            tiers.add(new WifiTier("Wi-Fi 6", 1.5, 8, 200));
            tiers.add(new WifiTier("Wi-Fi 7", 2.0, 16, 500));
        }
        ConfigurationSection ex = wf == null ? null : wf.getConfigurationSection("extender");
        ExtenderSpec extender = new ExtenderSpec(
                ex == null ? 0.7 : Math.clamp(ex.getDouble("range-factor", 0.7), 0.1, 2),
                ex == null ? 0.6 : Math.clamp(ex.getDouble("speed-factor", 0.6), 0.05, 1),
                ex == null ? 6 : Math.max(0, ex.getDouble("extra-ping-ms", 6)),
                ex == null ? 60 : Math.max(0, ex.getLong("price", 60)),
                ex == null ? 4 : ex.getInt("max-per-player", 4));
        Wifi wifi = new Wifi(wf == null || wf.getBoolean("calling", true), wf == null || wf.getBoolean("auto-connect", true), wf == null ? 1.5 : Math.max(0, wf.getDouble("wall-loss-blocks", 1.5)), List.copyOf(tiers), extender);

        ConfigurationSection tr = s.getConfigurationSection("transit");
        Transit transit = new Transit(
                tr == null ? 5000 : Math.max(1, tr.getDouble("mbps-per-tier", 5000)),
                tr == null ? 5 : Math.clamp(tr.getInt("max-tier", 5), 1, 20),
                tr == null ? 30 : Math.max(0, tr.getLong("upkeep-per-tier", 30)),
                tr == null ? 400 : Math.max(0, tr.getLong("upgrade-cost", 400)),
                tr == null ? 0.2 : Math.clamp(tr.getDouble("utilization", 0.2), 0.01, 1),
                tr == null ? 0.25 : Math.max(0, tr.getDouble("peer-bonus", 0.25)));

        ConfigurationSection fl = s.getConfigurationSection("faults");
        Faults faults = new Faults(fl == null || fl.getBoolean("enabled", true), fl == null ? 6 : Math.max(1, fl.getInt("auto-repair-hours", 6)));

        ConfigurationSection fw = s.getConfigurationSection("fixed-wireless");
        FixedWireless fixed = new FixedWireless(fw == null ? 8 : Math.max(0, fw.getDouble("indoor-loss-db", 8)));

        ConfigurationSection mk = s.getConfigurationSection("market");
        Market market = new Market(mk == null ? 0 : Math.max(0, mk.getInt("households", 0)),
                mk == null ? 30 : Math.max(1, mk.getInt("step-minutes", 30)), mk == null || mk.getBoolean("pays", true));

        ConfigurationSection br = s.getConfigurationSection("browser");
        List<String> news = br == null ? List.of() : br.getStringList("news");
        Browser browser = new Browser(br == null || br.getBoolean("enabled", true),
                br == null ? 4 : Math.max(0, br.getDouble("page-mb", 4)),
                br == null ? 60 : Math.max(1, br.getInt("max-listings", 60)),
                br == null ? 3 : Math.max(1, br.getInt("listings-per-player", 3)),
                br == null ? 7 : Math.max(1, br.getInt("listing-days", 7)),
                br == null ? 30 : Math.max(1, br.getInt("mail-inbox", 30)),
                br == null ? 200 : Math.max(20, br.getInt("mail-length", 200)),
                news.isEmpty() ? List.of("Welcome to the web! Home internet is open for business.",
                        "ISPs: keep your upkeep paid and your nodes repaired, or customers will leave.",
                        "Tip: a computer shows every provider's measured speed on its router's Providers tab.") : List.copyOf(news));

        return new BroadbandSettings(
                s.getBoolean("enabled", true),
                techs,
                s.getInt("max-isps-per-player", 1),
                s.getInt("max-nodes-per-isp", -1),
                s.getInt("max-routers-per-player", 3),
                s.getInt("max-computers-per-player", 6),
                Math.max(1, s.getInt("max-plans-per-isp", 8)),
                Math.max(0, s.getInt("node-min-spacing", 16)),
                ispPat, nodePat,
                Math.max(2, s.getDouble("wifi-range", 24)),
                Math.max(0, s.getInt("install-delay-seconds", 20)),
                Math.max(0, s.getInt("suspend-hours", 12)),
                Math.max(0, s.getInt("warn-hours-before", 6)),
                Math.max(1, s.getInt("upkeep-period-hours", 24)),
                Math.max(1, s.getInt("min-period-hours", 1)),
                Math.max(1, s.getInt("max-period-hours", 720)),
                Math.max(0, s.getInt("speedtest-cooldown", 30)),
                Math.clamp(s.getDouble("jitter-percent", 5), 0, 50),
                s.getBoolean("phones-use-wifi", true),
                s.getBoolean("guest-wifi-default", false),
                Math.max(0, s.getDouble("tier-upgrade-percent", 60)) / 100.0,
                Math.max(0, s.getDouble("tier-upkeep-step", 50)) / 100.0,
                Math.max(0, s.getDouble("throttle-mbps", 1)),
                Math.clamp(s.getDouble("bundle-max-discount", 30), 0, 90),
                wifi, transit, faults, fixed, market, browser, s.getBoolean("fancy-screens", true));
    }

    /** Built-in values, used when a technology is missing from config.yml. */
    private static TechSpec fallback(Technology t) {
        return switch (t) {
            case DSL -> new TechSpec(t, "DSL", Material.COPPER_INGOT,
                    "Phone-line copper. Cheap and everywhere near an exchange, but speed drops fast with distance.",
                    400, 40, 0.1, 30, 1.0, 0.1, false, 1, 1, 40, 150, 3, 10, 10, 3, 0.05, 0.01, 4, 40);
            case CABLE -> new TechSpec(t, "Cable", Material.LEAD,
                    "Coaxial cable. Fast and fairly steady, but the neighbourhood shares each node.",
                    800, 400, 0.1, 15, 2.0, 0.5, false, 1, 1, 25, 450, 6, 25, 25, 3, 0.15, 0.01, 3, 100);
            case FIBER -> new TechSpec(t, "Fiber", Material.END_ROD,
                    "Light down glass. Huge speeds and tiny ping that barely care about distance, but nodes are costly.",
                    1500, 10000, 1.0, 4, 4.0, 0.9, false, 1, 1, 80, 1500, 12, 80, 55, 3, 0.4, 0.002, 1, 300);
            case SATELLITE -> new TechSpec(t, "Satellite", Material.SPYGLASS,
                    "A dish and a view of the sky. Works anywhere in the world, but slow, laggy and weather-sensitive.",
                    0, 120, 0.2, 550, 0, 1.0, true, 0.5, 0.2, 20, 900, 10, 60, 30, 3, 0, 0.004, 2, 200);
            case FIXED_WIRELESS -> new TechSpec(t, "5G Home", Material.BLAZE_ROD,
                    "Home internet over a mobile carrier's towers. No cable to dig, but it is only as good as the signal at your door.",
                    0, 1000, 0.3, 25, 0, 1.0, false, 1, 1, 12, 0, 0, 20, 35, 1, 0, 0, 1, 0);
        };
    }

    private static Material material(String name, Material def, java.util.logging.Logger log) {
        if (name == null) return def;
        Material m = Material.matchMaterial(name);
        if (m == null) {
            log.warning("Unknown material in config: " + name);
            return def;
        }
        return m;
    }
}
