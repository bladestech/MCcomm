# Upkeep and decay

Every tower costs **upkeep**, paid automatically from the carrier wallet every **24 real hours**. The schedule keeps running while you're offline.

## What a tower costs

```
upkeep per day = 4 + 4 × (generation − 1) + backhaul extra
```

| Backhaul | Extra |
|---|---|
| Netherite block | 12 |
| Diamond block | 8 |
| Gold block | 5 |
| Iron block | 3 |
| Copper block | 1 |

Examples: a Gen 5 tower on Netherite costs **32 cr/day**; a Gen 2 on Iron costs **11 cr/day**.

**10%** of each tower's upkeep goes to the [manufacturer](../manufacturers.md) that built it (if it's a player manufacturer). The rest leaves the economy.

MVNOs have no towers, but their **revenue share** owed to their host is settled on the same schedule; see [Roaming and MVNOs](partners.md#mvnos).

## When you can't pay

If the wallet can't cover the upkeep when it's due, you're notified and your towers start **decaying**, one stage every **6 hours**:

| Stage | Effect |
|---|---|
| 1 | Speed drops to 60% |
| 2 | Speed 40%, the highest band shuts off |
| 3 | Speed 25%, the two highest bands shut off |
| 4 | **Silent**: no service at all |

Tower screens show **▲ DEGRADED** or **■ SILENT**, and so does the map.

## Repairing

**Deposit enough to cover the upkeep** (or move money in from your wallet). The overdue upkeep is paid at once and every tower is repaired immediately.

!!! danger "Death spiral"
    A silent network earns nothing from simulated users and gets no new subscribers, so a dead carrier stays dead until someone puts money in.
