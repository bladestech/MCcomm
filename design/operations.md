# Design spec: Virtual operations

Status: **draft, being built.** Replaces the facilities and individual employees of
[workforce.md](workforce.md) (stations, call centres, NOCs, hiring, wages, payday).
Tower wear and repair jobs from that spec stay.

## Why

Facilities on wall signs with room scans, and employees hired one by one, made
running a carrier fiddly, and most players only wanted the result: repairs, support
and a fast network. Operations become a **budget**: the costs still grow with the
network and its customers, and the owner chooses how much of them to fund.

## The budget

Every carrier and ISP has three parts. Each has a **full cost** (100%) per day that
grows with the business:

| Part | Carrier full cost per day | ISP full cost per day | Range |
|---|---|---|---|
| Field crews | 15 per macro cell + 6 per small cell + 0.25 per subscriber | 0.5 × each node's upkeep + 0.1 per customer | 0-150% |
| Customer care | 1.7 per subscriber beyond 25 | 1.2 per customer beyond 25 | 0-100% |
| Network ops | 2 per network subscriber beyond 25 (MVNOs included) | 1.5 per customer beyond 25 | 0-100% |

- Simulated users and households count as subscribers and customers.
- **Transit** (1 cr per GB of traffic, simulated users 1 GB a day each) is billed with
  operations as its own line. It has no funding level: traffic must be paid for.
- 5G Home ISPs pay customer care only; field and network work is the carrier's.
- The first 25 subscribers need no care or network ops staff, so a tiny carrier pays
  only for field crews (and nothing at 0%).

A worked example: NovaMobile with 3 macro cells, 1 small cell and 400 customers pays
at 100% about 45 + 6 + 100 = 151 for field crews, 638 for care and 750 for network
ops, about 1,540 a day plus transit. That is close to what its staffed version paid.

### Funding levels

The owner sets each part from 0% in steps of 10% (shift-click: 50%). The chosen
levels are the **target**.

- Field crews up to **150%**: above 100% repairs get faster still (diminishing).
- Care and network ops up to **100%**: there is nothing to gain beyond full service.

### Paying

- The bill is charged **every hour**: target × full cost ÷ 24, plus transit.
- **Pay what you can.** If the balance minus the next upkeep bill can't cover it,
  the company pays what it can. That hour's **effective funding** is paid ÷ full.
  Nothing is owed afterwards, and there is no wage debt or quitting.
- Order of bills stays: upkeep first, then operations, then repair parts.

### Effects use a 3-day average

Each part keeps a moving average of its effective funding over about 3 days
(exponential, updated hourly). Every effect reads the **average**, so raising
funding works in over a few days, and so does cutting it. This stops a company
funding only while the market is sampled.

| Part | Effect of average funding `f` |
|---|---|
| Field crews | Repair time = base ÷ speed, where speed = f up to 100% and 1 + 0.8 × (f − 1) above (1.5 → about 0.7×). Below 25%, no crews: repairs go to contractors after 30 minutes at 3× the cost, as today |
| Customer care | Carrier: the market's support score = f. ISP: a new care term in the rating |
| Network ops | Carrier: speed × max(0.6, f). ISP: transit capacity × max(0.6, f) |

Repairs no longer depend on distance: there are no stations and no travel. Repair
time is `(20 + 0.5 × wear) minutes ÷ f`, small cells half that. Parts cost what they
do today.

ISP node faults: an unpaid fault is fixed after `6 in-game hours ÷ (1 + 11 × f)`,
which is 6 in-game hours at 0%, half an in-game hour at 100% and about 20 in-game
minutes at 150%. The paid technician button stays.

## Groups

Companies in a [parent company](groups.md) pay 5% less for operations per extra
member, at most 15% less.

## Migration

- Existing staff become starting levels: each part starts at
  `min(1, staff coverage ÷ needed)`. Field crews start at 100% if there were any
  technicians, otherwise 0%.
- Wages owed are forgiven. No severance is due.
- Facility signs are cleared to blank text. `workforce.yml` keeps only wear, repair
  jobs and the operations state.
- The bankruptcy clock moves to the operations state. Bankruptcy comes only from
  unpaid upkeep now.

## Screens and commands

- The carrier **Staff** tab becomes **Operations**: one item per part with the target,
  the 3-day average, today's cost, the full cost and what it does. Click ±10%,
  shift-click ±50%. The ISP menu gets the same tab.
- `/facility` is removed. `/celladmin workforce` becomes `/celladmin operations`
  (the old name stays as an alias).
- squaremap: the stations layer goes.

## Loophole checks

| Risk | Rule |
|---|---|
| Fund only around market samples | Effects use the 3-day average |
| Run up debt, then liquidate | No debt: what can't be paid simply isn't funded |
| Free service at 0% | Contractors cost 3×, the support score and speed fall |
| Shift money to dodge the bill | Bills are charged hourly from the company's own balance |

## Open questions

1. Are the full costs right once the market is running (margins around 55-60%)?
2. Should funding above 100% exist for care and network ops (for example a small rating bonus)?
