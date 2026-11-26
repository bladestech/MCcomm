package dev.mccomm.cell.model;

import net.kyori.adventure.text.format.TextColor;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * A business a player owns: a carrier, a manufacturer or an ISP. Ids are only unique within one kind, so code that
 * mixes kinds (parent companies, operations) keys them by {@link #ref()}.
 */
public interface Company {
    /** The kinds of company. {@link #key} is used in saved references ("carrier:telecraft"). */
    enum Kind {
        CARRIER("carrier", "Carrier"), MANUFACTURER("mfr", "Manufacturer"), ISP("isp", "ISP");

        public final String key, display;

        Kind(String key, String display) {
            this.key = key;
            this.display = display;
        }

        public static Kind of(String s) {
            if (s == null) return null;
            String k = s.toLowerCase(Locale.ROOT);
            for (Kind t : values()) if (t.key.equals(k) || t.name().toLowerCase(Locale.ROOT).equals(k)) return t;
            return switch (k) {
                case "manufacturer", "factory" -> MANUFACTURER;
                case "telecom", "telco" -> CARRIER;
                default -> null;
            };
        }
    }

    /** A kind and id together: unique across every kind of company. */
    record Ref(Kind kind, String id) {
        public String key() {
            return kind.key + ":" + id;
        }

        public static Ref parse(String s) {
            if (s == null) return null;
            int i = s.indexOf(':');
            if (i <= 0) return null;
            Kind k = Kind.of(s.substring(0, i));
            return k == null ? null : new Ref(k, s.substring(i + 1));
        }

        @Override
        public String toString() {
            return key();
        }
    }

    Kind kind();

    String id();

    String name();

    UUID owner();

    void owner(UUID owner);

    Set<UUID> employees();

    List<TextColor> colors();

    long balance();

    void balance(long b);

    List<LedgerEntry> ledger();

    void log(long amount, String note);

    String styled();

    boolean isStaff(UUID player);

    /** The parent company (group) id, or null when the company is on its own. */
    String group();

    void group(String groupId);

    default Ref ref() {
        return new Ref(kind(), id());
    }
}
