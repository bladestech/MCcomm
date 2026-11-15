package dev.mccomm.cell.model;

/** One wallet transaction. Positive amount = money in, negative = money out. */
public record LedgerEntry(long time, long amount, String note) {
}
