package dev.mccomm.cell.broadband;

import dev.mccomm.cell.model.BlockKey;

/** Something built on a wall sign: an ISP node, a home router, a computer or a Wi-Fi extender. */
public sealed interface Device permits Node, Router, Computer, Extender {
    BlockKey key();

    /** "node", "router", "computer" or "extender". */
    String kind();
}
