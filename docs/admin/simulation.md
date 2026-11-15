# Simulated users

For roleplay and creative servers: make networks feel busy without real players. Requires **`mccomm.simulate`** (ops by default).

## Using it

```
/cellsim
```

The menu lists every carrier. Click one to set its simulated users: −100, −10, −1, +1, +10, +100, **Set exactly**, or **Clear**. Changes show on the towers immediately.

## How they behave

- **Spread evenly** across the carrier's working towers. For an MVNO, that's its host's towers.
- Each is on **one of the carrier's plans at random**, and that plan's band cap applies. The spread is seeded, so numbers don't jump around between updates.
- **Counted separately** from real players: tower screens read like `3 users · 50 sim`, and carrier stats show simulated subscribers on their own.
- **Lighter load:** for congestion and the load meter, **500 simulated users count as one real player** (`simulation.users-per-real-user`).

## Simulated revenue

A **server-wide toggle** in the `/cellsim` menu, **off by default**, that only admins can change.

When it's on, simulated users **pay their plan** like real subscribers:

- Each pays its plan's price spread over the plan's period, paid into the carrier wallet **once an hour**.
- MVNOs owe their host the revenue share on it.
- Users on a network with **no working towers** don't pay.

!!! danger "This creates money"
    Simulated revenue adds credits from nothing. In a test, 400 simulated users earned a carrier about 5,600 cr/day against 73 cr/day of upkeep. Leave it off on servers where credits should stay scarce, and only give `mccomm.simulate` to people you trust while it's on.
