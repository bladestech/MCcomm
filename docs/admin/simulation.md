# Simulated users

For roleplay and creative servers: make networks feel busy without real players. Requires **`mccomm.simulate`** (ops by default).

There are two modes (`simulation.mode` in the config):

- **`market`** (default): the server has a pool of simulated **customers** that carriers compete for. See [The customer market](../carriers/market.md).
- **`fixed`**: you set each carrier's simulated users by hand, and they never move.

## Using it

```
/cellsim
```

**Market mode:** the menu shows every carrier's customers and satisfaction, and the **market size**. Click it for +100, right-click for −100, or shift-click to type a number. When you first switch to market mode, the users carriers already have become their starting customers. Shrinking the market removes customers without a carrier first, then takes from carriers in proportion.

**Spread evenly:** the scaffolding button at the top gives every carrier the same number of simulated users. In market mode it shares out the whole pool (nobody is left without a carrier); in fixed mode it evens out the users carriers already have. Customers then move on as usual.

**Growth:** in market mode the pool itself grows by `market.growth.per-day` (off by default; `0.01` is 1% a day) up to `market.growth.max-size` (10,000; 0 = no limit). New customers arrive without a carrier and shop around. Growth doesn't depend on maintenance, staff or wear, so it keeps going with `workforce.enabled: false`. With `per-day: 0` the pool stays fixed.

**Fixed mode:** click a carrier to set its simulated users: −100, −10, −1, +1, +10, +100, **Set exactly**, or **Clear**. Changes show on the towers immediately.

## How they behave

- **Spread evenly** across the carrier's working towers. For an MVNO, that's its host's towers.
- Each is on **one of the carrier's plans at random**, and that plan's band cap applies. The spread is seeded, so numbers don't jump around between updates.
- **Counted separately** from real players: tower screens read like `3 users · 50 sim`, and carrier stats show them on their own.
- **Lighter load:** for congestion and the load meter, **500 simulated users count as one real player** (`simulation.users-per-real-user`).
- **Full cost:** for tower wear, staffing needs and data transit, each one counts fully, because each pays full price.

## Simulated revenue

A **server-wide toggle** in the `/cellsim` menu, **off by default**, that only admins can change.

When it's on, simulated users **pay their plan** like real subscribers:

- Each pays its plan's price spread over the plan's period, paid into the carrier wallet **once an hour**.
- MVNOs owe their host the revenue share on it.
- Users on a network with **no working towers** don't pay.
- Their data costs the carrier **transit** (1 GB per customer per day at 1 cr/GB by default), paid on payday.

!!! danger "This creates money"
    Simulated revenue adds credits from nothing. Staff, transit and the market keep a big carrier's margin far below the old ~98%, but money still comes in. Leave it off on servers where credits should stay scarce, and only give `mccomm.simulate` to people you trust while it's on.
