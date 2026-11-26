# Design spec: Workforce, facilities and network wear

Status: **partly superseded.** Wear and repair jobs (section 4) are built and stay. Employees, facilities, wages and phase 2 (satisfaction, quitting, poaching) are replaced by the virtual operations budget in [operations.md](operations.md); headquarters and mergers continue in [groups.md](groups.md). The market details live in docs/carriers/market.md. Every number is a starting value that goes in `config.yml`.
value that goes in `config.yml`; this spec is where they get tuned before code.

## 1. Why

Today a carrier's costs barely move as it grows. In the superflat test,
NovaMobile earned about 5,500 cr/day from 400 simulated users while its three
towers cost about 100 cr/day in upkeep. That is a margin of about 98%; real
telcos keep 30–40%.

The cause: revenue scales with **subscribers**, but costs scale only with
**towers**. This spec adds costs that scale with subscribers and traffic,
through things players build and manage:

- **Virtual employees** with wages, levels, satisfaction, and the freedom to quit
  or move to a rival.
- **Facilities**: buildings on the map that house staff. Where a maintenance
  station stands decides how fast towers are repaired.
- **Network wear**: towers wear down from the traffic they carry, and
  technicians repair them.

Out of scope here (separate specs, but designed to plug in): the customer market
with churn, ad campaigns, tower leasing, spectrum licences, corporate tax.

### Goals

1. A large, fully staffed carrier lands at roughly **55–60% margin** from this
   system alone, and 30–40% once licences, tax and churn arrive.
2. A **small carrier on a small server is not crushed**: it can run with no staff
   and no facilities, using contractors.
3. **No new way to create money.** Every new cost is a sink. Transfers between
   companies with the same owner pay the usual 5% fee.
4. Everything works in real time, survives restarts, and works with
   `/celladmin timewarp`.

### Load counting: one important rule

For congestion (speed), simulated users keep counting as 1/500 of a real user.
For **costs** (wear, staff requirements, transit), **every subscriber counts as
one, simulated or real**. Simulated users pay full plan prices, so they must
cost full price to serve. This is what stops the money printer.

## 2. Employees

### 2.1 Roles

| Role | Company | Facility | What one employee covers (regular level) |
|---|---|---|---|
| Field technician | Carrier, Manufacturer* | Maintenance station | Repair jobs, one at a time |
| Network engineer | Carrier | Network operations centre | 100 subscribers |
| Customer support | Carrier | Call centre | 60 subscribers |
| Sales & marketing | Carrier | Ad office | Runs ads (ads spec) |
| Researcher | Manufacturer | R&D lab | 40 research points/day |
| Factory worker | Manufacturer | Factory | 1 tower built/day |

\* Manufacturer technicians only matter once leasing with maintenance exists.

### 2.2 Levels

| Level | Productivity | Market wage multiplier | Promotion after |
|---|---|---|---|
| Junior | ×0.75 | ×0.75 | 14 days worked |
| Regular | ×1.00 | ×1.00 | 45 days worked |
| Senior | ×1.35 | ×1.40 | — |

A promoted employee's market wage rises. If the company doesn't raise their
pay, their pay score drops automatically (see 2.4). There is no separate
"ask for a raise" mechanic; the satisfaction model does it.

### 2.3 Wages

Market wage per day at regular level (config, `workforce.market-wage`):

| Role | cr/day |
|---|---|
| Field technician | 150 |
| Network engineer | 200 |
| Customer support | 100 |
| Sales & marketing | 120 |
| Researcher | 180 |
| Factory worker | 90 |

- The company sets each employee's wage (bulk "set everyone in this role to X%
  of market" in the HR screen).
- Payday is daily at the company's upkeep time, from the company balance.
- If payday can't be paid in full, nobody is paid that day (no partial pay), the
  wage is owed, and every employee takes the late-pay hit (2.4). Topping up pays
  what's owed at once, like upkeep today.
- An employee whose wage is owed for **1 day stops working** (counts as 0
  productivity) and **quits after 3**.

### 2.4 Satisfaction

Each employee has satisfaction 0–100. Once a day a **target** is calculated, and
satisfaction moves 25% of the way towards it (so changes feel gradual).

```
target = 50
       + pay        clamp(100 × (wage / market − 1), −40, +30)
       + workload   0 if load ≤ 100%, else −clamp(30 × (load − 1), 0, 30)
       + benefits   +8 with a company-paid phone plan
       + facility   +5 if their facility has ≥ 20% spare capacity
                    −10 if they have no facility (idle)
       + health     −25 per late payday, fading over 7 days
                    −10 while any of the company's towers is decaying
       + tenure     +1 per 7 days employed, max +10
```

`load` = the work required of their role divided by what the role's staff can
cover. For example, 400 subscribers with 3 regular support agents (180 covered)
is a load of 2.2.

