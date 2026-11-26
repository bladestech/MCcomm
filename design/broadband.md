# Design spec: Home internet (ISPs, access technologies and home networks)

Status: **built** with the defaults below: ISPs, five access technologies, nodes, routers, computers, Wi-Fi, billing and the speed test, plus everything under "Added after the first slice" below (Wi-Fi phones and calling, 5G Home, grandfathering, ratings and a household market, Wi-Fi generations, data caps, last-mile costs, faults, transit and peering, bundles, a map layer, a browser and timewarp).
Mobile carriers, towers and phones keep their rules. The integrations (Wi-Fi data and calling, 5G Home, bundles) are listed per idea below.

## Why

Today the only way online is a phone on a tower. Players who build houses, towns and bases have
nothing to wire up, and there is no business for someone who wants to run *the cable company*
instead of *the phone company*. Home internet gives:

- **players** a reason to make a house a *connected* house: pick a provider, pay a bill, get a
  speed, and put a computer in the study;
- **ISP owners** a second kind of company with a different shape from a carrier: few, expensive
  nodes and fixed customers, instead of many towers and roaming phones;
- **the economy** another place credits go (install fees, monthly plans, node upkeep) and another
  thing to compete on (speed, price, coverage, reliability);
- **the world** a real difference between a village on DSL, a city on fibre and a cabin on a dish.

## The pieces

```
  ISP ──builds──▶ Node (wall sign) ◀──range── Router (wall sign, in a house) ··Wi-Fi·· Computer (wall sign)
   │                                              │                                      phones, players
   └─ sells Plans ─────── customer orders ────────┘
```

| Piece | What it is | Lives on |
|---|---|---|
| **ISP** | A company: owner, staff, balance, plans, nodes. Like a carrier, but for home internet | `/isp` |
| **Node** | The ISP's point of presence: DSL exchange, cable headend, fibre cabinet or satellite ground station | a wall sign |
| **Plan** | One technology, a price per period, a speed cap and an install fee | the ISP |
| **Router** | The home's modem. Holds the subscription. Its Wi-Fi reaches 24 blocks | a wall sign |
| **Computer** | Goes online through a router in range. Speed test, internet chat, Wi-Fi picker | a wall sign |

Everything physical is a **wall sign**, exactly like towers, so it works with the existing
protection (no breaking, blasting, pistons or editing by others) and the same install habit
(`look at a sign, run a command`).

## Access technologies

Each technology is a block of config, so servers can retune or rename them. A fifth, **5G Home**
(fixed wireless over a carrier's towers), is described under "Added after the first slice".

| | DSL | Cable | Fiber | Satellite |
|---|---|---|---|---|
| Reach from a node | 400 blocks | 800 | 1500 | **anywhere** (same world) |
| Peak speed | 40 Mbps | 400 | 10000 | 120 |
| Upload ÷ download | 0.1 | 0.1 | **1.0** | 0.2 |
| Base ping | 30 ms | 15 | 4 | **550** |
| Distance cost | linear: 10% left at the edge | gentle: 50% left | tiny: 90% left | none |
| Shared by (per node, tier 1) | 40 homes | **25** | 80 | **20** |
| Node cost / upkeep per day | 150 / 3 | 450 / 6 | 1500 / 12 | 900 / 10 |
| Install fee | 10 | 25 | 80 | 60 |
| Needs | nothing | nothing | nothing | **clear sky**; rain ×0.5, thunder ×0.2 |

How each one *feels*:

- **DSL**: cheap, and fine if you live next to the exchange. The farther out, the slower: at the
  edge you get a tenth of the speed. Good for villages.
- **Cable**: a big step up and still affordable, but the neighbourhood shares the node, so a busy
  evening slows everyone. Good for towns.
- **Fiber**: the best line there is. Distance barely matters, upload equals download, ping is a
  few milliseconds. A node costs ten times a DSL one, so it only pays in dense areas.
- **Satellite**: the only way to get online in the middle of nowhere, in any biome, with only
  a ground station *somewhere* in the world. Slow, high ping, and the dish (the router sign) must
  see the sky. A storm cuts the speed.

### Speed, in one formula

For a working line:

```
speed = peak × distanceFactor × share × weather × transit   (then capped by the plan)
distanceFactor = 1 − (distance ÷ range)^falloff × (1 − minFactor)
share          = min(1, nodeCapacity × tier ÷ customersOnNode)
```

- `share` is the congestion rule. It is the same idea as towers' *users before slowdown*.
- `transit` is the ISP's backbone share: 1 until its customers' demand beats the transit it bought.
- **5G Home** doesn't use this formula: its line is the best tower link at the home's door (see idea 2 below).
- A plan's **speed cap** is what the customer actually buys; it can't beat the line. A **used-up data allowance** drops it to a fair-use speed.
- Ping is the technology's base ping plus `distance / 100` ms.
- All of it is derived on the fly from config and live state; nothing about speed is stored.

## How a house gets online (the player's path)

1. **Place a wall sign** in the house and run `/broadband router install [name]` while looking at it.
   The sign becomes `[Router]`. (Up to 3 routers per player.)
2. **Right-click the router** → *Providers*. Every ISP's every plan is listed with a real quote *for
   this exact spot*: estimated speed, ping, which node would serve you and how far away, price and
   install fee. Plans that can't be had here are greyed out with the reason (*No DSL node within
   400 blocks*, *Needs a clear view of the sky*, *<ISP> is having an outage*).
