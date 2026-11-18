# Configuration

Everything lives in **`plugins/MCcomm/config.yml`**. Apply changes with **`/celladmin reload`**.

!!! note "New settings after updating"
    MCcomm doesn't overwrite your existing config. Settings added by a new version use their defaults until you copy those sections from the [bundled config](https://github.com/bladestech/MCcomm/blob/main/src/main/resources/config.yml) into yours.

The config file itself is commented. This page is an overview by section.

## Network

| Section | Controls |
|---|---|
| `bands` | Each band's name, range, peak speed, upload ratio, ping, wall loss and cap, text delay, minimum generation, GUI icon |
| `generations` | Model names per generation (used for the Generic manufacturer's towers) |
| `backhaul` | Which blocks can be backhaul, and their capacity multipliers |
| `signal` | Best and cutoff dBm, falloff, bar thresholds, wall scanning, weather and underground loss, antenna height, update rate |
| `congestion` | `capacity-per-generation`: users per band at full speed, by generation |
| `towers` | Install fee, name pattern, height bonus (free blocks, % per block, cap, sample radius) |
| `display` | The sign screen's panel block, full-bright, `yaw-offset` (set 180 if text is mirrored), wall-sign geometry |

See [Signal reference](../signal.md) for how these numbers are used.

## Phones and players

| Section | Controls |
|---|---|
| `phones` | Phone models: name, highest band, SIM slots, price, icon |
| `starter-phone` | A phone given free to new players (`""` = none). It's worth nothing on resale |
| `phone-resale-percent` | What selling a phone back pays (50%) |
| `subscriptions` | Most SIM slots any phone can have (2), inactive SIMs (4) |
| `phone` | Inbox and contacts size, chat prompt timeout |
| `messaging` | Text expiry, call ring time, call garbling at low bars |
| `internet` | Data per character sent and received, max message length |
| `data` | Throttle speed when out of data on a "slow down" plan |
| `hud` | Sidebar on by default, style (`sidebar` or `actionbar`), title |
| `speedtest` | Cooldown, jitter, minimum signal factor |
| `emergency-number` | Default `909` |
| `prefix` | Chat prefix (MiniMessage) |

## Economy

| Section | Controls |
|---|---|
| `currency` | Name, symbol, and the credit value of each ore |
| `billing` | Low-balance warning (6 h), overdue multiplier (4×), default plan for new carriers, min/max plan period |
| `upkeep` | Period, cost per tower, per generation and per backhaul; decay step and speeds |
| `carriers` | Carriers per player, towers per carrier, name pattern |
| `manufacturing` | Manufacturers per player, upkeep share (10%), research points per credit, manufacturing costs, research costs, Generic manufacturer on/off and margin |
| `companies` | Transfer fee (5%), liquidation fee (10%), MVNO farewell share, tower scrap and stock refund rates, paperweight resale, valuation income days |
| `partners` | Agreement notice period, deprioritized speed share, MVNO grace period |
| `workforce` | Tower wear rates and effects, office staff (support, engineers), transit, technician wages and levels, hiring and firing costs, station rent, service radius and travel speed, repair times and parts, contractors, visible technicians. See [Operations](../carriers/operations.md) |

## Extras

| Section | Controls |
|---|---|
| `simulation` | `users-per-real-user` (500) and `mode` (market or fixed). See [Simulated users](simulation.md) |
| `market` | Fair price, churn rates, shopping rate, satisfaction weights, coverage grid and band weights. See [The customer market](../carriers/market.md) |
| `squaremap` | Map integration. See [squaremap](squaremap.md) |

## Balancing tips

- **Cheaper towers or upkeep** make small carriers viable. Raise them if every player ends up running a carrier.
- **Ore values** decide how fast money comes in. Servers with iron farms may want iron worth less.
- **Phone prices** set how long players play before they reach 5G.
- **Band ranges** decide how many towers a carrier needs. Remember the height bonus adds up to 50%.
