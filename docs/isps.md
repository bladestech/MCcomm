# Running an ISP

An **internet service provider** sells home internet. Where a [carrier](carriers/index.md) builds towers for phones, an ISP builds **nodes** for homes: a few expensive sites and many fixed customers.

## Start one

```
/isp create <name>
/isp deposit <amount>
```

A new ISP comes with a starter plan for each technology. It has no nodes yet, so none can be ordered. Money for nodes, upgrades and upkeep comes from the ISP's **balance**, so deposit first. The default is 1 ISP per player.

## Build nodes

Look at a wall sign and run:

```
/isp node install <dsl|cable|fiber|satellite> <name>
```

The node is paid from the ISP's balance. Names are unique within the ISP.

| | DSL | Cable | Fiber | Satellite |
|---|---|---|---|---|
| Homes can be within | 400 blocks | 800 | 1500 | anywhere |
| Peak speed | 40 Mbps | 400 | 10,000 | 120 |
| Customers at full speed | 40 | 25 | 80 | 20 |
| Node cost | 150 cr | 450 | 1,500 | 900 |
| Upkeep per day | 3 cr | 6 | 12 | 10 |

!!! note
    These are the defaults; the server owner can change them. Nodes of the same technology must be 16 blocks apart.

Right-click a node (or open `/isp` → **Network**) to see its load, **upgrade its tier** (more customers at full speed, more upkeep), rename or take it down.

A satellite node is a *ground station*: homes anywhere in the world can use it, but its capacity is small and every home needs a clear sky.

## Plans

`/isp` → **Plans**, or:

```
/isp plan add [business] <tech> <price> <mbps> <name...>
```

| Setting | Does |
|---|---|
| Price and billing period | What customers pay each period (default 24 h) |
| Speed cap | The most the plan delivers. The line can still be slower (distance, sharing, weather) |
| Install fee | Paid once at sign-up (the customer also pays a per-block cabling fee for wired lines) |
| Data allowance | Optional. When it is used up the customer's line slows to a fair-use speed until renewal |

Price your plans against what the line can really deliver: customers see a **quote** for their address before they order. Edits to a plan reach existing customers **at their next renewal**, not before. Deleting a plan cancels its subscribers (no refunds).

**Residential and business plans.** Every plan is for either a **home** or a **business**. Customers only see plans that match their router's site type (see below), so you can sell both side by side: for example a cheap fiber home plan and a pricey business one with a high speed cap and no data limit. Business customers get **priority on a busy node**: where home lines slow down because the node is crowded, a business line keeps its speed longer (×2 priority by default). Make a business plan by right-clicking a technology when you create a plan in `/isp` → Plans (left-click makes a home plan), with `/isp plan add business <tech> <price> <mbps> <name...>`, or with the **For** button in the plan editor (not possible while the plan has subscribers). New business plans start at 3× the starter price.

**Ordering plans.** Plans show in your list's order. In `/isp` → **Plans**, **right-click** a plan to move it later or **shift-left-click** to move it earlier. Or open a plan and use **Move earlier** / **Move later** (shift-click for the top or bottom). The order is saved.

**Removing plans.** A new ISP starts with a plan for every technology, so delete the ones you don't sell: shift-right-click a plan in `/isp` → **Plans**, use **Delete plan** in the plan editor, or:

```
/isp plan delete <name...>
/isp plan delete tech <dsl|cable|fiber|satellite>
/isp plan list
```

Subscribers of a deleted plan are cancelled (no refunds).

## Rating

Every ISP has a **1-5 star rating** from the speed its customers actually get, how much of the time the network is up, value for money and coverage. Customers see it in the Providers list. If the server enables the household market, simulated households pick providers by rating, load your nodes, and pay your plans.

## Faults

Nodes break down now and then (more in storms; copper and coax suffer most). A faulted node serves no one: customers aren't billed and re-connect to another healthy node of yours if one reaches them. Send a technician from the node screen or `/isp node repair <name>` (costs the ISP), or a crew arrives after 6 hours.

## Transit and peering

Your customers share the ISP's **backbone** (transit): 5,000 Mbps per tier. If what customers pull at once (plan speeds, about 20%) goes over it, **every customer slows down** in proportion. Buy more in the ISP menu (`/isp transit`); every tier above the first adds daily upkeep.

**Peering** shares backbone with another ISP (+25% for each peer): `/isp peer offer <isp>`, then they `/isp peer accept <isp>`; `/isp peer end <isp>` stops it.

## Carrier ISPs: 5G Home

A mobile carrier's owner or staff can sell home internet over its towers:

```
/isp create <name> carrier <carrier>
```

There are no nodes to build. A home's line is the best 5G link from the carrier's towers at its door, so towers, bands and upkeep matter for home customers too. It only sells **5G Home** plans and pays no node upkeep.

## Bundles

```
/isp bundle offer <carrier> <isp discount %> <carrier discount %>
/isp bundle accept <isp>          (carrier staff)
/isp bundle end <carrier>
```

Customers of the carrier get the first discount on your home plans; your home customers get the second off their mobile plan renewals. Both are capped by the server (30% by default).

## Money

| In | Out |
|---|---|
| Install fees | Node cost (spent) and tier upgrades (spent) |
| Plan renewals | **Node upkeep every day** |

- **Upkeep:** if the balance can't pay it, the **whole network goes dark** (and customers aren't billed) until you deposit. Keep a buffer.
- **Withdraw:** `/isp withdraw <amount>` pays profit to your wallet, but never the next upkeep bill. Owner only.
- **Ledger:** `/isp` → Overview lists the latest transactions.

## Staff and style

```
/isp employee add|remove <isp> <player>
/isp color <isp> <colors...>
```

Staff can build and take down nodes and edit plans. Only the owner withdraws or shuts the ISP down (`/isp delete <name> confirm`, which cancels every customer).

## Commands

| Command | Description |
|---|---|
| `/isp` | Open your ISP menu |
| `/isp create <name> [carrier <carrier>]` | Start an ISP, or a carrier's 5G Home business |
| `/isp manage [isp]` | The ISP menu |
| `/isp node install <tech> <name> [isp]` | Build a node on the sign you look at |
| `/isp node repair <name> [isp]` | Pay a technician to fix a fault |
| `/isp transit [isp]` | Buy a tier of backbone |
| `/isp peer offer\|accept\|end <isp> [your isp]` | Peering |
| `/isp bundle offer\|accept\|end\|list` | Bundles with carriers |
| `/isp plan add [business] <tech> <price> <mbps> <name...>` | Add a plan (home by default) |
| `/isp plan delete <name...>` · `delete tech <tech>` · `list` | Delete plans, or list them |
| `/isp deposit\|withdraw <amount> [isp]` | Move credits |
| `/isp info [isp]` · `/isp list` | Details · all ISPs |
| `/isp employee add\|remove <isp> <player>` | Staff |
| `/isp color <isp> <colors...>` | Name style |
| `/isp delete <isp> confirm` | Shut it down |

For the full design, see [`design/broadband.md`](https://github.com/bladestech/MCcomm/blob/main/design/broadband.md).
