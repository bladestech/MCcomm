# Design spec: Small cells

Status: **draft for review.** Not built.

## Why

Today every tower is a macro cell: tall, long range, one per area. Real networks add
**small cells**: low-power radios on walls, lamp posts and ceilings that carry a busy
spot's traffic and fill gaps indoors. In MCcomm they give:

- carriers a cheap way to add **capacity and speed** where players gather (spawn,
  towns, malls, mines), without stretching macro towers;
- players **indoor and underground** coverage, where macro signal fades through walls;
- manufacturers **a second product line** to research and sell;
- the market a reason to build them: since service now counts speed, hotspots with
  small cells raise a carrier's service score.

Existing towers become **macro cells**. Nothing about them changes.

## What a small cell is

| | Macro cell (today) | Small cell |
|---|---|---|
| Anchor | Wall sign | Wall sign (same install flow) |
| Range | Band range × height bonus | **15%** of the band's range, no height bonus |
| Walls | Normal wall loss | **Half** the wall loss within its range (low, close, indoors) |
| Capacity per band | By generation (4–18 users) | **×2** for its generation |
| Bands | Any researched | Gen 3+ bands only (4G, 4G+, 5G); no 2G/3G |
| Backhaul | A backhaul block | A backhaul block, **or wireless** (see below) |
| Upkeep | 4 + 4/gen + backhaul | **1 + 1/gen** + backhaul (or wireless share) |
| Wear | From traffic | From traffic, ×0.5 per subscriber |
| Screen | Full tower screen | A compact screen: name, carrier, bands, load |

Ranges at default settings: 4G ≈ 150 blocks, 5G Mid ≈ 80, 5G mmWave ≈ 14.

### Picking a cell

No new rule: phones already take the highest band, then the strongest signal. Near a
small cell its band is usually the strongest, so phones move onto it automatically and
off the macro (offloading), and back to the macro when they walk away.

### Wireless backhaul

A small cell without a backhaul block can link wirelessly to one of the carrier's
**macro cells within 200 blocks** (nearest working one). It then:

- uses that macro's backhaul multiplier ×0.8;
- adds its load to the macro's backhaul (more small cells on one macro slow them all);
- goes down when the macro does.

That keeps small cells cheap to place but ties them to the macro network.

## Manufacturers

- A new research item: **Small cell form factor** (default 300 points, needs Gen 3).
- Models get a **form factor** setting: macro or small cell. Small-cell models cost
  about **40%** of a macro with the same bands to make (config).
- Generic sells a small cell per generation from Gen 3 at its usual margin.
- The usual upkeep share applies.

## Market and staff

- **Coverage:** small cells count towards coverage with band weight ×0.5. Their area is
  tiny, so they can't be spammed for coverage.
- **Service:** they raise the speed part of the service score through their bands and
  capacity, and they reduce macro congestion for real players.
- **Repairs:** technicians repair them like macros, but faster (base repair time ×0.5).
- **Engineers:** no change; they cover subscribers, not cells.

## Limits

- `towers.small-cells-per-macro` (default 8): a carrier can have at most this many small
  cells per macro cell it owns. This stops a carrier skipping macros entirely.
- Small cells can't be placed within 8 blocks of another of the same carrier's small cells.

## Loophole checks

| Risk | Rule |
|---|---|
| Coverage farming with cheap cells | Coverage weight ×0.5 and the tiny range; the per-macro cap |
| Free backhaul | Wireless backhaul shares and slows the macro's backhaul, and stops with it |
| Self-dealing through manufacturer margins | Same rules as macros: liquidation and valuation use manufacturing cost |
| Upkeep dodging | Small cells pay their own (small) upkeep and wear like towers |

## Screens and commands

- `/tower install <name>` works the same; the stock picker shows the form factor.
- Network tab: macro and small cells listed together, with a small-cell icon (end rod).
- squaremap: a small icon and its short-range circle, in its own toggleable layer.
- Docs: a "Small cells" section under Towers and Manufacturers.

## Open questions

1. Are the default ranges (15% of macro) and capacity (×2) right?
2. Should small cells need research, or be available to everyone from the start?
3. Wireless backhaul: keep it, or always require a block?
4. A different anchor block for small cells (for example an end rod with a sign), or
   keep wall signs for both?
