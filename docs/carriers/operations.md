# Operations and repairs

Towers wear down from the traffic they carry, and a network needs people to keep it
running. In MCcomm that work is **virtual**: there are no buildings to build or staff to
hire. Every carrier has an **operations budget** in three parts, and you choose how much
of each to fund. The bill grows with your network and your customers.

Everything is on the **Operations** tab of `/carrier manage`. [Internet providers](../isps.md#operations)
have the same tab.

!!! tip "Turning it off"
    Server owners can switch all of this off with `operations.enabled: false` in `config.yml`, or in game with `/celladmin operations off` (and `on` again; `workforce` still works as the old name). Add `reset` (`/celladmin operations off reset`) to also set every tower's wear back to 0 and cancel all repair jobs; `/celladmin operations reset` does just that at any time. While it's off there is no tower wear, no repair jobs and no operations bills or transit, service counts as fully funded, and the Operations tab is hidden. Budgets stay saved, and everything is **paused** (not backdated) until you turn it back on. Towers still pay their normal upkeep.

## The budget

| Part | Full (100%) cost per day | Funding | What it does |
|---|---|---|---|
| **Field crews** | 15 per macro tower, 6 per small cell, 0.25 per subscriber | 0–150% | Repair towers. More funding means faster repairs. Below 25%, every repair goes to a contractor |
| **Customer care** | 1.7 per subscriber beyond the first 25 | 0–100% | Your **support** score in [the customer market](market.md) is your care funding |
| **Network ops** | 2 per subscriber on your towers beyond the first 25, MVNOs you host included | 0–100% | Network speed × your funding, but never below ×0.6 |

The first 25 subscribers need no customer care or network ops: small carriers run
themselves. Subscribers are real players plus [simulated users](../admin/simulation.md),
and each simulated user counts fully because each pays full price.

An MVNO (a carrier without towers) pays customer care only. Its host's network ops
cover its customers.

**Example:** 4 macro towers, 2 small cells and 80 subscribers at 100% cost about
92 (field crews) + 94 (care) + 110 (network ops) = **296 credits a day**.

The tab shows each part's full cost, what you actually paid in the last hour, and the
**3-day average** that service follows.

- **Click** a part: +10%. **Right-click**: −10%. **Shift**: ±50%.
- The owner sets funding. Staff can see the tab but not change it.

### Paying

The bill is charged **every hour**, at the levels you chose.

- **The next upkeep is always kept back first.** The operations bill only uses what's
  above it, so operations can never make your towers go dark. In a
  [parent company](../groups.md), the treasury stands behind your upkeep, so you can
  spend your whole wallet first.
- **If that isn't enough, you pay what you can.** Nothing is owed: that hour simply
  counts as less funded. Paying 60% of a 100% budget counts as 60%.
- **Transit** for data your network carried (1 credit per GB, and 1 GB a day per
  simulated customer) is paid first, with the hourly bill. Unpaid real transit waits for
  the next bill.
- Your ledger shows one line a day: *Operations (last 24 h)*.

Service always follows a **3-day average** of what was actually paid, not today's
setting. Raising funding takes a few days to show, and so does cutting it. Funding only
for an hour before the market checks you doesn't work.

All operations money is spent: it leaves the economy rather than going to anyone.

### In a parent company

Members of a [parent company](../groups.md) pay 5% less for operations for every other
member, up to 15% less. If a member can't pay its bill, the parent's treasury lends the
difference.

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

At **30% wear** a repair job is queued.

**With field crews funded at 25% or more**, your crews take it straight away:

1. Repair parts are paid at the start: `5 + 0.2 × wear` credits.
2. The crew sets out (5 minutes) and works on site, and the tower goes back to 0% wear.

On-site time is `20 + 0.5 × wear` minutes at 100% funding (small cells are quicker).
Less funding is slower: at 50% it takes twice as long. Above 100% it gets faster, with
diminishing returns: 150% works 1.4× as fast.

**Example:** a tower at 30% wear needs 35 minutes of work. At 100% it's fixed after
40 minutes, at 50% after 75, and at 150% after 30.

### Contractors

With field crews below 25%, a job waits **30 minutes** and then a **contractor** is
booked. They arrive after **6 hours** and cost about **3×** parts plus labour, at least
60 credits. You can turn contractors off on the Operations tab; jobs then wait until
you fund your field crews.

Contractors mean you never *need* field crews: a small network can run on contractors
alone. Funding crews pays off once you have many towers or a lot of traffic.

## Which bills come first

When money is short, bills are paid in this order:

1. **Tower upkeep** (and an MVNO's share to its host).
2. **Operations**, from what's left above the next upkeep.
3. **Repairs.** Parts and contractors are only paid if the next upkeep and the next
   hour of operations stay covered. Otherwise the repair waits (you're told once).

So neither operations nor a repair bill can be what makes your towers go silent.

## Bankruptcy

A carrier that can't pay its **upkeep** for **7 days in a row** is declared
**bankrupt**. It's closed like a [liquidation](../economy.md#liquidating-a-carrier):
MVNO farewell money is paid first, and whatever is left goes to the owner, minus the
usual fee. Subscribers lose their SIMs, and the towers come down. Underfunded operations
never cause bankruptcy on their own.

The owner is warned 2 days and 1 day before, and `/carrier manage` shows the countdown
on the wallet. Paying the upkeep that's due stops the clock. Members of a
[parent company](../groups.md) get 7 more days.

## In your company's numbers

- **Income** in `/carrier manage` subtracts the operations budget at your current levels.
- **Valuation** counts operations in net income.
- **Selling** the company hands its budget levels and repair jobs to the buyer.

## Coming from stations and staff

Older versions had maintenance stations, call centres, network operations centres and
hired staff. On the first start of this version:

- Each carrier's budget starts where its staff left it: field crews at 100% if it had any
  technicians, and care and network ops at the share of subscribers its staff covered.
- Owed wages are forgiven. Nobody gets severance, and there's nothing to pay.
- Facility signs are cleared the next time their chunk loads. The rooms stay as they are.

The owner gets one message with the starting levels. Change them on the Operations tab.
