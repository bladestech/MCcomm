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
| Service | 35% | 60% reliability (worn, unpaid, silent or offline towers and understaffed engineering pull it down) and 40% speed (the top speed your towers give: best band × backhaul, on a log scale where 300 Mbps is full marks) |
| Coverage | 20% | Area your towers cover, weighted by band, compared with the best-covered carrier |
| Support | 20% | Customer support staffing: 100% when staffed, lower when overloaded |
| Price | 25% | Your average plan price per day against the going rate |

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

The pool itself grows: **1% a day** by default (`market.growth.per-day`), up to 10,000 customers (`market.growth.max-size`). The newcomers have no carrier yet, so they shop around like everyone else. This doesn't depend on staff or maintenance, so it carries on when the [workforce is switched off](operations.md).

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
- **Staff matter.** Too few support agents makes customers unhappy. Too few network
  engineers slows the whole network, real players included, which hurts service.
- **Repairs matter.** Worn towers are slower, which lowers service.
- **Decline is gradual.** A struggling carrier loses customers bit by bit, so there's
  time to fix things.

## Staff for subscribers

Subscribers here means real subscribers plus simulated customers. The first 25 need
no office staff (`workforce.self-service-subscribers`).

| Role | Covers (regular) | Works in | Room needs |
|---|---|---|---|
| Customer support | 60 subscribers | Call centre | One seat (stairs) and one lectern each |
| Network engineer | 100 subscribers on your towers (including MVNOs you host) | Network operations centre | One seat (stairs) and one observer each |

Juniors cover 75% as much and seniors 135%. Hire them on the **Staff** tab, build the
rooms with `/facility create callcentre <name>` or `/facility create noc <name>`.
See [Operations and repairs](operations.md) for wages, hiring and payday.

## Transit

Data costs the network that carries it **1 cr per GB** (`workforce.transit`), paid
on payday with wages and rent. Simulated customers count as 1 GB a day each, only
while simulated revenue is on.
