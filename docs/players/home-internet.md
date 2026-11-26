# Home internet

Put a router in your house, pick an internet provider, and every computer and phone within Wi-Fi range of it is online. It's separate from your phone's mobile data: **`/internet` over home Wi-Fi uses no data**, and it works without a phone.

!!! info "The signs are screens"
    Nodes, routers and computers show the same kind of live screen as cell towers: a colour stripe in the ISP's colours, a status badge (ONLINE, FAULT, SUSPENDED...), chips (the node's technology, the router's Wi-Fi generation), and a footer with load or speed. Server owners can switch this off with `broadband.fancy-screens: false` for plain sign text.

## Set it up

1. **Place a wall sign** inside your home, then look at it and run:

    ```
    /broadband router install [name]
    ```

    The sign becomes a **router**. You can have up to 3.

2. **Right-click the router** and open the **Providers** tab. Each ISP is a **folder** (your current provider first). Providers and plans that can't be ordered at your spot are hidden, so you only see what reaches you. Inside a folder, the **Hidden plans** book (bottom right) lists the plans left out and why (no node in range, no clear sky, and so on). Each folder shows its rating, technologies, cheapest price and top speed here. Click one to open its plans, in the order the ISP set, each with a quote for *this exact spot*:

    | The plans show | Meaning |
    |---|---|
    | Technology | DSL, Cable, Fiber or Satellite (see below) |
    | `↓ ~speed` and ms | What you'd get here, and the ping |
    | Node and distance | Which of the ISP's nodes would serve you |
    | Price and install fee | Per billing period, plus a one-time fee |
    | A red reason | Why it can't be ordered here |

3. **Order a plan.** Confirm, and the install fee plus the first period are charged from your wallet. A technician takes about 20 seconds, then the router shows the provider and speed.

4. **Add a computer.** Place another wall sign and run:

    ```
    /broadband computer install [name]
    ```

    Within **24 blocks** of the router, it's online. Right-click it to open the computer screen.

## The computer

| Button | Does |
|---|---|
| Connection | Provider, plan, download, upload and ping |
| Speed test | Ping, download and upload over your line. Uses no data |
| Internet chat | Sends your chat to the [`/internet`](messaging.md) channel. Free over Wi-Fi |
| Wi-Fi | Cycle between *Automatic* and each router in range |

## Technologies

| | Reach | Best for | Catch |
|---|---|---|---|
| **DSL** | ~400 blocks from a node | Villages, cheap | Slows quickly the farther you are |
| **Cable** | ~800 blocks | Towns | The neighbourhood shares each node: busy times are slower |
| **Fiber** | ~1500 blocks | Everything | Rare and expensive for ISPs; distance barely matters |
| **Satellite** | Anywhere | The middle of nowhere | High ping, slow, **needs a clear view of the sky**, and storms cut it |
| **5G Home** | Wherever the carrier has 5G | No cable to dig | Sold by mobile carriers; only as good as the 5G signal at your door |

Server owners can retune all of this in [Configuration](../admin/config.md).

Every provider has a **star rating** (speed, uptime, value and coverage) on the Providers list. Quotes show the speed and ping you would get *at your router*, and a cabling fee for wired lines (per block from the node).

!!! tip "Dish needs the sky"
    For satellite, nothing solid may be above the router's sign. Put it on an outside wall under open sky, or on a roof.

## Paying

- Plans renew every period (set by the ISP, often 24 hours) from your wallet.
- Short on credits? You're warned a few hours before. If a renewal fails the line is **suspended**: no internet, and the sign says so. **Pay now** on the router, or deposit, and it comes straight back. After 12 hours unpaid the line is cut and you pick a provider again.
- If the ISP is down (it couldn't pay its upkeep), the node is gone, or your dish is blocked, you aren't billed while there's no service.
- Switching to another plan on the same ISP and technology has no install fee.
- If the provider changes its plan, you **keep your signed-up terms until your next renewal**, when you're told the new ones.
- Some plans have a **data allowance**. When it runs out the line slows to a crawl (1 Mbps) until the next renewal; it never cuts off. Speed tests and browsing use it.
- **Bundles:** if your provider has a bundle with a carrier you're on, the home plan is cheaper (and your mobile renewals are cheaper if you have the provider's home internet).
- **Outages:** a node can develop a fault, especially in storms. You aren't billed while it's down, and the provider fixes it or re-routes you to another node.

## Wi-Fi

- **Range:** a Wi-Fi 4 router reaches 24 blocks, and every solid block between the router and you costs about 1.5 blocks. Upgrade on the router screen: Wi-Fi 5, 6 and 7 reach farther and allow more guests.
- **Sharing:** a router is **private** by default. Switch it to *shared* and anyone in range can use it (up to its guest limit), and your computers work for visitors.
- **Phones on Wi-Fi:** in range of a working router, your phone uses it instead of mobile data (`/internet` and the speed test), and **Wi-Fi calling** lets you call and text with no tower in range.
- **Choosing a network:** `/broadband connect` lists the Wi-Fi in range (bars, provider, speed; click one to join). `/broadband connect <name>` joins it, `/broadband connect auto` lets your phone pick the best again, and `/broadband disconnect` puts you back on mobile data. The network you're connected to is what the sidebar shows. Your choice is remembered, and you rejoin when you walk back into range.
- **Speed test:** `/broadband speedtest` runs the same animated ping, download and upload test as `/cell speedtest`, over the Wi-Fi you're connected to. It uses the router's data allowance on a capped plan.
- **Home or business:** a router is a **home** by default. For a shop or office, open the router and click **Site** to make it a **business** site (cancel any plan first). Home sites only see residential plans on the Providers tab, and business sites only see business plans, which usually cost more but keep their speed on a busy node.
- **Wi-Fi extenders:** a router's Wi-Fi only reaches so far. Look at a wall sign inside the reach of your router and run `/broadband extender install [name]` (60 credits by default, up to 4). The extender repeats one of your routers, so its Wi-Fi reaches a further **70%** of the router's range from the extender's sign, at **60%** of the speed and a few ms more ping. Right-click it to see what it repeats, pick a router, rename it or remove it. It needs a router of yours with a working connection to reach it, and phones, computers and guests use it like any other Wi-Fi. The HUD shows the extender's name when you're connected through one.
- **Show the line** on the router screen draws the cable from the serving node to your house in sparks.

## The browser

On a computer, open **Browser**:

| Page | What |
|---|---|
| News | Headlines from the server |
| Marketplace | Post what you sell (3 listings, 7 days), contact sellers by mail |
| Mail | Read, reply and write to any player (30 messages kept) |

Pages take as long as your ping and speed make them, and use data on a capped plan.

## Commands

| Command | Description |
|---|---|
| `/broadband` (`/wifi`, `/bb`) | Open the router you look at (or your only one) |
| `/broadband router install [name]` | Make the wall sign you look at a router |
| `/broadband computer install [name]` | Make the wall sign you look at a computer |
| `/broadband extender install [name]` | Make the wall sign you look at a Wi-Fi extender |
| `/broadband providers` | Open the router's provider list |
| `/broadband connect [name\|auto]` · `disconnect` | List the Wi-Fi in range and join one, or leave Wi-Fi |
| `/broadband list` · `speedtest` · `remove` | Your devices, a test over the Wi-Fi you're on, remove what you look at |

Want to run a provider instead? See [Running an ISP](../isps.md).
