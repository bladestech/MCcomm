# Design spec: Parent companies (groups)

Status: **draft, being built.**

## Why

Players who run a carrier, a manufacturer and an ISP manage three separate
businesses with three wallets. A **group** (parent company) holds any number of
them, with one treasury, cheaper transfers inside it and financial backing for its
members. It also prepares for mergers and buyouts later.

## What a group is

- Owned by one player. A player can own **any number** of groups
  (`groups.max-per-player`, -1 = unlimited).
- Holds carriers, manufacturers and ISPs (**members**), any mix
  (`groups.max-members`, -1 = unlimited). A company is in at most one group.
- Has a **treasury** (its own balance and ledger), a name and colours.
- A member's owner is always the group's owner. A company joins only if the group's
  owner owns it.
- Joining is optional: a company outside a group works exactly as today.
- Companies inside a group don't count towards the per-player limits
  (`carriers.max-per-player`, `manufacturing.max-per-player`,
  `broadband.max-isps-per-player`).

## Money

| Transfer | Fee |
|---|---|
| Group ↔ member | **2.5%** (`groups.transfer-fee-percent`) |
| Member → member | Through the treasury: 2.5% |
| Group or company ↔ a player's wallet | The normal 5% |

Money leaving a company keeps the usual rules: the next upkeep bill stays, and
nothing leaves while the company owes the group (except repayments).

## Benefits

1. **Financial backing.** When a member can't pay its upkeep or its operations bill,
   the treasury pays it, as a **loan** to the member (no fee).
   - The member doesn't go dark, decay or lose funding while the treasury can pay.
   - Loans are repaid automatically from the member's balance once it has more than
     its next upkeep bill.
   - While a member owes, it can't send money to a wallet.
2. **Bankruptcy grace.** A member in default gets `groups.bankruptcy-extra-days`
   (7) more before it closes. The group is never liable beyond its treasury.
3. **Economies of scale.** Each member pays `groups.ops-discount-per-member` (5%)
   less for operations per other member, at most `groups.ops-discount-max` (15%).
4. **One overview**: combined value and income, loans, and every member with its
   alerts.

## Lifecycle

- **Leaving:** a member can leave once its loan is repaid. It stays owned by the
  group's owner.
- **Liquidating a member:** its payout goes into the treasury.
- **Liquidating the group:** every member is liquidated into the treasury, then the
  treasury goes to the owner minus `companies.liquidation-fee-percent`.
- **Selling:** a group is sold with all its members. Selling a single member takes it
  out of the group (its loan must be repaid first).
- **Valuation:** the treasury plus each member's valuation (loans cancel out inside
  the group).
- **Bankruptcy of a member:** it closes as today; any loan it can't repay is lost by
  the treasury.

## Commands and menus

`/company` (alias `/group`): `create <name>`, `list`, `info [group]`,
`add <carrier|mfr|isp> <name> [group]`, `remove <name>`, `deposit`/`withdraw <amount>`,
`transfer <from> <to> <amount>`, `sell`, `liquidate`, `color`.

The group menu has the tabs Overview, Companies, Money and Loans. The phone's Work app
lists groups next to their members.

## Loophole checks

| Risk | Rule |
|---|---|
| Dodge the 5% wallet fee | Only transfers inside the group are cheaper; anything to a wallet pays 5% |
| Empty a failing member before it closes | Outgoing transfers keep the upkeep reserve, and nothing leaves while it owes |
| Loan farming | Loans only pay bills that are due, directly; they never go to a wallet |
| Bypass company limits forever | Limits are config (`groups.max-members`); costs still grow per company |
| Self-dealing with the group's manufacturer | Valuation and liquidation already use manufacturing cost |

## Open questions

1. Should groups be able to have staff of their own (players who manage every member)?
2. Mergers: two carriers in the same group merging into one (from workforce.md §13).
