package dev.mccomm.cell.broadband;

import java.util.UUID;

/** One e-mail in a player's computer inbox. */
public final class Mail {
    private final UUID from;
    private final String text;
    private final long time;
    private boolean read;

    public Mail(UUID from, String text, long time, boolean read) {
        this.from = from;
        this.text = text;
        this.time = time;
        this.read = read;
    }

    public UUID from() { return from; }
    public String text() { return text; }
    public long time() { return time; }
    public boolean read() { return read; }
    public void read(boolean r) { this.read = r; }
}
