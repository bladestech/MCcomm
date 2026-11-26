package dev.mccomm.cell.broadband;

/**
 * A deal between an ISP and a mobile carrier: customers of the carrier get {@code ispDiscount} percent off
 * the ISP's home plans, and the ISP's customers get {@code carrierDiscount} percent off the carrier's plan
 * renewals. The ISP offers; the carrier's staff accept.
 */
public final class Bundle {
    private final String carrierId;
    private int ispDiscount;
    private int carrierDiscount;
    private boolean accepted;

    public Bundle(String carrierId, int ispDiscount, int carrierDiscount, boolean accepted) {
        this.carrierId = carrierId;
        this.ispDiscount = ispDiscount;
        this.carrierDiscount = carrierDiscount;
        this.accepted = accepted;
    }

    public String carrierId() { return carrierId; }
    public int ispDiscount() { return ispDiscount; }
    public void ispDiscount(int v) { this.ispDiscount = v; }
    public int carrierDiscount() { return carrierDiscount; }
    public void carrierDiscount(int v) { this.carrierDiscount = v; }
    public boolean accepted() { return accepted; }
    public void accepted(boolean a) { this.accepted = a; }
}
