# SIMs, numbers and plans

## Subscribing

Open **`/cell` → Carrier store** (or `/cell subscribe <carrier>`), pick a carrier and choose one of its plans. The **first period is charged immediately** from your wallet.

You get a **phone number** for that carrier, like `297-7402`. The first three digits are the carrier's prefix. You keep the number while you're subscribed (active or inactive) and lose it when you unsubscribe.

!!! warning "Overdue debt blocks subscribing"
    If you owe money to any carrier, you can't subscribe or pick a plan anywhere until it's paid. See [Missed payments](#missed-payments).

## SIM slots

- You can have up to **2 active** and **4 inactive** SIMs. How many can be active depends on your phone: **+** models have two slots.
- **Only active SIMs** connect and receive calls and texts.
- Manage them in **SIM cards**:
    - Click to activate or deactivate.
    - Right-click to make a SIM your **data SIM**.
    - Shift-click twice to unsubscribe.
- Switching to a single-SIM phone moves your second SIM to inactive.

### The data SIM

Your **data SIM** is used for speedtests, `/internet`, and the number your calls and texts come from. If it has no service, the other SIM takes over. Choose it in SIM cards, or with `/cell data <carrier|auto>`. Automatic means the first SIM with service.

## Plan types

| Type | How you pay | Notes |
|---|---|---|
| **Postpaid** | Charged every period (e.g. daily) from your wallet | Missing a payment creates [debt](#missed-payments) |
| **Financed** | Like postpaid, plus instalments for a phone the carrier bought you | The phone is **locked** to that carrier until paid off |
| **Prepaid** | You **top up** when you want; each top-up lasts the plan's period and adds its data | No debt, ever. Service stops when it expires |

Each plan can also set:

- **Data**: an allowance per period (or per top-up), or unlimited.
- **When out of data**: slow down (to 0.5 Mbps), block data, or charge per extra GB.
- **Max band**: e.g. a cheap plan capped at 4G even on a 5G phone.
- **Speed cap**: e.g. 50 Mbps.
- **Texts and calls**: included or not. A data-only plan has neither.
- **Roaming**: allowed or not, and a per-GB surcharge.

The wallet shows each SIM's plan, data left and next charge.

- Right-click a prepaid SIM to **top up**, or a SIM on a data plan to **buy a data pack** (if the carrier offers one).
- Click a SIM to **change plan**. A new postpaid price applies from your next charge; switching to or from prepaid starts the new plan immediately.

## When a carrier changes your plan

- **Changes in your favour** (a lower price, more data, fewer limits) apply immediately. Extra data is added to your current period.
- **Anything worse is grandfathered:** you keep your current terms until your next renewal (or next top-up on prepaid), then move to the new terms.
- You get one message summarising the change. Your wallet shows a pending change and the new terms.

## Missed payments

If your balance won't cover a charge, you're **warned 6 hours before**. If it still can't be paid:

- The plan is **cancelled**. You keep the SIM and number, but get no service.
- That payment becomes **overdue debt at 4×** its price.
- If you were paying off a phone: the **rest of the phone's balance** becomes due at once, and the phone is **blacklisted**. It won't work on any carrier until the debt is paid.
- You **can't subscribe or pick a plan anywhere** until the debt is paid.

Any deposit or incoming payment pays debts off first. When a carrier's debt is cleared, its blacklisted phones work again and are yours.

## Roaming

If your carrier has no coverage where you are but has a **roaming agreement** with another network, you connect to the partner's towers. The sidebar shows **R** and the partner's name.

- Roaming only works if your plan allows it.
- Your carrier may charge a roaming surcharge per GB.
- Calls and texts cost nothing extra.

## MVNOs

Some carriers are **MVNOs**: virtual carriers with no towers of their own that run on another network. To you they work like any carrier: their own plans, prices and branding, on the host's towers.