Examples:
- Paid market wage, normal load, nothing else: target 50.
- Paid 120% of market, company phone plan, 8 weeks in: 50 + 20 + 8 + 8 = 86.
- Paid market wage but overworked at load 1.5 and one late payday: 50 − 15 − 25 = 10.

### 2.5 Quitting

- **Below 40:** each day, chance to hand in notice = (40 − satisfaction) × 1.5%.
  Notice lasts 3 days. The owner gets a notice in their phone and HUD.
  During notice the company can make a **counter-offer** (a raise). If the raise
  lifts their target to 50 or more, they stay.
- **Below 15:** each day, 20% chance they walk out with no notice.
- **Owed wages for 3 days:** they quit, no roll.
- People who quit enter the shared labour pool (2.6). Quitting pays no severance.

### 2.6 Labour pool and hiring

- The server has one **shared labour pool** that every company hires from. First
  come, first served.
- Pool size: `20 + 6 × number of companies`, refilled with 1 new candidate per
  company per day (config).
- Each candidate has a generated name, role, level and **asking wage**
  (market × 0.9 to 1.15). Hiring at below their asking wage starts them at lower
  satisfaction (the pay term applies from day one).
- **Recruitment fee:** 3 days of their wage, paid on hiring.
- **Firing:** 7 days of their wage as severance.
- **Company cap:** total staff is limited by facility capacity, and by
  `workforce.max-staff-per-company` (default 60).

Fees and severance stop hire-and-fire cycling to reroll the pool.

### 2.7 Moving to a rival (poaching)

**Phase 2 (passive):** someone who quit because of pay is listed in the pool
with "Left NovaMobile · wants 165 cr". Their level and experience come with them.
Anyone can hire them.

**Phase 4 (active):** a company can send an offer to another company's employee.

- They accept if `offer ≥ current wage × (1 + satisfaction / 200)`. A happy
  employee (satisfaction 80) needs a 40% raise; an unhappy one (20) needs 10%.
- The employer gets **24 hours to match**. Matching keeps them, and a matched
  offer also gives +10 satisfaction.
- **Non-compete:** a company can pay an extra 10% of an employee's wage per day
  to make them refuse all offers. It can be dropped at any time.
- Offers between two companies with the same owner aren't allowed (pointless,
  and it avoids edge cases).

### 2.8 Benefits: company-paid phone plans

Turned on per role in the HR screen; the company picks a carrier and plan.

- **On the company's own carrier:** costs the carrier its own cost of serving one
  subscriber (about 5 cr/day at default numbers, see 6), not zero and not the plan
  price. It counts as a subscriber for costs but not for revenue or valuation.
- **On another carrier:** the plan price is paid to that carrier as real revenue,
  and the staff count as that carrier's subscribers. If the two companies share
  an owner, the 5% transfer fee applies.

## 3. Facilities

### 3.1 Types

| Type | For | Capacity = | Location matters? |
|---|---|---|---|
| Maintenance station | Technicians | beds | **Yes**: dispatch distance |
| Call centre | Support | min(seats, lecterns) | No |
| Network operations centre | Engineers | min(seats, observers) | No |
| Ad office | Sales | min(seats, cartography tables) | Later: local ad bonus |
| R&D lab | Researchers | brewing stands + enchanting tables | No |
| Factory | Factory workers | smithing tables + blast furnaces | No |

Seats are stairs blocks. A bed counts once (not per half).

Stations also count **barrels**: every 4 barrels make repairs 5% faster, up to
25% (spare-parts storage).

### 3.2 Building one

1. Build a room, put a wall sign inside it.
2. Look at the sign and run `/facility create <type> <name>`. Works for owners,
   Managers and Engineers (5).
3. The plugin flood-fills the air from the sign's front:
   - The room must be **closed**: the flood fill may not escape, up to
     4,096 air blocks.
   - The room must be **roofed**: no air block may see the sky.
   - Furniture is counted if it touches the room's air.
4. The sign gets a small screen (same style as towers): type, name, company,
   staff/capacity.

The room is checked again **once a day** and with `/facility check <name>`. If
capacity has dropped, the newest staff above the cap go **idle**: still paid,
not working, −10 satisfaction until reassigned.

Breaking the sign removes the facility (staff go idle). Facilities are protected
the same way tower signs are.

### 3.3 Rent

Each facility costs `10 + 2 × capacity` cr/day, paid with upkeep. Capacity
counts what was built, not how many staff work there, so overbuilding costs.

### 3.4 On the map

- squaremap layer "Facilities" with an icon per facility type.
- Stations also draw their **service radius** (3.5), so uncovered towers are
  easy to spot.

## 4. Network wear and repairs

### 4.1 Wear

Each tower has **wear** from 0 to 100. Every billing tick (60 s) wear rises by:

