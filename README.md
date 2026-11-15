# MCcomm

A Paper/Purpur 26.x plugin that adds a mobile network to Minecraft. Carriers build cell towers, manufacturers design them, and players buy phones, subscribe to plans, text, call, and chat over the "internet". Signal, bands, data and money all matter.

> **Status: 1.4.0 Alpha.** Feature-complete for testing, but expect bugs and balance changes.

**📖 Documentation: <https://bladestech.github.io/MCcomm/>**

Requires Java 25 and Paper (or Purpur) 26.1+. Download the jar from [Releases](https://github.com/bladestech/MCcomm/releases).

## Highlights

- **Cell towers on wall signs**, each with a live screen. There are 8 bands (2G to 5G mmWave) with real signal: distance, walls, depth, weather and tower height.
- **Phones and a phone GUI** (`/cell`): messages, contacts, dialer, speedtest, SIM manager, wallet and eShop. Every subscription gets a phone number.
- **Texts, calls and `/internet`**, a second chat that uses mobile data.
- **Carriers**: postpaid, financed-phone and prepaid plans, data allowances, upkeep and decay, roaming agreements, MVNOs and branding.
- **Manufacturers**: research generations and bands, design tower models, set margins and sell to carriers.
- **Economy**: credits from ores. Company valuations, sales and liquidation.
- **Extras**: signal sidebar, simulated users for roleplay, and [squaremap](https://modrinth.com/plugin/squaremap) support.

## Quick start

1. `/carrier create NovaMobile`, then `/carrier manage` to fund it and buy a tower in the Tower shop.
2. Look at a wall sign on a tall mast: `/tower install NM1`. Right-click the sign and add a backhaul block.
3. `/cell eshop` to buy a phone, `/cell wallet` to deposit ores, then subscribe in the Carrier store.

See [Getting started](https://bladestech.github.io/MCcomm/getting-started/) for the full walkthrough.

## Building

```bash
./gradlew build      # build/libs/MCcomm-<version>.jar
./gradlew runServer  # local Paper test server
```

The docs are in [`docs/`](docs) and published automatically to GitHub Pages.

## License

Copyright 2026 bladestech

Licensed under the [Apache License, Version 2.0](LICENSE).
