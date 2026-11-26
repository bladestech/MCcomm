# Running a carrier

A **carrier** (telco) owns towers, sells plans to players, and pays to keep its network running.

## Creating one

```
/carrier create <name>          a network operator with towers
/carrier create <name> mvno     a virtual carrier that runs on someone else's network
```

- Names are 2–20 letters, numbers or `_ - & .`.
- Players can own **1 carrier** by default (`carriers.max-per-player`); admins are exempt.
- New carriers get a **number prefix** (e.g. `297`) and a default **Standard** plan (12 cr/day).
- MVNOs are covered on [Roaming and MVNOs](partners.md).

## The management screen

**`/carrier manage [name]`**, or **Work** on the phone. Six tabs along the top:

| Tab | What's in it |
|---|---|
| **Overview** | Balance (click to deposit), net income per day, subscribers and market share, upkeep health, **alerts** (worn towers, underfunded operations, money owed to a parent company, offers, stock to install) and quick actions |
| **Network** | Every tower, most worn first, with status, wear and repairs; the **Tower shop**, **Stock**, and a link to field crews |
| **Customers** | Market standing, the trade-in program (see [Plans](plans.md#trade-in-program)), branding, and your **plans** (click to edit, **New plan**) |
| **Operations** | The [operations budget](operations.md): field crews, customer care and network ops, what each costs and does, the contractors switch, and players with access |
| **Money** | Deposit, ledger, valuation, withdraw, add from your wallet, sell or liquidate, and its [parent company](../groups.md) (see [Companies and economy](../economy.md)) |
| **Partners** | Roaming agreements and MVNO hosting |

Every page has a **Help** book in the bottom-right corner, and long lists have page arrows.

## Staff

```
/carrier employee add|remove <carrier> <player>
```

- **Employees** can view everything, deposit ores, buy and install towers, toggle bands, and fit backhaul blocks.
- **Only the owner** edits plans, branding, the trade-in program and partner deals, and moves money or sells or liquidates the company.

## Branding

Your carrier's name can be a **solid colour** or a **2–4 colour gradient**. It's shown on tower screens, phones, the sidebar, `/internet` and the map.

- **Branding** in `/carrier manage` offers 14 solid colours, 10 preset gradients (Sunset, Ocean, Neon, Forest, Fire, Ice, Royal, Candy, Rainbow, Mono), or a custom style you type, like `#ff5f6d #ffc371`.
- `/carrier color <name> <colours...>` works too, and accepts presets like `sunset`.

Plans can have their own style. If a plan has none, it uses the carrier's.

## Making money

- **Income:** plan payments, prepaid top-ups, data packs, data overage, roaming surcharges, and overdue debts paid back.
- **Costs:** buying towers, [upkeep](upkeep.md), [operations](operations.md), and roaming fees when your subscribers use partner networks.
- **Sales:** put a plan on a [temporary sale](sales.md) to win customers. Keep it up too long and the regulator fines you.

To take profit out, use **Company → Withdraw to your wallet** (5% fee). See [Companies and economy](../economy.md).

## Other commands

| Command | Does |
|---|---|
| `/carrier info [name]` | Owner, employees, tower count |
| `/carrier list` | All carriers |
| `/carrier company [name]` | Open the Company page |
| `/carrier delete <name> confirm` | Liquidate it (same as **Company → Liquidate**) |
