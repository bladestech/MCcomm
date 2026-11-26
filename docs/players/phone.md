# The phone

Type **`/cell`** to open your phone. Almost everything a player does happens here; the typed commands are shortcuts.

## You need a phone first

Phones are bought in the **Shop** app (or `/cell eshop`). Without a phone you have **no service at all**, emergency calls included. See [Wallet and phones](wallet.md) for the models.

## Home screen

| Where | What |
|---|---|
| Top row | One status item per SIM slot, coloured by signal: green (4 bars), yellow (3), orange (2), red (1), grey (no service). Then **Your numbers**, your phone model, the clock and Close. |
| Apps | **Messages**, **Contacts**, **Dial**, **Signal**, **SIMs and plans**, **Shop**, **Wallet**, **Settings**. |
| Work | Shown if you run or work for a carrier or manufacturer; opens it (or asks which). |
| Bottom row | **Emergency 909**, call controls (Answer, Decline, Hang up) and Help. |

| App | What's in it |
|---|---|
| Signal | Each SIM's signal, a speedtest, and towers within 4 km |
| SIMs and plans | Your active and inactive SIMs, your data SIM, and every carrier to subscribe to |
| Shop | Phones to buy, use or sell, and trade-ins against a phone loan |
| Wallet | Balance, deposits, subscriptions (change plan, top up, buy data), history, send credits |

Every screen has the same frame: Back at the bottom left, page arrows in the middle when a list is long, and a **Help** book at the bottom right that explains the page.

The home screen updates live while it's open.

!!! info "No Service vs. Emergency calls only"
    If you have no active SIM (or no plan) but any tower reaches you, you see **No Service · Emergency calls only (909)**. Dialling 909 currently only shows a message.

## Typing things in

When the phone needs text, like a number or a message, it closes and asks in chat. Your next chat line is captured, not broadcast. Type `cancel` to back out.

## The signal sidebar

A scoreboard on the right of the screen shows, for each SIM:

```
 » Nortus-Telecom · data      ← carrier, in its colours; "data" marks your data SIM
   |||| 5G mmWave R Partner   ← bars, band; R = roaming on a partner network
   -60 dBm · 23 m · T2        ← signal, distance, tower
   Standard · Unlimited       ← plan · data left
```

When you're on [home Wi-Fi](home-internet.md) the sidebar also shows it, with bars like the cell signal:

```
 Wi-Fi |||| Home Cord           ← bars, and the router's name
   Cordline Fiber · 940 Mbps   ← provider · line speed
```

The bars fall as you walk away from the router or put walls between you. In action bar mode the Wi-Fi is added to the end of the line.

Under the SIMs it shows any call, the carriers you run with their balances (green, gold or red by health, with ▲/▼ for the last change), and a footer with your wallet, phone and the time.

Turn it off and on with **`/cell hud`** or in **Settings**.

## Signal and speedtest

- **Signal** lists towers within 4 km: carrier, model, generation, bands, distance, height and status.
- **Speedtest** (`/cell speedtest`) measures ping, download and upload over a few seconds, shown in a boss bar.
    - It **uses real data**: speed × duration. A 1 Gbps test burns about 300 MB.
    - There's a 10-second cooldown.

## Settings

- **Signal sidebar**: on or off.
- **Do Not Disturb**: blocks incoming calls and silences texts. Texts are still delivered.

## Command shortcuts

| Command | Does |
|---|---|
| `/cell` | Open the phone |
| `/cell status` | Your connection in chat |
| `/cell speedtest [slot]` | Run a speedtest |
| `/cell number` | Your phone numbers |
| `/cell contacts` · `/cell messages` | Open those apps |
| `/cell eshop` · `/cell wallet` | Open the eShop or wallet |
| `/cell hud` | Toggle the sidebar |
