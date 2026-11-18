# Operations and repairs

Towers wear down from the traffic they carry. To keep them running you need people to
repair them: **field technicians**, working out of **maintenance stations** you build
on the map. Tower wear and facilities are on the **Network** tab of `/carrier manage`, hiring on the **Staff** tab.

## Wear

Every tower has wear from 0 to 100%. It grows every minute:

| Source | Wear per day (default) |
|---|---|
| Just being on | 2 |
| Each subscriber on the tower (real or simulated) | 0.1 |
| Each GB of real mobile data carried | 0.02 |

A quiet tower wears about 2% a day. A tower with 130 subscribers wears about 15% a day.

!!! note "Simulated users count fully"
    For speed, 500 simulated users load a tower like one player. For wear they count
    one each: they pay full plan prices, so they cost full price to serve.

| Wear | Effect |
|---|---|
| Under 40% | None |
| 40% | Speed ×0.85 |
| 60% | Speed ×0.6 and the top band switches off |
| 80% | Speed ×0.4 and the top two bands switch off |
| 100% | **Silent**: no service until it's repaired |

The tower screen shows "Worn" or "⚒ Repair in progress". If the whole network goes
silent, simulated users stop paying.

## Repairs

At **30% wear** a repair job is queued. Then:

1. The **nearest maintenance station** within **2,000 blocks** (same dimension) that
   has a free technician sends one. Senior technicians go first.
2. Repair parts are paid at the start: `5 + 0.2 × wear` credits.
3. The technician travels at 150 blocks a minute, works on site, and the tower goes
   back to 0% wear. They then drive back before taking the next job.

On-site time is `20 + 0.5 × wear` minutes, shorter for senior technicians and with
spare parts at the station.

**Example:** a tower 900 blocks from the station, at 30% wear, with a regular
technician: 5 min to set out + 6 min there + 35 min of work. The tower is fixed after
46 minutes and the technician is free again 6 minutes later.

While the repair runs, a technician in a hard hat stands at the foot of the tower
(server owners can turn this off).

### Contractors

If no technician can take a job within **30 minutes** (no station in range, or
everyone busy), a **contractor** is booked. They arrive after **6 hours** and cost
about **3×** a technician's job, at least 60 credits. You can turn contractors off in
the Network tab; jobs then wait for your own staff.

Contractors mean you never *need* staff: a small network can run on contractors
alone. Stations pay off once you have many towers or a lot of traffic.

## Maintenance stations

A station is a building with:

- a **closed room**: walls, a floor and closed doors, up to 4,096 blocks of air;
- a **roof**: no sky visible inside;
- **beds**: one per technician;
- **barrels** (optional): every 4 make repairs 5% faster, up to 25%.

Put a wall sign inside, look at it and run:

```
/facility create station North-Depot
```

Rent is `10 + 2 per bed` credits a day. Beds are re-counted every 10 minutes while the
station is loaded, or straight away with `/facility check <name>`. If beds disappear,
the newest technicians there go idle (still paid, not working) until there's room.

Breaking the sign removes the station. Where you build matters: put stations near
your towers. On [squaremap](../admin/squaremap.md) each station shows its service area.

## Office staff

Customer support agents (call centres) and network engineers (network operations
centres) are hired the same way. How many you need depends on your subscribers; see
[The customer market](market.md#staff-for-subscribers).

## Technicians

Hire them on the **Staff** tab: click a role to see three new job seekers each day.

| Level | Wage (default) | Repair time |
|---|---|---|
| Junior | ~112 cr/day | ×1.33 |
| Regular | ~150 cr/day | ×1.0 |
| Senior | ~210 cr/day | ×0.74 |

- **Hiring** costs a recruitment fee of 3 days' wage. You need a free bed.
- **Firing** costs 7 days' wage as severance.
- **Payday** is daily: wages plus station rent, from the carrier's wallet.
- **If payday fails**, the amount is owed, unpaid staff stop working, and after
  3 unpaid days they quit. The owner can't withdraw money while wages are owed.
  Depositing pays what's owed and everyone goes back to work.

Wages, rent, parts and contractor fees are all spent. They leave the economy rather
than going to anyone.

## Which bills come first

When money is short, bills are paid in this order:

1. **Tower upkeep** (and an MVNO's share to its host).
2. **Wages and station rent.** Payday only goes through if the next upkeep stays
   covered; otherwise it's owed.
3. **Repairs.** Parts and contractors are only paid if upkeep, owed wages and the
   next payday stay covered. Otherwise the repair waits (you're told once).

So a repair bill can never be what makes your towers go silent.

## Bankruptcy

A carrier that can't pay its upkeep or its staff for **7 days in a row** is declared
**bankrupt**. It's closed like a [liquidation](../economy.md#liquidating-a-carrier): owed wages and
severance are paid first, then MVNO farewell money, and whatever is left goes to the
owner, minus the usual fee. Subscribers lose their SIMs, and the towers come down.

The owner is warned 2 days and 1 day before, and `/carrier manage` shows the countdown
on the wallet. Paying everything that's due stops the clock.

## In your company's numbers

- **Income** in `/carrier manage` subtracts wages and rent.
- **Valuation** counts wages and rent in net income and subtracts wages owed.
- **Liquidation** settles owed wages plus severance for every employee before paying out.
- **Selling** the company hands its staff, stations and jobs to the buyer.