3. **Order one.** A confirmation shows what is paid now (install fee + first period). The wallet is
   charged, the ISP is paid, the router shows *Installing…* and a short **technician visit**
   (20 s) later it is live. The sign shows the ISP, technology and speed.
4. **Place a second wall sign** and run `/broadband computer install [name]`. A computer within
   Wi-Fi range of the router is online. Right-click it for the computer screen:
   - connection status (provider, plan, down/up, ping);
   - **speed test**;
   - **internet chat** on/off (`/internet` over Wi-Fi: free, no phone needed);
   - **Wi-Fi picker** (automatic, or a specific router in range).
5. **Guests.** A router's owner can *share* its Wi-Fi. Anyone in range then gets the internet
   chat (and may use any computer next to it). Private by default.

Billing is per plan period (default 24 h, set by the ISP). A **missed payment suspends** the line
(no service, router sign shows `SUSPENDED`) and it is cut after 12 h; paying in time brings it back at once.
The customer is **not billed while the line is down** for the ISP's or the line's fault (outage,
node removed, dish blocked).

## How an ISP works (the owner's path)

1. `/isp create <name>`. The ISP starts with one **starter plan per technology**. (1 ISP per player.)
2. `/isp deposit <amount>`: credits come from the wallet. This pays for nodes, upgrades and upkeep.
3. Look at a wall sign: `/isp node install <tech> <name>`. The node is paid for from the ISP's
   balance. Nodes of one technology need 16 blocks between them.
