package dev.mccomm.cell.economy;

import dev.mccomm.cell.band.Band;
import org.bukkit.Material;

/** A phone sold in the eShop. {@code maxBand} is the highest band it can use; {@code sims} its active SIM slots. */
public record PhoneModel(String id, String name, Band maxBand, int sims, long price, Material icon) {
}
