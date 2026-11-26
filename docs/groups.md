# Parent companies

A **parent company** (a group) bundles companies you own under one name with a shared
**treasury**. Carriers, manufacturers and internet providers can all join. Groups are
optional: nothing changes for a company that stays on its own.

```
/company create <name>
/company add carrier NovaMobile
/company add isp FairNet
```

`/company` (or `/group`) opens the group menu. It's also under **Work** on the phone,
and in each member's **Money** tab.

You can own as many parent companies as you like (`groups.max-per-player`, no limit by
default), and companies inside a group **don't count** toward the per-player limits for
carriers, manufacturers and ISPs.

## Why have one

**Financial backing.** When a member can't pay its upkeep, its operations bill or a
repair, the treasury lends it the difference automatically, with no fee and no interest.

- A member spends its own wallet first and only borrows what it can't pay. Because the
  treasury stands behind its next upkeep, it doesn't hold that back the way a company on
  its own does.
- The member keeps running instead of going dark.
- It repays the loan from its income before anything else: whatever it has above its
  next upkeep and the next hour of operations goes back to the treasury.
- While it owes, it can't send money out, and it can't be sold or taken out of the group.
- The treasury only lends what it has. The group is never liable beyond its treasury.

**Bankruptcy grace.** A member in default gets **7 more days** before bankruptcy (14 in
total by default).

**Economies of scale.** Each member's operations cost **5% less for every other member**,
up to 15% less (four or more members).

**Cheaper transfers.** Moving money between the treasury and a member costs **2.5%**,
half the usual 5%.

**One overview.** The group menu shows the treasury, combined value, income per day,
every member with its alerts, and any loans.

## Money

| From → to | Fee |
|---|---|
| Your wallet ↔ the treasury | 5% |
| The treasury ↔ a member | 2.5% |
| A member → another member | 2.5% each way, through the treasury |
| Loan repayments | Free |

```
/company deposit|withdraw <amount> [group]
/company give <carrier|mfr|isp> <name> <amount>     treasury → member
/company take <carrier|mfr|isp> <name> <amount>     member → treasury
```

A member keeps its next upkeep when you take money out, as with a normal withdrawal.
Only the owner moves money.

## Members

- Only companies **you own** can join, and only one group at a time.
- A 5G Home ISP can't join on its own: it follows its carrier's owner.
- `/company remove <carrier|mfr|isp> <name>` takes a member out. It must repay its loan
  first.
- Every member always belongs to the group's owner.

## Selling and liquidating

- **Selling the group** (group menu → Money → Sell) sells it with every company in it.
- **Selling one member** takes it out of the group. Its loan must be repaid first.
- **Liquidating a member** pays its liquidation money into the **treasury**, not your
  wallet. Any loan it still owed is written off.
- **Liquidating the group** (`/company liquidate <group> confirm`, or shift-right-click
  twice in the menu) liquidates every member, then pays out the treasury minus the usual
  10% fee.

The group's **valuation** is the treasury plus every member's valuation. A loan counts
for the treasury and against the member, so it cancels out.

## Commands

| Command | Description |
|---|---|
| `/company` (`/group`) · `/company manage [group]` | Open the group menu |
| `/company create <name>` | Found one (2-24 letters, digits, `_` or `-`) |
| `/company list` · `/company info [group]` | Your groups · details |
| `/company add\|remove <carrier\|mfr\|isp> <name> [group]` | Add or remove a member |
| `/company deposit\|withdraw <amount> [group]` | Wallet ↔ treasury |
| `/company give\|take <carrier\|mfr\|isp> <name> <amount>` | Treasury ↔ member |
| `/company color <group> <colours...>` | Branding |
| `/company liquidate <group> confirm` | Liquidate the group |

Server owners set the limits, fee, extra days and discount in the `groups` section of
[`config.yml`](admin/config.md).
