# MCcomm

**A mobile network for Minecraft.** Carriers build cell towers, manufacturers design them, and players buy phones, subscribe to plans, text, call and chat over the "internet". Signal, bands, data and money all matter.

MCcomm is a plugin for **Paper and Purpur 26.1+** (Java 25). It's open source under the [Apache License 2.0](https://github.com/bladestech/MCcomm/blob/main/LICENSE).

!!! warning "Alpha"
    MCcomm is in alpha. It's feature-complete enough to play with, but expect bugs and balance changes. Back up `plugins/MCcomm/` before updating.

## What's in it

<div class="grid cards" markdown>

- **Cell towers on wall signs.** A layered screen on the sign shows the carrier, status, model, bands and load. There are 8 bands, 2G to 5G mmWave, each with its own range and speed.
- **Real signal.** Distance, walls around you, depth underground, weather and tower height all count. You drop to lower bands as you move away.
- **Phones and a phone GUI.** Six phone models with different band support and SIM slots. `/cell` opens messages, contacts, a dialer, speedtest, SIM manager, wallet, eShop and more.
- **Numbers, texts, calls and `/internet`.** Every subscription gets a phone number. `/internet` is a second chat that uses mobile data.
- **Carriers.** Plans (postpaid, financed phones, prepaid), data allowances, tower upkeep and decay, roaming agreements, MVNOs and branding.
- **Manufacturers.** Research generations and bands, design tower models, set your margin and sell to carriers.
- **An economy.** Credits come from deposited ores. Companies can be valued, sold or liquidated.
- **Extras.** A signal sidebar, simulated users for roleplay servers, and [squaremap](admin/squaremap.md) support.

</div>

## Where to start

| You are… | Read |
|---|---|
| A server owner installing MCcomm | [Getting started](getting-started.md), then [Configuration](admin/config.md) |
| A player | [The phone](players/phone.md) and [SIMs, numbers and plans](players/plans.md) |
| Starting a carrier | [Running a carrier](carriers/index.md) |
| Starting a manufacturer | [Running a manufacturer](manufacturers.md) |
| Curious how signal works | [Signal reference](signal.md) |

## Download

Get the latest jar from [GitHub Releases](https://github.com/bladestech/MCcomm/releases).
