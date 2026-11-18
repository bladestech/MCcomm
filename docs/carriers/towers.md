# Towers

## Buying towers

Towers are bought as **models** from manufacturers in the **Tower shop** on the **Network** tab of **`/carrier manage`**.

- The server-run **Generic** manufacturer always sells standard full-band models of every generation. Player [manufacturers](../manufacturers.md) may sell cheaper or specialised ones.
- To buy: click a model for 1, right-click for 5, or shift-click to type any amount.
- The price comes out of the carrier wallet. Bought towers go into your **Tower stock**.

A model has a **generation** (1–5) and a set of **bands**. Two models with the same generation and bands perform identically, whoever made them.

## Installing

1. Build a mast. **Taller is better**: see [height bonus](#height-bonus).
2. Place a **wall sign** on it. Standing and hanging signs can't be towers.
3. Look at the sign and run:
   ```
   /tower install <name> [carrier]
   ```
   Pick a model from your stock. The name is 1–12 letters, numbers, `_` or `-`, unique on the server, like `LF1`. The carrier is only needed if you run more than one.
4. Right-click the sign and put a **backhaul block** in its slot. Until then the tower is **offline**.

Towers are **fixed models**: they can't be upgraded. To get a better tower, install a newer model.

## The tower screen

A screen exactly the size of the sign shows:

- an **accent stripe** in your carrier's colour;
- your **carrier name** and a **status badge**: **● LIVE**, **▲ DEGRADED** (upkeep unpaid), **■ SILENT** or **○ OFFLINE**;
- the **tower name**, model and generation;
- **band chips**: green means on, grey means off, dark means not in this model;
- a **footer**: a load meter, user count (and simulated users), backhaul and height bonus.

!!! tip "Text backwards?"
    If the screen text appears mirrored on your client, an admin can set `display.yaw-offset: 180`.

## The tower GUI

Right-click the sign (staff only; others see an info message), or use `/tower manage`.

- **Bands:** switch each band the model includes on or off. Fewer bands means fewer ways to connect, not lower upkeep.
- **Backhaul slot:** put in one block. **Once installed it's locked**: it can only be **discarded** (destroyed, click twice), never taken back. To change it, discard it and fit a new one.

| Backhaul | Capacity | Extra upkeep per day |
|---|---|---|
| Netherite block | 100% | 12 cr |
| Diamond block | 70% | 8 cr |
| Gold block | 45% | 5 cr |
| Iron block | 25% | 3 cr |
| Copper block | 10% | 1 cr |

Capacity multiplies the speed of every band. mmWave's 4 Gbps peak becomes 400 Mbps on copper.

## Height bonus

Taller towers reach further, on **every band**.

- Height is measured from the antenna (1.5 blocks above the sign) down to the typical ground level around the tower, sampled in two rings 12 and 24 blocks out. The mast itself and single buildings don't count; a hilltop does.
- The first **4 blocks** give nothing. Each block above that adds **+1% range**, up to **+50%** at 54 blocks.
- Height is re-measured automatically while the tower's area is loaded.

## Capacity and generations

Each band of a tower serves a number of users at full speed before slowing down, and newer generations handle more:

| Generation | Users per band at full speed |
|---|---|
| 1 | 4 |
| 2 | 6 |
| 3 | 9 |
| 4 | 13 |
| 5 | 18 |

Beyond that, the band's speed is split between its users. The generation also decides which bands a model can include; see [Signal reference](../signal.md#bands).

## Small cells

Besides full towers (**macro cells**), manufacturers can make **small cells**: cheap,
short-range cells for busy spots, towns and indoors. You install them the same way, on a
wall sign with `/tower install <name>`.

| | Macro cell | Small cell |
|---|---|---|
| Range | Band range + height bonus | 15% of the band's range, no height bonus |
| Walls near the cell | Normal loss | Half the loss |
| Capacity per band | By generation | ×2 |
| Bands | Any | 4G and 5G only (Gen 3+ models) |
| Upkeep | 4 + 4 per generation + backhaul | 1 + 1 per generation + backhaul |
| Wear and repairs | Normal | Half the wear per subscriber, half the repair time |

- **Phones switch automatically.** Near a small cell its signal is stronger, so phones
  use it and stop loading your macro; they move back as players walk away.
- **Wireless backhaul.** A small cell without a backhaul block links to your nearest
  working macro cell within 200 blocks, at 80% of that macro's backhaul, shared a little
  more with every small cell on the same macro. If the macro goes down, so does the
  small cell. Put a backhaul block in to make it independent.
- **Limits:** up to 8 small cells per macro cell you own, at least 8 blocks apart.
- **The market** counts small cells at half weight for coverage. They mainly help speed
  and capacity.

The tower screen shows `Small cell · via NV1` for a wirelessly linked one; the Network
tab shows small cells with an end rod icon.

## Removing and protecting towers

- **Breaking the sign**, or the block it hangs on, removes the tower. Only carrier staff and admins can break them.
- Explosions, pistons, fire and mobs can't.
- A removed tower's backhaul block is destroyed.
- `/tower remove` (looking at it) does the same.

## Commands

| Command | Does |
|---|---|
| `/tower install <name> [carrier]` | Install a tower from stock on the sign you look at (`create` is an alias) |
| `/tower manage` · `/tower info` | Open the GUI, or show info, for the sign you look at |
| `/tower rename <name>` | Rename it |
| `/tower remove` | Remove it |
| `/tower list [carrier]` | List towers |
| `/tower setgen <gen>` · `/tower tp <name>` | Admin only |
