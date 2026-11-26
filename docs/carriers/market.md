# The customer market

With `simulation.mode: market` (the default), the server has a fixed pool of
**simulated customers**. The admin sets its size in `/cellsim`. Each customer is with
one carrier or with none, and every hour some of them move:

- customers **leave** carriers they're unhappy with, or that charge too much;
- customers **without a carrier** shop around and sign up with the most attractive one.

Your standing is on the **Customers** tab of `/carrier manage` (and in the Overview).

## Satisfaction

Every carrier gets a satisfaction score from 0 to 100%:

| Part | Weight | What it measures |
|---|---|---|
| Service | 35% | 60% reliability (worn, unpaid, silent or offline towers and underfunded network ops pull it down) and 40% speed (the top speed your towers give: best band × backhaul, on a log scale where 300 Mbps is full marks) |
| Coverage | 20% | Area your towers cover, weighted by band, compared with the best-covered carrier |
| Support | 20% | Your customer care funding (3-day average): 100% when fully funded |
| Price | 25% | Your average plan price per day against the going rate ([sale prices](sales.md) count while they last) |

**The going rate** is the server's fair price (`market.reference-price-per-day`,
12 cr/day by default) blended half and half with the median of rivals' prices. So
prices matter even when you're the only carrier.

## Ratings

Players see each carrier's satisfaction as a **star rating** from 1 to 5:
`rating = 1 + 4 × satisfaction`. It's shown in the phone's **SIMs and plans** list and on
each carrier's plans page, after the model name on tower screens, in `/carrier info`,
and in squaremap tower popups. Hovering shows a star score for each part (service,
coverage, support, price), so players can see why a carrier rates high or low. In fixed
mode the rating is worked out the same way, just without customers moving.

## Losing customers

Every day a share of your customers leaves:

- **1%** even when everyone's happy;
- up to **12%** more as satisfaction drops (it grows with unhappiness squared);
- more when you charge **above the going rate**: 0.75% extra at 1.5×, 3% at 2×;
- **25%** when priced out at **3×** the going rate or more. Nobody joins you then.

If your network has no working towers at all, customers leave fast and nobody joins.

## Growth

The server can let the pool grow over time (`market.growth.per-day`, off by default; `0.01` is 1% a day), up to 10,000 customers (`market.growth.max-size`). The newcomers have no carrier yet, so they shop around like everyone else. This doesn't depend on operations or maintenance, so it carries on when [operations are switched off](operations.md).

## Switching

Customers who are happy enough to stay still compare carriers now and then: **2% of each
carrier's customers a day** look around, and move if a rival is at least **15% more
attractive** (`market.switching`). So a clearly better newcomer can win customers from
the leader, not just pick up the ones who left.

## Winning customers

Customers without a carrier shop at about 30% a day. They pick carriers in
proportion to how attractive they are: satisfaction squared, times coverage, times a
price factor that falls as your price rises above the going rate. If even the best
carrier is unappealing, many of them stay without a carrier.

## What this means for you

- **Raising prices** earns more per customer but loses customers. A monopoly can charge
  a premium, but well above the going rate it loses more than it gains.
- **Coverage and speed win customers.** More area, newer bands and better backhaul
  all raise your score.
- **Operations matter.** Underfunded customer care makes customers unhappy. Underfunded
  network ops slows the whole network, real players included, which hurts service.
- **Repairs matter.** Worn towers are slower, which lowers service.
- **Decline is gradual.** A struggling carrier loses customers bit by bit, so there's
  time to fix things.

## Operations and the score

Support and part of service come from your [operations budget](operations.md#the-budget):

- **Customer care** funding is your support score directly.
- **Network ops** funding multiplies network speed (never below ×0.6), which lowers service.

Both follow a 3-day average of what you actually paid, and the first 25 subscribers
(`operations.self-service-subscribers`) need neither.

## Transit

Data costs the network that carries it **1 cr per GB** (`operations.transit`), paid
hourly with the operations bill. Simulated customers count as 1 GB a day each, only
while simulated revenue is on.
