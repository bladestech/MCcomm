# Wallet and phones

## Credits

Everything in MCcomm is priced in **credits** (cr). You get credits by **depositing ores**:

| Ore | Credits | Block | Credits |
|---|---|---|---|
| Copper ingot | 1 | Copper block | 9 |
| Iron ingot | 3 | Iron block | 27 |
| Gold ingot | 10 | Gold block | 90 |
| Redstone | 1 | Redstone block | 9 |
| Lapis lazuli | 2 | Lapis block | 18 |
| Emerald | 30 | Emerald block | 270 |
| Diamond | 40 | Diamond block | 360 |
| Netherite ingot | 200 | Netherite block | 1,800 |

These are the defaults; servers can change them.

## The wallet

**`/cell wallet`** (or the Wallet app) shows:

- **Balance**, and any **overdue** debt (in red).
- **Deposit ores**:
    - Put ores in the box, or click "Add all ores from your inventory".
    - Closing it converts them to credits.
    - Anything that isn't an ore comes back.
- **Your subscriptions**: plan, next charge or expiry, data left, and any phone loan. See [plans](plans.md) for the click actions.
- **History**: your recent transactions.
- **Send credits** to another player. This is **free**. `/cell pay <player> <amount>` does the same.

## Phones

Phones are bought in **`/cell eshop`** and stay on your account. You use one at a time and can switch freely between phones you own.

| Phone | Bands | SIM slots | Price |
|---|---|---|---|
| Connect | up to 3G | 1 | 64 cr |
| Connect+ | up to 3G | 2 | 120 cr |
| Explorer | up to 4G+ | 1 | 300 cr |
| Explorer+ | up to 4G+ | 2 | 420 cr |
| Pilot | all bands | 1 | 800 cr |
| Pilot+ | all bands | 2 | 1,100 cr |

Your phone limits the **highest band** you can connect on. A Connect next to a 5G tower still gets 3G.

### Selling a phone

Shift-right-click a phone you own in the eShop (twice to confirm) to sell it for **50%** of its price.

- You can't sell a phone you're still paying off, or one that's blacklisted.
- Phones you got for free (a starter phone, or one given by an admin) sell for nothing.

### Financed phones

Some carriers offer plans with a **phone included**: the carrier buys the phone, and you repay it (plus interest, minus a down payment) in instalments with your plan.

- The phone is **locked** to that carrier until it's paid off. Other carriers' SIMs won't work in it.
- **Pay off early** any time: shift-click the SIM in your wallet.
- When it's paid off, it **unlocks automatically** and is yours.
- If you miss a payment, the remaining balance becomes due and the phone is **blacklisted** until you pay. See [Missed payments](plans.md#missed-payments).
- **Trade-ins:** if the carrier runs a trade-in program, shift-right-click the SIM in your wallet to trade in another phone you own. Its value comes off the loan, and **each remaining instalment gets smaller**.

### Paperweights

If a carrier **closes down** while you're still paying off its phone, the phone stays locked to the dead carrier forever. It's a **paperweight**: it never works again, can't be traded in, and sells back for **5%**.
