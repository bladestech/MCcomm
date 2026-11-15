# Running a manufacturer

**Manufacturers** design tower models and sell them to carriers. They earn the sale margin plus a share of upkeep on every tower they built.

## Founding one

```
/manufacturer create <name>
/manufacturer manage [name]      (or "My factory" on the phone)
```

Players can own **1 manufacturer** by default (`manufacturing.max-per-player`).

## Research

Research unlocks what you can build. Fund it in **Research**:

- **Fund with ores**: ores go straight into research points.
- **Fund from wallet**: spend credits from the manufacturer's wallet.

1 credit = 1 research point by default.

You start knowing **Gen 1 with 2G and 3G**. Generations unlock in order, and each band must be researched separately, once its generation is:

| Generation | Cost (points) | Allows |
|---|---|---|
| Gen 2 | 150 | 4G |
| Gen 3 | 350 | 4G+, 5G Low |
| Gen 4 | 700 | 5G Mid, 5G High |
| Gen 5 | 1,400 | 5G mmWave |

| Band | Research cost (points) |
|---|---|
| 4G | 80 |
| 4G+ · 5G Low | 160 each |
| 5G Mid | 280 |
| 5G High | 420 |
| 5G mmWave | 700 |

## Designing models

In **Tower models → New model**, give it any name you like (e.g. "Skyline S1"), then set:

- **Generation**: up to what you've researched.
- **Bands**: any researched bands that generation allows. Leaving bands out makes the tower cheaper to make.
- **Margin**: 0–500%.
- **Published**: whether carriers can buy it.

The editor shows **cost**, **price**, **profit per tower** and **upkeep share** live, before you publish.

### What a tower costs you to make

```
cost = 20 + 15 × (generation − 1) + the cost of each included band
price = cost × (1 + margin)
```

| Band | 2G | 3G | 4G | 4G+ | 5G Low | 5G Mid | 5G High | mmWave |
|---|---|---|---|---|---|---|---|---|
| Cost | 3 | 5 | 8 | 12 | 12 | 18 | 25 | 40 |

A full-band Gen 5 tower costs 203 cr to make. At a 40% margin it sells for 284 cr.

Specs are identical between manufacturers: same generation and bands means same range and speed. You compete on **price**, **band selection** and **naming**.

## Income

- **Sales:** when a carrier buys, you receive `price − cost` per tower (the manufacturing cost leaves the economy).
- **Upkeep share:** **10%** of the upkeep on every installed tower you built, paid whenever its carrier pays upkeep. It continues for as long as the tower stands.

Your overview shows your wallet, upkeep-share income per day, and towers installed.

## Models after sale

- **Unpublish** a model to stop selling it.
- A model **can't be deleted** while any carrier still has it in stock.
- Installed towers keep their model name and specs even if you rename or delete the model later.

## The Generic manufacturer

The server runs a manufacturer called **Generic**. It sells a full-band model for every generation at cost + 40%, named after the `generations` section of the config (CT-100 Classic … CT-500 NR-X). It never earns anything, and it can be turned off with `manufacturing.generic.enabled`.

## Company

Your **Company** page shows a valuation and lets the owner withdraw money, sell or liquidate. See [Companies and economy](economy.md).
