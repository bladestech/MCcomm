package dev.mccomm.cell.model;

import dev.mccomm.cell.band.Band;

/**
 * A carrier's plan. Mutable so the owner can edit it from the carrier GUI.
 * <ul>
 *   <li>POSTPAID: charged {@code price} every period from the wallet.</li>
 *   <li>FINANCED: like postpaid, plus the carrier buys {@code financePhone} up front and the
 *       subscriber repays it (plus interest, minus down payment) over {@code installments} periods.</li>
 *   <li>PREPAID: no automatic charges. A top-up costs {@code price}, lasts {@code periodHours} and adds the data.</li>
 * </ul>
 */
public final class Plan {
    public enum Type { POSTPAID, FINANCED, PREPAID }

    public enum OutOfData { THROTTLE, BLOCK, OVERAGE }

    private final String id;
    private String name;
    private Type type = Type.POSTPAID;
    private long price;
    private int periodHours;
    private Band maxBand;
    private double speedCapMbps;
    private boolean texts = true;
    private boolean calls = true;
    private long dataMB = -1; // -1 = unlimited
    private OutOfData outOfData = OutOfData.THROTTLE;
    private long overagePerGb = 10;
    private long packMB;      // add-on data pack size, 0 = none offered
    private long packPrice;
    private String financePhone;
    private int installments = 6;
    private double interestPercent = 10;
    private long downPayment;
    private boolean roaming = true;
    private long roamingPerGb;  // surcharge to the subscriber per GB while roaming, 0 = free
    private java.util.List<net.kyori.adventure.text.format.TextColor> colors = new java.util.ArrayList<>(); // empty = carrier style

    public Plan(String id, String name, long price, int periodHours) {
        this.id = id;
        this.name = name;
        this.price = price;
        this.periodHours = periodHours;
    }

    public String id() { return id; }
    public String name() { return name; }
    public void name(String n) { this.name = n; }
    public Type type() { return type; }
    public void type(Type t) { this.type = t; }
    public long price() { return price; }
    public void price(long p) { this.price = p; }
    public int periodHours() { return periodHours; }
    public void periodHours(int h) { this.periodHours = h; }
    public Band maxBand() { return maxBand; }
    public void maxBand(Band b) { this.maxBand = b; }
    public double speedCapMbps() { return speedCapMbps; }
    public void speedCapMbps(double c) { this.speedCapMbps = c; }
    public boolean texts() { return texts; }
    public void texts(boolean t) { this.texts = t; }
    public boolean calls() { return calls; }
    public void calls(boolean c) { this.calls = c; }
    public long dataMB() { return dataMB; }
    public void dataMB(long mb) { this.dataMB = mb; }
    public boolean unlimited() { return dataMB < 0; }
    public OutOfData outOfData() { return outOfData; }
    public void outOfData(OutOfData o) { this.outOfData = o; }
    public long overagePerGb() { return overagePerGb; }
    public void overagePerGb(long v) { this.overagePerGb = v; }
    public long packMB() { return packMB; }
    public void packMB(long v) { this.packMB = v; }
    public long packPrice() { return packPrice; }
    public void packPrice(long v) { this.packPrice = v; }
    public String financePhone() { return financePhone; }
    public void financePhone(String id) { this.financePhone = id; }
    public int installments() { return installments; }
    public void installments(int n) { this.installments = n; }
    public double interestPercent() { return interestPercent; }
    public void interestPercent(double p) { this.interestPercent = p; }
    public long downPayment() { return downPayment; }
    public void downPayment(long v) { this.downPayment = v; }

    public java.util.List<net.kyori.adventure.text.format.TextColor> colors() { return colors; }
    public void colors(java.util.List<net.kyori.adventure.text.format.TextColor> c) { this.colors = new java.util.ArrayList<>(c); }

    /** The plan's name as MiniMessage: its own style, or the carrier's if it has none. */
    public String styled(Carrier carrier) {
        return dev.mccomm.cell.util.Style.mm(colors.isEmpty() && carrier != null ? carrier.colors() : colors, name);
    }

    public boolean roaming() { return roaming; }
    public void roaming(boolean v) { this.roaming = v; }
    public long roamingPerGb() { return roamingPerGb; }
    public void roamingPerGb(long v) { this.roamingPerGb = v; }

    /** A full copy (same id), used to keep a subscriber's old terms until renewal. */
    public Plan copy() {
        Plan p = new Plan(id, name, price, periodHours);
        p.type = type;
        p.maxBand = maxBand;
        p.speedCapMbps = speedCapMbps;
        p.texts = texts;
        p.calls = calls;
        p.dataMB = dataMB;
        p.outOfData = outOfData;
        p.overagePerGb = overagePerGb;
        p.packMB = packMB;
        p.packPrice = packPrice;
        p.financePhone = financePhone;
        p.installments = installments;
        p.interestPercent = interestPercent;
        p.downPayment = downPayment;
        p.roaming = roaming;
        p.roamingPerGb = roamingPerGb;
        p.colors = new java.util.ArrayList<>(colors);
        return p;
    }

    public boolean prepaid() { return type == Type.PREPAID; }
    public boolean financed() { return type == Type.FINANCED; }

    public long periodMillis() {
        return periodHours * 3_600_000L;
    }

    /** What the subscriber repays for the phone in total (price + interest - down payment). */
    public long financeTotal(long phonePrice) {
        return Math.max(0, Math.round(phonePrice * (1 + interestPercent / 100.0)) - downPayment);
    }

    public long installment(long phonePrice) {
        return installments <= 0 ? financeTotal(phonePrice) : (financeTotal(phonePrice) + installments - 1) / installments;
    }
}