4. Tune the plans in `/isp` → *Plans* (price, period, speed cap, install fee, name).
5. Watch `/isp` → *Network* (each node's load) and *Customers*.
6. Grow by **upgrading nodes** (tier 1→3: ×2, ×3 capacity, more upkeep) or building more of them.

Money in: install fees and plan renewals go straight to the ISP's balance. Money out: node cost
and upgrades (spent, not refunded), node **upkeep** every day. If the balance can't cover upkeep the
**whole network goes dark** until it can, and customers aren't billed meanwhile. An owner can
withdraw profit any time, but never the next upkeep bill. Staff (`/isp employee add`) can run nodes and plans;
only the owner withdraws or shuts it down.

## Data and code layout

- New package `dev.mccomm.cell.broadband`; **nothing in `DataStore` changed**. State is saved in its
  own `broadband.yml`, loaded at start, autosaved every minute and on shutdown.
- `BroadbandService` has all the rules (quotes, speed, billing tick, faults, market, signs, speed test).
  `BroadbandMenu` has every screen, in the shared menu frame; `ComputerBrowser` is the news, marketplace and mail.
  `BroadbandListener` clicks and protects signs.
- Small hooks in existing classes: `SignalService` (a `wifi` flag per SIM), `CallService`, `SmsService`, `SimService`
  (Wi-Fi calling), `SpeedTest`, `BillingService` (data offload, carrier-side bundle discount, timewarp),
  `InternetService`, `SquaremapHook` (the nodes layer).
- `BroadbandSettings` reads the `broadband:` section of `config.yml`; `/celladmin reload` applies it.
- Billing runs on its own 5-second tick, separate from the mobile billing tick.
- Commands: `/isp` (companies), `/broadband` (homes; aliases `/wifi`, `/bb`).
- `InternetService` (`/internet`) asks `BroadbandService.wifiLink(player)` first. On home Wi-Fi it
  sends and receives for free, delayed by the line's ping; otherwise the old mobile-data rules apply.

## Limits and loophole checks

| Risk | Rule |
|---|---|
| Free internet by stacking cheap nodes | Nodes cost credits and charge daily upkeep; same-technology nodes need 16 blocks between them; capacity is per node |
| Unlimited speed from one big plan | The line caps the speed (peak × distance × share × weather), not the plan |
| Everyone on one node | Share = capacity ÷ customers, so a full node slows every home on it |
| Bait and switch (cheap plan, then a price hike) | A router keeps its signed-up terms until the next renewal, and is told when new ones start |
| Faking ratings | Rating mixes *measured* delivery and a running average of uptime, not just the plan's headline speed |
| Bundle abuse | Discounts are capped (30%), need the other company's staff to accept, and need an actual plan on the other side |
| Wi-Fi leeching | Wall loss, range by router generation, and a guest limit on shared routers |
| Dodging upkeep | Unpaid upkeep darkens the whole network and pauses customer billing; no profit while dark |
| Cashing out the ISP | Withdraw can't touch the next upkeep bill; the owner only; ledgered |
| Draining the customer | Suspension, a 12 h grace and no overdue multiplier (unlike carriers); the wallet is never negative |
| Abandoned routers/nodes | Signs that disappear are cleaned up every tick; deleting an ISP cancels customers (no refunds) |
| Router abuse (placing 100) | 3 per player, 6 computers (admins exempt) |
| Satellite from underground | The dish must have no solid block above its sign; Nether and End never do |
| Griefing devices | Break, explosion, burn, piston and sign-edit protection; owners and an ISP's staff remove their own; admins remove any |

## Added after the first slice

Every idea from the first draft of this spec is now built. What each one is, and where it deliberately
stops short:

### 1. Phones prefer Wi-Fi
In reach of a working router (yours, or a shared one with a free guest slot), a phone treats the home line as its data:
- **Data**: `/internet`, the phone's speed test and any data use go through `BroadbandService.offload`, so **no mobile data is used** (a capped home plan's allowance is used instead).
- **Speed test**: the phone's `/cell speedtest` measures the Wi-Fi line (`/cell speedtest <slot>` still tests a SIM).
- **Wi-Fi calling**: with no tower in range, a SIM whose plan allows calls and texts can still call and text over Wi-Fi. `SimStatus` has a `wifi` flag; the HUD and the SIM screen show *Wi-Fi calling*. Call quality over Wi-Fi is full bars.
- Config: `broadband.phones-use-wifi`, `broadband.wifi.calling`.

### 2. Fixed wireless ("5G Home")
A carrier's home business: `/isp create <name> carrier <carrier>` makes an ISP that is **backed by a carrier** (carrier staff only, one per carrier, not for MVNOs).
- It has no nodes. A home's line is the best 5G link from any of the carrier's online towers, with an **indoor loss** (8 dB by default) applied, then shared per tower (12 customers at full speed), capped at 1000 Mbps and by the plan.
- It reuses the whole tower model: decay, wear, band range and tower silence all change the home line. Customers see a real quote for their exact spot.
- It only sells fixed-wireless plans, pays no node upkeep, and shows the carrier's towers on its *Towers* tab.

### 3. Grandfathered plans
A router keeps a **copy of the plan as it was** when the period started (price, speed cap, data, period). An ISP's edit applies at the customer's next renewal, when they are told the new terms. The router screen says when terms are about to change.

### 4. ISP market and ratings
- **Rating (1.0-5.0 stars)** = 35% speed (the plans' reach and the *measured* delivery of real lines against their caps), 25% reliability (a running average of how much of the network was up), 25% value (speed per credit against the technology's baseline) and 15% coverage (node reach, or tower count for 5G Home).
  It shows on the Providers list, in `/isp info`, on the ISP screen and on the map.
- **Simulated households** (`broadband.market.households`, off by default): each market step they pick ISPs in proportion to rating squared, limited by free capacity. They **load the nodes** (so real customers feel them) and **pay** the ISP their plans' average price.
  They don't churn one by one: each step re-divides the whole pool.

### 5. Wall loss and Wi-Fi generations
- Every solid block between a router and a device costs **1.5 blocks of range** (`wifi.wall-loss-blocks`).
- Routers upgrade from the router screen: Wi-Fi 4 to 7 (range x1 to x2, guests 2 to 16). Guests on a *shared* router are limited to its guest slots; the owner is never counted. Prices are spent.

