# Companies and economy

## Where credits come from and go

Credits **enter** the economy when players and companies **deposit ores**, and when phones are sold back to the eShop. With [simulated revenue](admin/simulation.md) on, simulated users also create credits.

Credits **leave** the economy through:

- buying phones;
- manufacturing costs when towers are sold;
- 90% of tower upkeep;
- research;
- transfer and liquidation **fees**.

Everything else, like plans, roaming, revenue shares, overdue debts and trade-ins, **moves** credits between players and companies.

Credits never turn back into ores.

## Moving money

| From → to | Fee |
|---|---|
| Player → player (`/cell pay`, wallet **Send credits**) | **Free** |
| Your carrier, manufacturer or ISP → you | **5%** |
| You → your carrier, manufacturer or ISP | **5%** |
| You ↔ your [parent company](groups.md) | **5%** |
| Parent company ↔ one of its members | **2.5%** |

Company transfers are on the **Money** tab of `/carrier manage`, `/manufacturer manage` and the ISP menu (or `/carrier company`, `/manufacturer company`), owner only. A company that owes its parent company can't send money out until the loan is repaid. You're warned if a withdrawal leaves the carrier short of its next upkeep. Fees **round up** and payouts **round down**, so rounding never creates credits.

## Valuation

Each company page shows a **valuation**, a guide for setting a sale price:

- **Carrier:** wallet + installed towers at what they'd cost to build + unused stock at purchase price + **30 days of net income** (plan income minus upkeep), if positive.
- **Manufacturer:** wallet + what its research is worth + 30 days of upkeep-share income.
- **ISP:** wallet + nodes at what they'd cost to build + 30 days of net income, if positive.
- **Parent company:** treasury + every member's valuation. A loan to a member counts for the parent and against the member, so it cancels out.

## Selling a company

**Money → Sell the company**: name an online player and a price. They get clickable **Accept** and **Decline** buttons, valid for 5 minutes. The price moves from their wallet to yours, and **everything else carries over unchanged**: towers, plans, subscribers, agreements, loans, research, models and operations budgets.

Selling a company that's in a parent company takes it out of the group; anything it owes the group must be repaid first. To sell the whole group, sell it from the group menu.

## Liquidating a carrier

**Company → Liquidate** (shift-right-click twice; the full breakdown is shown first). The carrier closes for good.

| Step | What happens |
|---|---|
| Payout | Wallet + towers as scrap (**30%** of build cost) + unused stock (**50%** of manufacturing cost) |
| MVNO farewell | If it hosted MVNOs, **20%** of that is split between them as farewell money |
| Fee | **10%** of the rest |
| To you | What's left, paid into your wallet |
| Towers | Taken down |
| Subscribers | Lose their SIMs and numbers; debts owed to the carrier are **cancelled** |
| Phones being financed | Become **paperweights**: locked to the dead carrier forever, sell for 5% |
| Agreements | Roaming partners lose access; hosted MVNOs have no network until they find a new host |

### Bankruptcy

A carrier or ISP that can't pay its upkeep for 7 days in a row (`companies.bankruptcy-days`) is liquidated automatically, the same way. Members of a parent company get 7 more days. See [Operations](carriers/operations.md#bankruptcy).

## Liquidating a manufacturer

- Carriers holding its unsold models are **refunded their purchase price** from the manufacturer's wallet first, scaled down if it can't cover them all.
- The rest, minus the 10% fee, goes to the owner.
- Its installed towers keep working; they just stop paying it the upkeep share.

## Liquidating an ISP

Wallet + nodes as scrap (**30%** of build cost), minus the 10% fee, goes to the owner. The nodes come down and every customer loses their line.

A company inside a [parent company](groups.md) pays its liquidation money into the treasury, not your wallet.

## Economy safeguards

- **Free phones are worthless on resale.** Starter phones and admin-given phones sell and trade in for nothing, and the starter phone is only ever given once.
- **Liquidation uses manufacturing cost**, never a self-set sale price, so buying your own towers at a huge margin and liquidating loses money.
- **Self-dealing doesn't pay.** Owning both the carrier and the manufacturer, financing a phone through your own carrier, trading in at 100%, or defaulting on your own carrier all cost you money.
- **Server owners:** `/celladmin credits`, `/celladmin carriercredits` and the simulated revenue toggle can all create credits. Give `mccomm.admin` and `mccomm.simulate` only to people you trust.
