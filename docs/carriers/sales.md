# Plan sales

Put a plan on a **temporary sale** to win customers: new sign-ups, renewals and top-ups
pay the sale price until it ends. Carriers and [internet providers](../isps.md) both
have it.

Open the plan in the plan editor and click **Sale**:

1. Enter how much off: **5–70%**.
2. Enter how long: **1 hour to 7 days** (168 hours).

The plan shows ~~the old price~~ and the sale price, with the time left, everywhere
customers see it. The market counts the sale price too, so a sale makes you more
attractive while it lasts.

## The rules

- **The price must be real.** A plan can only go on sale if its price hasn't changed for
  **7 days**, so the "was" price is one customers actually paid. Raising the price and
  then "discounting" it doesn't work.
- **The price is locked** during a sale. End the sale first to change it.
- **End it early** with shift-right-click on the Sale button. It also ends on its own,
  and you're told.
- A sale doesn't count as a plan change: existing customers keep their terms.

## The regulator

A sale that never ends is just a misleading price. The regulator counts the days on
which **any** of your plans was on sale over the last **30 days**:

| Sale days in 30 | What happens |
|---|---|
| Up to 7 | Nothing |
| 8 and 10 | A warning |
| More than 10 | A **fine** for every further day a sale is running |

The fine is `max(100, 2 × your customers)` credits, **doubled** for each fine you got in
the last 90 days. It comes straight out of the company's balance, and can take it below
zero. End the sale and the fines stop. Wait until older sale days fall out of the
30-day window to run another.

The Sale button shows your sale days so far.

**Example:** a carrier with 300 customers keeps a plan on sale for 14 days in a row. Day
11 costs 600, day 12 1,200, day 13 2,400 and day 14 4,800.

Server owners set all of this in the `sales` section of [`config.yml`](../admin/config.md).
