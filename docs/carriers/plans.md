# Plans

Open the **Customers** tab of **`/carrier manage`**; your plans are listed there. The owner can create, edit and delete plans; staff can view them.

## The plan editor

| Setting | Meaning |
|---|---|
| **Style** | The plan name's colour or gradient (defaults to the carrier's) |
| **Name** | Shown in shops, wallets and the sidebar |
| **Type** | Postpaid, Financed phone, or Prepaid (see below) |
| **Price** | Per period (postpaid and financed) or per top-up (prepaid) |
| **Period / Valid for** | 1–720 real hours (24 = daily, 168 = weekly) |
| **Max band** | Subscribers can't go above this, even on a better phone |
| **Speed cap** | Download is limited to this (0 = no cap) |
| **Texts** · **Calls** | Included or not |
| **Data** | GB per period (or per top-up), or unlimited |
| **Out of data** | Slow down (0.5 Mbps), block data, or charge per extra GB |
| **Overage price** | Per extra GB, if charging |
| **Data pack** | An add-on subscribers can buy: size and price |
| **Roaming** | Allowed or not, and a per-GB surcharge for subscribers |

When a plan has subscribers, the editor header explains how your edits will reach them.

### Postpaid

Charged every period from the subscriber's wallet. If they can't pay, the plan is cancelled and they owe you **4×** that payment. You receive it when they pay it off.

### Prepaid

Subscribers top up when they want. Each top-up costs the price, lasts the period, and adds the plan's data; top-ups stack. When it expires they lose service until they top up. There's **no debt**.

### Financed phone

A postpaid plan with a phone included. Set it up with:

- **Phone**: which model to give.
- **Instalments**: how many periods to repay over.
- **Interest**: a percentage added to the phone's price.
- **Down payment**: paid up front.

When someone signs up:

- **Your wallet pays the phone's retail price**, so you need the balance. The editor shows how many phones your wallet can fund.
- The subscriber pays the down payment and first period, then `price × (1 + interest) − down payment` spread over the instalments, added to each charge.
- The phone is **locked** to your network until paid off.

If they **default**, the rest of the phone's balance becomes overdue on top of the 4× charge, and the phone is **blacklisted** until they pay. If they never pay, you take the loss: that's the risk you price in with interest and the down payment.

### Trade-in program

In the management screen, turn on the **Trade-in program** and set a **rate** (default 60%; off for every carrier by default).

- Subscribers paying off a phone with you can trade in another phone they own. You credit them the rate × that phone's price off their loan, and their remaining instalments get smaller.
- You receive the phone's 50% resale value. At 60% a trade-in costs you 10% of the phone's price.
- A trade-in can't credit more than what's left on the loan.

## Changing a plan with subscribers

- **Better for subscribers** (lower price, more data, fewer limits): applies to everyone immediately.
- **Worse in any way** (including a different period): current subscribers keep their old terms until their next renewal or top-up. New subscribers get the new terms straight away.
- Subscribers get one summary message about a minute after you stop editing.
- **A plan's type can't be changed** while it has subscribers. Create a new plan instead.

## Ordering plans

Plans show to subscribers in the order they appear in your list. In the Plans list, **right-click** a plan to move it later or **shift-left-click** to move it earlier. Or open a plan and use **Move earlier** and **Move later** (shift-click to jump to the top or bottom). The order is saved.

## Deleting a plan

Shift-right-click it twice. Its subscribers drop to "no plan" (no service, **no debt**) and are told to choose another.
