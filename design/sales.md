# Design spec: Plan sales

Status: **draft, being built.**

## Why

Carriers and ISPs compete on price, but a price change is permanent and hits every
customer. A **sale** is a temporary discount: it pulls in customers for a while and
then ends by itself. To keep "sales" honest, a regulator fines companies that are
always on sale.

## A sale

- On one plan (carrier or ISP), **5-70% off** (`sales.min-percent`,
  `sales.max-percent`), for **1 hour to 7 days** (`sales.max-hours`).
- Sign-ups, plan switches, top-ups and renewals during the sale pay the sale price.
  Simulated customers see it too: the market's average price, simulated revenue and
  the ISP household market all use the current price.
- **The "was" price must be real:** a sale can only start if the plan's price hasn't
  changed for `sales.clean-price-days` (7). During a sale the price can't be edited.
- The owner can end it early. Starting or ending a sale doesn't count as a plan edit
  (no "plan improved" notices, nobody is grandfathered).
- Customers see "~~12~~ 9 cr, 25% off until ...".

## The regulator

Per company, over the last `sales.window-days` (30):

- Count the **days on which any of its plans was on sale**.
- At `sales.warn-at` (8 and 10 days): a warning.
- Every sale day beyond `sales.allowed-days` (10) is fined
  `max(sales.fine-min, sales.fine-per-customer × customers)` (100, 2 cr), and the
  fine **doubles** for every earlier offence in the last `sales.offence-memory-days`
  (90).
- Fines come out of the company's balance (it can go negative, then normal default
  rules apply) and show in the ledger.

## Loophole checks

| Risk | Rule |
|---|---|
| Raise the price, then "discount" it back | The price must be unchanged for 7 days first, and is locked during the sale |
| Rotate sales across plans | The count is per company, any plan |
| Endless 6-day sales with gaps | The 30-day window counts days, not sales |
| Snapshot terms keep the sale | Sale fields are never copied into grandfathered terms or a router's terms |

## Open questions

1. Should rivals or players be told when a competitor starts a sale (news on the browser)?
2. Should fines go to a server pot instead of disappearing?
