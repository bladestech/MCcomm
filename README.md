# MCcomm

A Paper/Purpur 26.x plugin that adds a mobile network to Minecraft: carriers build cell towers, players buy phones, subscribe to plans, text, call, and chat over the "internet". Signal, bands, data and money all matter.

> **Status: 1.4.0 Alpha.** Feature-complete for a first test, but expect bugs and balance changes.

Requires Java 25 and Paper (or Purpur) 26.1+.

## Features

- **Cell towers on wall signs:** buy tower models in the Tower shop, then `/tower install <name>` while looking at a wall sign (e.g. `LF1`). A layered screen exactly the size of the sign shows the carrier, status, tower, model, band chips and load.
- **Tower manufacturers:** `/manufacturer create <name>`. Fund research with ores to unlock generations and bands, design models with your own names and any set of bands, set your margin (with a live profit preview), and sell to carriers. Manufacturers earn 10% of upkeep on every tower they built. A server-run Generic manufacturer always sells standard towers.
- **8 bands:** 2G, 3G, 4G, 4G+, 5G Low, 5G Mid, 5G High and 5G mmWave. Each has its own range, speed and ping. You fall back to lower bands as you move away.
- **Signal:** distance, nearby walls, depth underground and weather affect signal. Taller towers get up to +50% range.
- **Tower GUI:** toggle the bands the model includes, and install a locked backhaul block (Netherite to Copper) that sets capacity. Towers are fixed models: install a newer model to upgrade.
- **Phones:** Connect (3G), Explorer (4G+) and Pilot (all bands), each with a `+` dual-SIM version, bought in the eShop.
- **Phone GUI (`/cell`):**
  - Messages, contacts, dialer and speedtest.
  - SIM cards: 2 active and 4 inactive, with a choosable data SIM.
  - Carrier store, wallet, nearby towers and settings.
- **Phone numbers:** every subscription gets a number (e.g. `297-7402`). `/text` and `/call` accept a number, a contact or a player.
- **Economy (credits from deposited ores):**
  - **Plans:** postpaid, financed (the carrier buys your phone and you repay it) or prepaid.
  - **Plan options:** data allowance or unlimited, band and speed caps, and texts/calls included or not.
  - **Missed payments:** the plan is cancelled and the payment becomes ×4 overdue. Financed phones get blacklisted.
  - **Phones:** sell a phone back to the eShop for 50%. Carriers can run trade-in programs that take a phone's value off a phone loan and lower each instalment.
  - **Tower upkeep:** carriers pay for their towers. Unpaid towers decay until they go silent.
- **`/internet`:** a second chat channel that costs mobile data per character. It shows your carrier and phone model.
- **Companies:** move money between yourself and your carrier or manufacturer (5% fee). Sending credits between players is free (`/cell pay`). Each company has a valuation, and can be sold to another player or liquidated: towers are scrapped, subscribers lose their SIMs, hosted MVNOs get farewell money, phones still being paid off become paperweights, and the owner gets the rest minus a fee.
- **Roaming agreements:** a host lets another carrier's subscribers use its towers where their own network has no coverage. The home carrier pays the host per GB. Plans decide whether roaming is allowed and set an optional per-GB surcharge.
- **MVNOs:** `/carrier create <name> mvno` creates a virtual carrier with no towers. It runs on a host network for a share of its revenue, and its subscribers are suspended if it doesn't pay. Hosts can cap bands and deprioritize partner users.
- **Simulated users (roleplay):** `/cellsim` (permission `mccomm.simulate`) injects simulated users per carrier. They're spread evenly across its towers, each on a random plan, and they load towers and cause congestion like real users. A server-wide toggle decides whether they also pay their plans (off by default, since it creates money).
- **Tower capacity by generation:** each band serves more users at full speed on newer towers (4 at Gen 1 up to 18 at Gen 5).
- **Branding:** carrier and plan names can have a solid colour or a 2-4 colour gradient. Pick from presets or type your own on the Branding page in `/carrier manage`, or use `/carrier color`.
- **squaremap support** (optional): a "Cell towers" layer with a mast icon per tower in its carrier's colour (grey when down), with popups showing model, bands, status, users and height. There's also a coverage layer per carrier showing each tower's reach. Configured under `squaremap:`.
- **Signal sidebar HUD** with each SIM's carrier and plan in their styles, plus bars, band, tower, data left, your phone and the in-game time.

## Quick start

1. `/carrier create NovaMobile`, then `/carrier manage` to deposit ores into the carrier wallet.
2. In `/carrier manage` open the Tower shop and buy a tower model, then look at a wall sign and run `/tower install NM1`. Right-click the sign and install a backhaul block.
3. `/cell eshop` to buy a phone, `/cell wallet` to deposit ores, then subscribe in the Carrier store.

## Commands

| Command | |
|---|---|
| `/cell` | Open the phone. Shortcuts: `status`, `eshop`, `wallet`, `plan <carrier>`, `speedtest`, `number`, `contacts`, `messages`, `data <carrier\|auto>`, `hud` |
| `/text <number\|contact\|player> <msg>` | Send a text |
| `/call start <number\|contact\|player\|909>` · `accept` · `deny` · `end` | Calls |
| `/internet [msg]` | Toggle internet chat mode, or send one message |
| `/carrier manage\|create\|delete\|info\|list\|color\|employee` | Run a carrier |
| `/tower install <name> [carrier]` · `manage` · `info` · `rename` · `remove` · `list` | Towers |
| `/manufacturer create\|manage\|list\|color\|employee\|delete` | Run a tower manufacturer |
| `/tower setgen <n>` · `/tower tp <name>` | Admin |
| `/celladmin reload\|save\|credits\|carriercredits\|givephone\|billing` | Admin |

## Permissions

- `mccomm.use` (default: everyone)
- `mccomm.carrier.create` (default: everyone)
- `mccomm.admin` (default: op)

## Building

```bash
./gradlew build
```

The jar is written to `build/libs/`. `./gradlew runServer` starts a local Paper test server.

Everything (bands, ranges, phones, prices, ore values, upkeep, decay, data costs) is configurable in `config.yml`.

## License

Copyright 2026 bladestech

Licensed under the [Apache License, Version 2.0](LICENSE). You may use, modify and distribute this project under its terms.