```
per day = 2                       (idle)
        + 0.10 × subscribers served by this tower (simulated count fully)
        + 0.02 × GB carried by this tower that day
```

"Served by" uses the tower that each subscriber's simulated or real connection
is assigned to, the same spread that congestion already uses.

At default numbers a tower with 130 subscribers wears about 15 points a day; an
idle tower 2 points a day.

### 4.2 Effects

| Wear | Effect |
|---|---|
| 0–39 | None |
| 40–59 | Speed ×0.85 |
| 60–79 | Speed ×0.6, highest band off |
| 80–99 | Speed ×0.4, two highest bands off |
| 100 | Silent |

This multiplies with the existing unpaid-upkeep decay; the worse of the two band
cut-offs applies. The tower screen shows a wear bar, and "🔧 Repair in progress"
during a job.

### 4.3 Jobs and dispatch

- When wear reaches **30** (config `dispatch-at`), a repair job is queued.
- Every tick, each queued job looks for the **nearest station of the same company
  in the same dimension** that is within its **service radius (2,000 blocks)**
  and has an idle technician.
- The technician is busy for:

```
set-out 5 min
+ travel  = distance / 150 blocks per min, there and back
+ repair  = (20 + 0.5 × wear) min × level factor × spare-parts factor
```

  Level factor: junior 1.33, regular 1.0, senior 0.74.

- When the repair finishes, wear goes to 0.
- **Parts:** each repair costs `5 + 0.2 × wear` cr, charged at the start.

Example: a tower 900 blocks from the station at wear 30, regular technician, no
barrels: 5 + 12 + 35 = 52 minutes, parts 11 cr.

### 4.4 Contractors

If no station can take the job within 30 minutes (none in range, or all busy),
the job goes to a **contractor**:

- Starts after a **6-hour** delay, then repairs normally.
- Costs `3 × (parts + a regular technician's wage for the job's duration)`, at
  least 60 cr.
- A carrier can set "never use contractors" (the job waits for staff instead).

Contractors mean **no carrier needs staff or facilities to function**. Stations
are an upgrade, worth it once a carrier has enough towers and traffic.

### 4.5 Visible technicians (optional)

Server toggle `workforce.visible-technicians` (default on). While a repair runs,
a technician figure stands next to the tower base, named after the employee.
Uses the mannequin entity if Paper 26.x exposes it (to check), otherwise an
armor stand. It is not interactive and is removed when the job ends or the chunk
unloads.

## 5. Real-player roles

Today's real-player "employees" get full access to the carrier menu. They become
roles:

| Role | Can do |
|---|---|
| Owner | Everything |
| Manager | Everything except moving money, selling, liquidating, changing roles |
| Engineer | Towers (install, bands, backhaul) and facilities |
| Finance | Plans, prices, branding, partner deals, staff and wages; no towers |

- `/carrier role <company> <player> <role|remove>` and the same for manufacturers.
- **Migration:** existing employees become **Managers**, matching what they can do now.
- **Optional salary:** a role can carry a daily salary, paid like virtual staff
  but to the player's wallet. The owner can't have one. Salaries to anyone who
  co-owns another company with the owner pay the 5% fee.

## 6. What it costs: a worked example

NovaMobile, 400 subscribers on 3 towers, at default numbers:

| Cost | Calculation | cr/day |
|---|---|---|
| Support | 7 agents (400 / 60) × 100 | 700 |
| Engineers | 4 engineers (400 / 100) × 200 | 800 |
| Technicians | 1 technician × 150 | 150 |
| Repairs (parts) | ~3 jobs × ~13 | 40 |
| Transit | 400 × 1 GB × 1 cr/GB | 400 |
| Facilities | station (2 beds), call centre (8), NOC (4): 14 + 26 + 18 | 58 |
| Tower upkeep | existing | 100 |
| **Total** | | **~2,250** |

Revenue is about 5,500 cr/day, so the margin is about **59%** fully staffed,
before licences and tax. If it understaffs, it saves wages but staff get
overworked and quit, towers wear down, and (with the market spec) customers
leave. Per subscriber, serving costs about 5.6 cr/day against 13.75 cr of revenue.

Transit (`workforce.transit-cr-per-gb`, default 1) uses real data for real
players. Simulated users are assumed to use `simulation.gb-per-user-day`
(default 1).

### Small carriers

**Self-service allowance:** the first **25 subscribers** need no support or
engineers (`workforce.self-service-subscribers`). Staff requirements are
calculated on subscribers above that.

A carrier with 5 real players and 2 towers pays only upkeep, about 2 contractor
repairs a month (~60 cr each) and a little transit. That's comparable to today.

## 7. Manufacturers

- **Researchers** produce research points daily (40 per regular researcher). Buying
  points with credits stays available, at the current 1 point per credit. At
  market wage a researcher costs 180 cr for 40 points, so they're the expensive
  but hands-off way. **To decide:** whether researchers should be cheaper per
  point than buying, to make staff worth it.
