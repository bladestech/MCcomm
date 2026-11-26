package dev.mccomm.cell.market;

import dev.mccomm.cell.CellPlugin;
import dev.mccomm.cell.band.Band;
import dev.mccomm.cell.model.Carrier;
import dev.mccomm.cell.model.Plan;
import dev.mccomm.cell.model.Tower;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The simulated customer market. The server has a fixed pool of customers; each carrier holds some
 * of them ({@link Carrier#simulatedUsers()}), the rest have no carrier. Every hour:
 * <ul>
 *   <li>each carrier gets a satisfaction score from service quality, coverage, support staffing and price;</li>
 *   <li>some of its customers leave (more when unhappy or overpriced) and join the carrier-less pool;</li>
 *   <li>part of the pool shops around and signs up with carriers in proportion to how attractive they are.</li>
 * </ul>
 * Only active when {@code simulation.mode} is {@code market}; in {@code fixed} mode admins set counts by hand.
 */
public final class MarketService {
    private static final long HOUR = 3_600_000L;

    /** One carrier's standing in the market. Scores are 0..1, satisfaction 0..100. */
    public record Score(double quality, double coverage, double support, double price, double priceRatio,
                        double avgPrice, double satisfaction, double churnPerDay, double attraction, boolean refused) {
    }

    private final CellPlugin plugin;
    private Map<String, Score> scores = new HashMap<>();
    private long computedAt;
    private final Map<String, Long> shownRating = new HashMap<>(); // rating ×10 last shown on tower screens
    private final Map<String, Double> leaveAcc = new HashMap<>();
    private final Map<String, Double> joinAcc = new HashMap<>();
    private final Map<String, Double> switchAcc = new HashMap<>();
    private double shopAcc;
    private double growAcc;
    private final Map<String, int[]> today = new HashMap<>();     // carrier -> {joined, left} in the current day
    private final Map<String, int[]> yesterday = new HashMap<>(); // the last full day
    private int hourOfDay;

    public MarketService(CellPlugin plugin) {
        this.plugin = plugin;
    }

    private MarketSettings m() {
        return plugin.settings().market;
    }

    public boolean active() {
        return m().enabled();
    }

    public Score score(Carrier c) {
        Score s = scores.get(c.id());
        // Market mode refreshes hourly; otherwise (fixed mode) refresh on demand every few minutes.
        if (s == null || (!active() && System.currentTimeMillis() - computedAt > 300_000L)) {
            recompute();
            s = scores.get(c.id());
        }
        return s;
    }

    // ================================================================ ratings

    /** Public star rating, 1.0 to 5.0, from the satisfaction score. 0 if unknown. */
    public double stars(Carrier c) {
        Score s = score(c);
        return s == null ? 0 : 1 + 4 * s.satisfaction() / 100.0;
    }

    private static double part(double v) {
        return 1 + 4 * v;
    }

    /** Five stars, filled to the nearest whole star (MiniMessage). */
    public static String starBar(double stars) {
        int full = (int) Math.clamp(Math.round(stars), 0, 5);
        return "<#FFD24A>" + "★".repeat(full) + "<#5A5E66>" + "☆".repeat(5 - full);
    }

    public static String starText(double stars) {
        return String.format(java.util.Locale.ROOT, "%.1f", stars);
    }

    /** Rating lines for item lore: overall stars plus the four parts. */
    public List<String> ratingLore(Carrier c) {
        Score s = score(c);
        if (s == null) return List.of();
        double st = stars(c);
        return List.of(
                "<gray>Rating: " + starBar(st) + " <white>" + starText(st),
                "<dark_gray>Service ★" + starText(part(s.quality())) + " · Coverage ★" + starText(part(s.coverage())),
                "<dark_gray>Support ★" + starText(part(s.support())) + " · Price ★" + starText(part(s.price())));
    }

    /** Plain text for web popups: ★★★★☆ 4.2. */
    public String ratingPlain(Carrier c) {
        double st = stars(c);
        int full = (int) Math.clamp(Math.round(st), 0, 5);
        return "★".repeat(full) + "☆".repeat(5 - full) + " " + starText(st);
    }

    /** {joined, left} for the last full day. */
    public int[] lastDay(Carrier c) {
        return yesterday.getOrDefault(c.id(), new int[2]);
    }

    public int customers() {
        int n = 0;
        for (Carrier c : plugin.store().carriers()) n += c.simulatedUsers();
        return n;
    }

    /** Customers without a carrier. */
    public int unassigned() {
        return Math.max(0, plugin.store().marketSize() - customers());
    }

    // ================================================================= tick

    /** Runs with the billing tick; advances the market in whole hours (also during timewarps). */
    public void tick(long now) {
        if (!active()) return;
        long last = plugin.store().marketAt();
        if (last <= 0 || last > now) {
            plugin.store().marketAt(now);
            if (plugin.store().marketSize() <= 0) plugin.store().marketSize(customers()); // first run: today's users are the market
            recompute();
            return;
        }
        for (int guard = 0; guard < 24 * 60 && now - last >= HOUR; guard++) {
            last += HOUR;
            step(1 / 24.0);
        }
        plugin.store().marketAt(last);
    }

    /** Timewarp support. */
    public void shift(long ms) {
        if (plugin.store().marketAt() > 0) plugin.store().marketAt(plugin.store().marketAt() - ms);
    }

    /** Sets the market size; shrinking removes carrier-less customers first, then carriers' customers proportionally. */
    public void resize(int size) {
        size = Math.clamp(size, 0, 1_000_000);
        plugin.store().marketSize(size);
        int excess = customers() - size;
        if (excess > 0) {
            int total = customers();
            List<Carrier> list = new ArrayList<>(plugin.store().carriers());
            int removed = 0;
            for (Carrier c : list) {
                int cut = (int) Math.floor((double) c.simulatedUsers() * excess / total);
                c.simulatedUsers(c.simulatedUsers() - cut);
                removed += cut;
            }
            for (Carrier c : list) {
                if (removed >= excess) break;
                if (c.simulatedUsers() > 0) {
                    c.simulatedUsers(c.simulatedUsers() - 1);
                    removed++;
                }
            }
        }
        plugin.store().markDirty();
        recompute();
    }

    /**
     * Gives every carrier the same number of customers. In market mode the whole pool is shared out
     * (nobody is left without a carrier); in fixed mode the users carriers already have are evened out.
     *
     * @return how many carriers share the customers (0 when there is nobody to share)
     */
    public int spreadEvenly() {
        List<Carrier> list = new ArrayList<>(plugin.store().carriers());
        int total = active() ? Math.max(plugin.store().marketSize(), customers()) : customers();
        if (list.isEmpty() || total <= 0) return 0;
        if (active()) plugin.store().marketSize(total);
        for (int i = 0; i < list.size(); i++) list.get(i).simulatedUsers(total / list.size() + (i < total % list.size() ? 1 : 0));
        plugin.store().markDirty();
        recompute();
        plugin.signal().tick();
        return list.size();
    }

    /** Population growth: the pool of customers grows a little every hour, whatever else is switched on or off. */
    private void grow(double frac) {
        int size = plugin.store().marketSize();
        if (m().growthPerDay() <= 0 || size <= 0) return;
        int max = m().growthMax();
        if (max > 0 && size >= max) {
            growAcc = 0;
            return;
        }
        growAcc += size * m().growthPerDay() * frac;
        int add = (int) Math.floor(growAcc);
        if (add <= 0) return;
        growAcc -= add;
        plugin.store().marketSize(max > 0 ? Math.min(max, size + add) : size + add);
    }

    private void step(double frac) {
        recompute();
        grow(frac);
        // Leaving.
        for (Carrier c : plugin.store().carriers()) {
            Score s = scores.get(c.id());
            if (s == null || c.simulatedUsers() <= 0) continue;
            double acc = leaveAcc.getOrDefault(c.id(), 0.0) + c.simulatedUsers() * s.churnPerDay() * frac;
            int leave = (int) Math.min(c.simulatedUsers(), Math.floor(acc));
            leaveAcc.put(c.id(), acc - leave);
            if (leave > 0) {
                c.simulatedUsers(c.simulatedUsers() - leave);
                today.computeIfAbsent(c.id(), k -> new int[2])[1] += leave;
            }
        }
        // Switching: some customers compare carriers even when they're fine where they are, and move
        // to a rival that's clearly more attractive. This lets a better newcomer catch up with a leader.
        Map<String, Integer> moves = new HashMap<>();
        for (Carrier c : plugin.store().carriers()) {
            Score own = scores.get(c.id());
            if (own == null || c.simulatedUsers() <= 0) continue;
            double bar = own.attraction() * (1 + m().loyalty());
            List<Carrier> better = new ArrayList<>();
            double gap = 0;
            for (Carrier o : plugin.store().carriers()) {
                Score so = scores.get(o.id());
                if (o == c || so == null || so.attraction() <= bar) continue;
                better.add(o);
                gap += so.attraction() - bar;
            }
            if (better.isEmpty()) continue;
            double acc = switchAcc.getOrDefault(c.id(), 0.0) + c.simulatedUsers() * m().switchPerDay() * frac;
            int leaving = (int) Math.min(c.simulatedUsers(), Math.floor(acc));
            switchAcc.put(c.id(), acc - leaving);
            if (leaving <= 0) continue;
            int given = 0;
            for (int i = 0; i < better.size(); i++) {
                Carrier o = better.get(i);
                int n = i == better.size() - 1 ? leaving - given
                        : (int) Math.round(leaving * (scores.get(o.id()).attraction() - bar) / gap);
                n = Math.min(n, leaving - given);
                if (n <= 0) continue;
                moves.merge(o.id(), n, Integer::sum);
                moves.merge(c.id(), -n, Integer::sum);
                given += n;
                today.computeIfAbsent(o.id(), k -> new int[2])[0] += n;
                today.computeIfAbsent(c.id(), k -> new int[2])[1] += n;
            }
        }
        for (var e : moves.entrySet()) {
            Carrier c = plugin.store().carrier(e.getKey());
            if (c != null) c.simulatedUsers(c.simulatedUsers() + e.getValue());
        }

        // Shopping: carrier-less customers sign up with probability = the best carrier's attraction.
        List<Carrier> open = new ArrayList<>();
        double total = 0, best = 0;
        for (Carrier c : plugin.store().carriers()) {
            Score s = scores.get(c.id());
            if (s == null || s.attraction() <= 0) continue;
            open.add(c);
            total += s.attraction();
            best = Math.max(best, s.attraction());
        }
        int pool = unassigned();
        if (!open.isEmpty() && pool > 0) {
            shopAcc += pool * m().shopPerDay() * frac * Math.min(1, best);
            int buying = (int) Math.min(pool, Math.floor(shopAcc));
            shopAcc -= buying;
            int given = 0;
            for (Carrier c : open) {
                double share = buying * scores.get(c.id()).attraction() / total;
                double acc = joinAcc.getOrDefault(c.id(), 0.0) + share;
                int join = (int) Math.floor(acc);
                joinAcc.put(c.id(), acc - join);
                join = Math.min(join, buying - given);
                if (join > 0) {
                    c.simulatedUsers(c.simulatedUsers() + join);
                    given += join;
                    today.computeIfAbsent(c.id(), k -> new int[2])[0] += join;
                }
            }
        } else {
            shopAcc = 0;
        }
        // Tower screens show the rating: refresh them when it changes.
        for (Carrier c : plugin.store().carriers()) {
            long r = Math.round(stars(c) * 10);
            Long before = shownRating.put(c.id(), r);
            if (before != null && before != r) for (Tower t : plugin.store().towersOf(c.id())) plugin.towers().refresh(t);
        }
        if (++hourOfDay >= 24) {
            hourOfDay = 0;
            yesterday.clear();
            yesterday.putAll(today);
            today.clear();
        }
        plugin.store().markDirty();
    }

    // =============================================================== scores

    /** Recalculates every carrier's score. Cheap enough to run hourly and on demand. */
    public void recompute() {
        Map<String, Double> coverageAbs = new HashMap<>();
        Map<String, Double> avgPrice = new HashMap<>();
        double maxCov = 0;
        for (Carrier c : plugin.store().carriers()) {
            double cov = coverage(networkOf(c));
            coverageAbs.put(c.id(), cov);
            maxCov = Math.max(maxCov, cov);
            avgPrice.put(c.id(), averagePrice(c));
        }
        Map<String, Score> out = new LinkedHashMap<>();
        for (Carrier c : plugin.store().carriers()) {
            double price = avgPrice.get(c.id());
            double anchor = anchor(c, avgPrice);
            double ratio = price <= 0 ? 0 : price / anchor;
            double priceScore = Math.clamp((2 - ratio) / 1.5, 0, 1);
            double covRel = maxCov <= 0 ? 0 : coverageAbs.get(c.id()) / maxCov;
            double quality = quality(networkOf(c));
            double support = plugin.workforce().support(c); // customer care funding (3-day average)
            double wsum = m().wQuality() + m().wCoverage() + m().wSupport() + m().wPrice();
            double sat = wsum <= 0 ? 0 : 100 * (m().wQuality() * quality + m().wCoverage() * covRel
                    + m().wSupport() * support + m().wPrice() * priceScore) / wsum;
            boolean refused = ratio >= m().refuseRatio() || c.plans().isEmpty();
            double unhappy = 1 - sat / 100;
            double churn = m().baseChurn() + m().unhappyChurn() * unhappy * unhappy
                    + m().priceChurn() * Math.pow(Math.max(0, ratio - 1), 2);
            if (quality <= 0) churn += m().unhappyChurn(); // no service at all
            if (refused) churn = Math.max(churn, m().refuseChurn());
            double attraction = refused || quality <= 0 ? 0
                    : Math.pow(sat / 100, 2) * (0.25 + 0.75 * covRel) * Math.min(1, ratio <= 0 ? 1 : 1 / (ratio * ratio));
            out.put(c.id(), new Score(quality, covRel, support, priceScore, ratio, price, sat, Math.min(1, churn), attraction, refused));
        }
        scores = out;
        computedAt = System.currentTimeMillis();
    }

    /** The price customers compare against: the config's fair price blended with rivals' average prices. */
    private double anchor(Carrier c, Map<String, Double> prices) {
        List<Double> rivals = new ArrayList<>();
        for (var e : prices.entrySet()) {
            if (e.getKey().equals(c.id()) || e.getValue() <= 0) continue;
            Carrier o = plugin.store().carrier(e.getKey());
            if (o != null && !networkOf(o).isEmpty()) rivals.add(e.getValue());
        }
        double ref = m().referencePrice();
        if (rivals.isEmpty()) return ref;
        rivals.sort(Double::compare);
        double median = rivals.size() % 2 == 1 ? rivals.get(rivals.size() / 2)
                : (rivals.get(rivals.size() / 2 - 1) + rivals.get(rivals.size() / 2)) / 2;
        return (1 - m().rivalsWeight()) * ref + m().rivalsWeight() * median;
    }

    /** Average daily price over the carrier's personal plans (simulated customers pick plans evenly). Business plans are priced apart from this. */
    public double averagePrice(Carrier c) {
        if (c.plans().isEmpty()) return 0;
        double sum = 0;
        int n = 0;
        for (Plan p : c.plans().values()) {
            if (p.business()) continue;
            sum += p.periodHours() <= 0 ? 0 : plugin.sales().price(p) * 24.0 / p.periodHours();
            n++;
        }
        if (n == 0) { // only business plans: compare those
            for (Plan p : c.plans().values()) sum += p.periodHours() <= 0 ? 0 : plugin.sales().price(p) * 24.0 / p.periodHours();
            n = c.plans().size();
        }
        return sum / n;
    }

    /** The towers a carrier's customers use: its own, or its host's for an MVNO. */
    public List<Tower> networkOf(Carrier c) {
        String net = c.id();
        if (c.mvno()) {
            var deal = plugin.partners().mvnoHost(c);
            if (deal == null || plugin.partners().suspended(c)) return List.of();
            net = deal.host().id();
        }
        List<Tower> out = new ArrayList<>();
        for (Tower t : plugin.store().towers()) if (t.carrierId().equals(net)) out.add(t);
        return out;
    }

    private boolean working(Tower t) {
        return t.online() && plugin.billing().decayStage(t) < plugin.settings().silentStage() && !plugin.workforce().silent(t);
    }

    /**
     * 0..1 service score. Reliability: how close towers run to full speed (worn, unpaid, silent or
     * offline towers and understaffed engineering pull it down). Speed: the top speed towers can
     * give (best band × backhaul × reliability), on a log scale against the reference speed, so
     * newer generations, faster bands and better backhaul win customers.
     */
    private double quality(List<Tower> towers) {
        if (towers.isEmpty()) return 0;
        double rel = 0, speed = 0;
        double ref = Math.log1p(m().speedReference());
        for (Tower t : towers) {
            if (!working(t)) continue;
            double r = Math.min(plugin.billing().decaySpeed(t), plugin.workforce().wearSpeed(t)) * plugin.workforce().networkFactor(t);
            rel += r;
            double peak = 0;
            int dropped = Math.max(plugin.billing().decayDroppedBands(t), plugin.workforce().wearDroppedBands(t));
            Band[] all = Band.values();
            for (int i = all.length - 1; i >= 0; i--) {
                Band b = all[i];
                if (!t.enabledBands().contains(b) || !plugin.towers().available(t, b)) continue;
                if (dropped > 0) {
                    dropped--;
                    continue;
                }
                peak = plugin.settings().spec(b).maxMbps();
                break;
            }
            double mbps = peak * plugin.signal().backhaulMultiplier(t) * r;
            speed += Math.min(1, Math.log1p(mbps) / ref);
        }
        rel /= towers.size();
        speed /= towers.size();
        return (1 - m().speedWeight()) * rel + m().speedWeight() * speed;
    }

    /** Coverage results by a fingerprint of the towers that produced them; coverage only changes when towers do. */
    private final Map<Long, Double> coverageCache = new HashMap<>();

    /** Covered area in grid cells, each weighted by the best band reaching it. */
    private double coverage(List<Tower> towers) {
        // Fingerprint: the settings in use, and each working tower's place, size and band ranges.
        long key = 0x9E3779B97F4A7C15L ^ System.identityHashCode(m()) ^ ((long) System.identityHashCode(plugin.settings()) << 32);
        Band[] all = Band.values();
        for (Tower t : towers) {
            if (!working(t)) continue;
            key = mix(key, t.id());
            key = mix(key, t.worldId().getMostSignificantBits() ^ t.worldId().getLeastSignificantBits());
            key = mix(key, ((long) t.x() << 32) ^ (t.z() & 0xFFFFFFFFL));
            key = mix(key, t.small() ? 1 : 0);
            for (Band b : all) {
                if (!t.enabledBands().contains(b) || !plugin.towers().available(t, b)) continue;
                key = mix(key, b.ordinal());
                key = mix(key, Double.doubleToLongBits(plugin.towers().range(t, b)));
            }
        }
        Double cached = coverageCache.get(key);
        if (cached != null) return cached;
        double v = computeCoverage(towers);
        if (coverageCache.size() > 256) coverageCache.clear();
        coverageCache.put(key, v);
        return v;
    }

    private static long mix(long h, long v) {
        h ^= v + 0x9E3779B97F4A7C15L + (h << 6) + (h >>> 2);
        h *= 0xBF58476D1CE4E5B9L;
        return h ^ (h >>> 31);
    }

    private double computeCoverage(List<Tower> towers) {
        int cell = m().cellSize();
        Map<UUID, Map<Long, Double>> best = new HashMap<>();
        Band[] bands = Band.values();
        for (Tower t : towers) {
            if (!working(t)) continue;
            double weight = t.small() ? plugin.settings().smallCells.coverageWeight() : 1;
            double maxRange = 0;
            List<Band> on = new ArrayList<>();
            for (Band b : bands) {
                if (!t.enabledBands().contains(b) || !plugin.towers().available(t, b)) continue;
                on.add(b);
                maxRange = Math.max(maxRange, plugin.towers().range(t, b));
            }
            if (on.isEmpty()) continue;
            Map<Long, Double> cells = best.computeIfAbsent(t.worldId(), k -> new HashMap<>());
            int cx0 = Math.floorDiv((int) (t.x() - maxRange), cell), cx1 = Math.floorDiv((int) (t.x() + maxRange), cell);
            int cz0 = Math.floorDiv((int) (t.z() - maxRange), cell), cz1 = Math.floorDiv((int) (t.z() + maxRange), cell);
            for (int cx = cx0; cx <= cx1; cx++) {
                for (int cz = cz0; cz <= cz1; cz++) {
                    double dx = cx * cell + cell / 2.0 - t.x(), dz = cz * cell + cell / 2.0 - t.z();
                    double d = Math.sqrt(dx * dx + dz * dz);
                    if (d > maxRange) continue;
                    double w = 0;
                    for (Band b : on) {
                        if (d <= plugin.towers().range(t, b)) w = Math.max(w, m().bandWeights()[b.ordinal()] * weight);
                    }
                    if (w <= 0) continue;
                    long key = ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
                    cells.merge(key, w, Math::max);
                }
            }
        }
        double sum = 0;
        for (Map<Long, Double> cells : best.values()) for (double w : cells.values()) sum += w;
        return sum;
    }
}