### 6. Data caps
Plans can carry a data allowance. When it is used up the line drops to a fair-use speed (`broadband.throttle-mbps`, 1 Mbps) until the next renewal: it **throttles, never blocks**. Speed tests, browser pages and phones on Wi-Fi use the allowance.

### 7. The last mile
Wired technologies charge a **drop fee** per block from the node to the home (DSL 0.05, cable 0.15, fiber 0.4 credits per block), paid with the install fee and shown in the quote. Satellite and 5G Home have none. **Show the line** on the router screen draws the cable from the node in sparks for a few seconds.

### 8. Outages and weather
Nodes develop random **faults** (a per-technology chance per hour, multiplied in storms: DSL x4, cable x3, satellite x2). A faulted node serves nobody and its customers aren't billed. Customers re-bind to another healthy node of the ISP if one reaches them. The ISP can pay a **technician** from the node screen (or `/isp node repair <name>`), or a crew fixes it after 6 hours.
Faults run on the node's own timer and don't use the carrier workforce system: a deliberate simplification.

### 9. Transit and peering
Each non-carrier ISP buys **transit tiers** (5000 Mbps per tier, +30 credits per day for every tier above the first). The customers' plan speeds, times a 0.2 utilisation factor, are the demand. When demand beats capacity, **every customer's speed is scaled down** by `capacity / demand`.
**Peering** (`/isp peer offer|accept|end`) gives both ISPs +25% capacity per peer.

### 10. Bundles
`/isp bundle offer <carrier> <isp%> <carrier%>`; the carrier's staff accept with `/isp bundle accept <isp>`. Then customers who have a plan with the carrier pay *isp%* less for the ISP's home plans, and customers with the ISP's home internet pay *carrier%* less for their mobile plan renewals (capped by `bundle-max-discount`). The first mobile period is not discounted, only renewals.

### 11. squaremap
An **ISP nodes** layer: an icon per node in its ISP's colour (grey when down), a popup with rating, load and status, and a range ring per technology.

### 12. A real computer
The computer screen has a **Browser**: *News* (from config), a *Marketplace* (players post listings and contact sellers; 3 each, 7-day expiry) and *Mail* (read, reply, write to anyone; 30 messages). Pages take `ping + size / speed` to load and use data on capped plans, so a throttled line really is slow to browse.

### 13. Timewarp
`/celladmin timewarp` now also moves the broadband schedules (renewals, installs, suspensions, upkeep, faults, the market) and runs the broadband tick for each step.

## Later ideas

Not built, and not promised:

1. **Real churn in the household market**: households that switch ISP on price or an outage, each remembered.
2. **A real workforce for ISPs**: technicians sent from a depot, as for carriers' towers.
3. **Indoors check**: routers (except satellite) must have a roof above them.
4. **Node range by height**, like towers' height bonus.
5. **Refund of the unused part of a period** when a customer cancels.
6. **Items for sale in the marketplace** (today it only connects buyer and seller by mail).
7. **Carrier-side bundle discount on the first mobile period.**

## Decisions and open questions

Decided for the first slice:

- Wall signs for every device (consistent with towers, protection comes for free).
- One subscription per router; one ISP's several plans compete on the same list.
- Satellite uses a ground-station *node* too (so it is capacity-limited and has an owner), but has no range.
- `/internet` over Wi-Fi needs no phone: a computer is enough.
- Name collision: the home command is `/broadband` (aliases `/wifi`, `/bb`), not `/home`, which Essentials-style plugins own.

Still open:

1. Are the defaults (24-block Wi-Fi, 20 s install, 12 h grace, node prices) right once real players try them?
2. Should the router have to be **indoors** (a roof above it) except for satellite? Today anywhere works.
3. Should a node's range depend on the **height** it is built at, like towers' height bonus?
4. Should customers be able to **cancel with a refund** of the unused part of a period?
5. Should an ISP be allowed more than one node of the **same** technology within a short distance for capacity (today: 16 blocks apart)?
6. Is the household market's rating-squared split the right pull, and should it be on by default?
7. Are fault rates and the 6 h auto-repair right, or should faults need a depot and technicians like towers?
8. Everything above is untested on a live server: the defaults are a first guess and need playing.