- **Factory workers** limit how many towers can be built per day. With none, a
  manufacturer builds 1 tower a day (the owner doing it). Orders over capacity
  are queued and delivered as they're built, instead of instantly.
- The Generic manufacturer has no staff and no limits.

## 8. Loophole checklist

| Risk | Rule |
|---|---|
| New money from wages | Wages, fees, severance, rent and parts are all spent; nothing is paid to anyone |
| Paying yourself a salary to dodge the 5% fee | Owners can't have a salary; co-owners' salaries pay the fee |
| Company plans between same-owner companies | 5% fee |
| Own-carrier benefits for free | Charged at the cost of serving a subscriber |
| Rerolling the labour pool | 3-day recruitment fee, 7-day severance |
| Liquidating to escape wages owed | Owed wages and 7 days' severance come off the payout first |
| Selling a company | Staff, facilities, wages owed and non-competes move with it |
| Facility sign shuffling for capacity | Capacity is checked daily; rent is based on what was built |
| Contractors as a cheap trick | Always at least 3× the staff cost |
| Rounding | Fees and costs round up, payouts round down |

## 9. Timewarp and restarts

- Wear, jobs, paydays, satisfaction and quitting are processed in steps of
  10 minutes during a timewarp, so a 60-day test behaves like 60 real days.
- Jobs in progress are saved with their end time. On restart they finish (or
  have finished) at the right time.
- Random rolls (quitting, candidates) use a seeded random generator per company
  and day, so a timewarp is reproducible.

## 10. Storage

New file `workforce.yml`:

- employees: id, company, role, level, name, wage, satisfaction, experience,
  hired-at, facility, owed, notice-until, non-compete, benefits
- facilities: id, company, type, name, sign position, capacity, barrels, rent
- jobs: tower, station, technician, started, ends, wear
- labour pool: candidates
- tower wear stays in `towers.yml`

## 11. Screens and commands

- **Company menu → Staff:** headcount by role, load per role, wage bill,
  average satisfaction, notices pending; hire from the pool, set wages, fire,
  counter-offers, benefits.
- **Company menu → Facilities:** list with location, staff/capacity and rent.
- **Carrier menu → Towers:** each tower shows wear and its current job.
- `/facility create|remove|check|list`
- `/carrier role`, `/manufacturer role`
- HUD (owners): a line for jobs in progress or pending notices, when there are any.

## 12. Build phases

1. Facilities (station only), wear, technicians, dispatch, contractors.
2. Wages, satisfaction, quitting, labour pool, passive poaching.
3. Support, engineers and the other facilities, together with the customer
   market and churn (separate spec).
4. Active poaching, non-competes, real-player roles and salaries; then ads,
   leasing and licences.

Each phase is released on its own and the docs are updated with it.

## 13. Later additions

### Headquarters

- One per company, registered like any facility (`/facility create hq <name>`).
- Houses office staff (financial advisor, later managers). Capacity = seats + desks
  (lecterns).
- Its size sets the company's **total staff cap** (replacing the flat
  `max-staff-per-company`), so growing means building a bigger HQ.
- Shown on squaremap as the company's home.

### Financial advisor

- A virtual role, housed at HQ, paid like other staff.
- Once a day writes a report from the company's real numbers, in the company
  menu: margin, cost per subscriber, cash runway, plans priced far from rivals,
  towers that cost more than they earn, staffing that's under or over load.
- Concrete suggestions ("raise Standard by ~2 cr: you're 30% below the market").
  Juniors give rough advice; seniors are more precise and look further ahead.
- **Crisis mode:** when runway drops under a few days, a step-by-step plan:
  which costs to cut first, which towers lose the most money, what could be sold.
- It only advises; it never changes anything by itself. Needs the customer market
  (phase 3) to be meaningful, so it comes after it.

### Mergers and buyouts

- **Buyouts** exist already (selling a company to another player for a price).
  Staff, facilities, wages owed and jobs move with the company.
- **Mergers:** two carriers become one, with both owners' agreement. The survivor
  takes the other's towers, stock, staff, facilities and balance. Subscribers keep
  their plan terms (grandfathered) and get a new number on the survivor's prefix.
  Agreements between the two end; agreements with third parties carry over.
  Fees: the usual transfer fee on the absorbed balance.
- **Hostile takeovers** would need shares and shareholders. Out of scope for now.

## 14. Open questions

1. Is 2,000 blocks a good station service radius for your maps?
2. Should researchers be cheaper per point than buying points?
3. Self-service allowance: is 25 subscribers right?
4. Should the per-subscriber staff ratios (60 support, 100 engineers) be easier
   or harder? They set the final margin.
5. Visible technicians: on by default?
