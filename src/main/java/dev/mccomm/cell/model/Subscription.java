package dev.mccomm.cell.model;

/**
 * A player's plan on one carrier. {@code planId} null = no plan (cancelled or never chosen): no service.
 * For prepaid plans {@code nextBill} is the expiry time. {@code dataLeftMB} is ignored on unlimited plans.
 */
public final class Subscription {
    private String planId;
    private long nextBill;
    private boolean warned;
    private double dataLeftMB;
    private String financePhone;   // phone being repaid, null = none
    private long financeRemaining; // credits still owed for it
    private long financeInstallment;
    private transient boolean expiredNotice; // prepaid: "expired" message already sent

    public Subscription(String planId, long nextBill) {
        this.planId = planId;
        this.nextBill = nextBill;
    }

    public String planId() { return planId; }
    public void planId(String id) { this.planId = id; }
    public long nextBill() { return nextBill; }
    public void nextBill(long t) { this.nextBill = t; }
    public boolean warned() { return warned; }
    public void warned(boolean w) { this.warned = w; }
    public double dataLeftMB() { return dataLeftMB; }
    public void dataLeftMB(double mb) { this.dataLeftMB = mb; }
    public String financePhone() { return financePhone; }
    public void financePhone(String id) { this.financePhone = id; }
    public long financeRemaining() { return financeRemaining; }
    public void financeRemaining(long v) { this.financeRemaining = v; }
    public long financeInstallment() { return financeInstallment; }
    public void financeInstallment(long v) { this.financeInstallment = v; }

    public boolean expiredNotice() { return expiredNotice; }
    public void expiredNotice(boolean v) { this.expiredNotice = v; }

    public boolean financing() {
        return financePhone != null && financeRemaining > 0;
    }
}
