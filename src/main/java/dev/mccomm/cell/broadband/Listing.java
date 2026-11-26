package dev.mccomm.cell.broadband;

import java.util.UUID;

/** A marketplace listing posted from a computer. */
public record Listing(int id, UUID seller, String title, long price, long time) {
}
