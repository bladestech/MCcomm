# MCcomm

A Paper/Purpur 26.x plugin that adds a mobile network to Minecraft: carriers build cell towers, players buy phones, subscribe to plans, text, call, and chat over the "internet". Signal, bands, data and money all matter.

> **Status: 1.0.0 Alpha.** Feature-complete for a first test, but expect bugs and balance changes.

Requires Java 25 and Paper (or Purpur) 26.1+.

## Features

- **Cell towers on wall signs:** `/tower create <carrier> <name>` while looking at a wall sign (e.g. `LF1`). A screen exactly the size of the sign shows the carrier, model, bands and status.
- **8 bands:** 2G, 3G, 4G, 4G+, 5G Low, 5G Mid, 5G High and 5G mmWave. Each has its own range, speed and ping. You fall back to lower bands as you move away.
- **Signal:** distance, nearby walls, depth underground and weather affect signal. Taller towers get up to +50% range.
- **Tower GUI:** toggle bands, upgrade the generation (Gen 1-5 unlock bands) and install a locked backhaul block (Netherite to Copper) that sets capacity.
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
- **Signal sidebar HUD** with bars, band, tower and data left.

## Quick start

1. `/carrier create NovaMobile`, then `/carrier manage` to deposit ores into the carrier wallet.
2. Look at a wall sign and run `/tower create NovaMobile NM1`. Right-click the sign and install a backhaul block.
3. `/cell eshop` to buy a phone, `/cell wallet` to deposit ores, then subscribe in the Carrier store.

## Commands

| Command | |
|---|---|
| `/cell` | Open the phone. Shortcuts: `status`, `eshop`, `wallet`, `plan <carrier>`, `speedtest`, `number`, `contacts`, `messages`, `data <carrier\|auto>`, `hud` |
| `/text <number\|contact\|player> <msg>` | Send a text |
| `/call start <number\|contact\|player\|909>` · `accept` · `deny` · `end` | Calls |
| `/internet [msg]` | Toggle internet chat mode, or send one message |
| `/carrier manage\|create\|delete\|info\|list\|color\|employee` | Run a carrier |
| `/tower create <carrier> <name>` · `manage` · `info` · `rename` · `remove` · `list` | Towers |
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
