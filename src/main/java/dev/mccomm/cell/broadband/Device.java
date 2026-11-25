package dev.mccomm.cell.broadband;

import dev.mccomm.cell.model.BlockKey;

/** Something built on a wall sign: an ISP node, a home router or a computer. */
public sealed interface Device permits Node, Router, Computer {
    BlockKey key();

    /** "node", "router" or "computer". */
    String kind();
}
