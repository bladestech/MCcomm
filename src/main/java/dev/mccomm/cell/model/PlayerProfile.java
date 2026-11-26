package dev.mccomm.cell.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A player's phone: up to N active (dual SIM) and M saved but inactive carriers, one phone
 * number per subscription, contacts, an SMS inbox and settings.
 */
public final class PlayerProfile {
    private final UUID player;
    private final List<String> active = new ArrayList<>();
    private final List<String> inactive = new ArrayList<>();
    /** carrierId -> phone number (digits only). */
    private final Map<String, String> numbers = new LinkedHashMap<>();
    /** phone number (digits only) -> contact name, in insertion order. */
    private final Map<String, String> contacts = new LinkedHashMap<>();
    /** Newest first. */
    private final List<SmsMessage> inbox = new ArrayList<>();
    private Boolean hud; // null = server default
    private boolean business; // a business account: sees and takes business plans instead of personal ones
    private boolean dnd;
    private String dataSim; // carrierId, null = automatic (first SIM with service)
    private String phone; // phone model id in use, null = no phone
    private final Set<String> ownedPhones = new LinkedHashSet<>();
    private long balance;
    /** carrierId -> credits owed (missed payments, already multiplied). */
    private final Map<String, Long> overdue = new LinkedHashMap<>();
    /** carrierId -> plan subscription. */
    private final Map<String, Subscription> subs = new LinkedHashMap<>();
    /** Messages to show on next join (billing warnings that happened while offline). */
    private final List<String> notices = new ArrayList<>();
    private final List<LedgerEntry> ledger = new ArrayList<>();
    /** phoneId -> carrierId it is locked to while being financed. */
    private final Map<String, String> phoneLocks = new LinkedHashMap<>();
    /** phoneId -> carrierId that blacklisted it for a defaulted financing. */
    private final Map<String, String> blacklist = new LinkedHashMap<>();
    /** Phones received for free (starter phone, admin gifts): worth nothing when sold or traded in. */
    private final Set<String> giftedPhones = new LinkedHashSet<>();
    private boolean starterGiven;

    public PlayerProfile(UUID player) {
        this.player = player;
    }

    public UUID player() { return player; }
    public List<String> active() { return active; }
    public List<String> inactive() { return inactive; }
    public Map<String, String> numbers() { return numbers; }
    public Map<String, String> contacts() { return contacts; }
    public List<SmsMessage> inbox() { return inbox; }
    public Boolean hud() { return hud; }
    public void hud(Boolean hud) { this.hud = hud; }
    public boolean business() { return business; }
    public void business(boolean b) { this.business = b; }
    public boolean dnd() { return dnd; }
    public void dnd(boolean dnd) { this.dnd = dnd; }
    public String phone() { return phone; }
    public void phone(String id) { this.phone = id; }
    public Set<String> ownedPhones() { return ownedPhones; }
    public Map<String, Long> overdue() { return overdue; }
    public Map<String, String> phoneLocks() { return phoneLocks; }
    public Map<String, String> blacklist() { return blacklist; }
    public Set<String> giftedPhones() { return giftedPhones; }
    public boolean starterGiven() { return starterGiven; }
    public void starterGiven(boolean v) { this.starterGiven = v; }
    public Map<String, Subscription> subs() { return subs; }
    public List<String> notices() { return notices; }

    public long balance() { return balance; }
    public void balance(long b) { this.balance = b; }
    public List<LedgerEntry> ledger() { return ledger; }

    /** Adds a ledger line (newest first, last 30 kept). */
    public void log(long amount, String note) {
        ledger.addFirst(new LedgerEntry(System.currentTimeMillis(), amount, note));
        while (ledger.size() > 30) ledger.removeLast();
    }

    public long totalOverdue() {
        long t = 0;
        for (long v : overdue.values()) t += v;
        return t;
    }

    public Subscription sub(String carrierId) { return subs.get(carrierId); }

    public String dataSim() { return dataSim; }
    public void dataSim(String carrierId) { this.dataSim = carrierId; }

    public boolean has(String carrierId) {
        return active.contains(carrierId) || inactive.contains(carrierId);
    }

    public void remove(String carrierId) {
        active.remove(carrierId);
        inactive.remove(carrierId);
        numbers.remove(carrierId);
        if (carrierId.equals(dataSim)) dataSim = null;
        subs.remove(carrierId);
    }

    public String number(String carrierId) {
        return numbers.get(carrierId);
    }

    public int unread() {
        int n = 0;
        for (SmsMessage m : inbox) if (!m.read()) n++;
        return n;
    }

    public boolean isEmpty() {
        return active.isEmpty() && inactive.isEmpty() && hud == null && !business && !dnd && dataSim == null && contacts.isEmpty() && inbox.isEmpty()
                && phone == null && ownedPhones.isEmpty() && balance == 0 && overdue.isEmpty() && notices.isEmpty() && ledger.isEmpty() && phoneLocks.isEmpty() && blacklist.isEmpty() && giftedPhones.isEmpty() && !starterGiven;
    }
}
