package dev.mccomm.cell.model;

/** A received text. Numbers are digits only. {@code read} is mutable so the inbox can mark it. */
public final class SmsMessage {
    private final String fromNumber;
    private final String fromName;
    private final String toNumber;
    private final String text;
    private final long time;
    private boolean read;

    public SmsMessage(String fromNumber, String fromName, String toNumber, String text, long time, boolean read) {
        this.fromNumber = fromNumber;
        this.fromName = fromName;
        this.toNumber = toNumber;
        this.text = text;
        this.time = time;
        this.read = read;
    }

    public String fromNumber() { return fromNumber; }
    public String fromName() { return fromName; }
    public String toNumber() { return toNumber; }
    public String text() { return text; }
    public long time() { return time; }
    public boolean read() { return read; }
    public void read(boolean read) { this.read = read; }
}
