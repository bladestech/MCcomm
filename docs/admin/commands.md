# Commands and permissions

## Permissions

| Permission | Default | Allows |
|---|---|---|
| `mccomm.use` | everyone | `/cell`, `/text`, `/call`, `/internet`, `/carrier`, `/tower`, `/manufacturer`, `/isp`, `/broadband` |
| `mccomm.carrier.create` | everyone | Creating a carrier |
| `mccomm.isp.create` | everyone | Starting an ISP |
| `mccomm.simulate` | op | `/cellsim`: simulated users for roleplay |
| `mccomm.admin` | op | Managing every carrier, tower and manufacturer; no limits; free installs; `/celladmin`; the simulated revenue toggle |

## Players

| Command | Description |
|---|---|
| `/cell` (`/phone`) | Open the phone |
| `/cell status` | Your connection in chat |
| `/cell speedtest [slot]` | Run a speedtest |
| `/cell eshop` · `/cell wallet` | Buy phones · your wallet |
| `/cell subscribe <carrier>` | See a carrier's plans |
| `/cell plan <carrier>` | Pick or change a plan |
| `/cell unsubscribe <carrier>` | Unsubscribe (releases your number) |
| `/cell activate\|deactivate <carrier>` | Swap SIMs between active and inactive |
| `/cell data <carrier\|auto>` | Choose your data SIM |
| `/cell sims` · `/cell number` · `/cell towers` · `/cell carriers` | Info |
| `/cell contacts` · `/cell messages` | Open those apps |
| `/cell pay <player> <amount>` | Send credits (free) |
| `/cell hud` | Toggle the signal sidebar |
| `/text <number\|contact\|player> <message>` (`/sms`) | Send a text |
| `/call start <number\|contact\|player\|909>` | Call |
| `/call accept` · `/call deny` · `/call end` | Answer, decline, hang up |
| `/internet [message]` (`/net`) | Toggle internet chat, or send one message |

## Carriers

| Command | Description |
|---|---|
| `/carrier create <name> [mvno]` (`/telecom`) | Create a carrier (or an MVNO) |
| `/carrier manage [name]` | The management screen |
| `/carrier company [name]` | Valuation, transfers, sell, liquidate |
| `/carrier info [name]` · `/carrier list` | Info |
| `/carrier color <name> <colours...>` | Branding: a colour, a 2–4 colour gradient, or a preset |
| `/carrier employee add\|remove <name> <player>` | Staff |
| `/carrier delete <name> confirm` | Delete outright |

## Towers

| Command | Description |
|---|---|
| `/tower install <name> [carrier]` | Install a stocked tower on the wall sign you look at (`create` is an alias) |
| `/tower manage` · `/tower info` | GUI or info for the sign you look at |
| `/tower rename <name>` · `/tower remove` | Rename or remove it |
| `/tower list [carrier]` | List towers |
| `/tower setgen <gen>` | Admin: set the tower's generation |
| `/tower tp <name>` | Admin: teleport to a tower |

## Facilities

| Command | Description |
|---|---|
| `/facility create station\|callcentre\|noc <name> [carrier]` | Register the wall sign you look at as a facility |
| `/facility check <name> [carrier]` | Re-count its room now |
| `/facility remove <name> [carrier]` | Remove it (its staff go idle) |
| `/facility list [carrier]` | List a carrier's facilities |

## Manufacturers

| Command | Description |
|---|---|
| `/manufacturer create <name>` (`/mfr`) | Found a manufacturer |
| `/manufacturer manage [name]` | Research, models, wallet |
| `/manufacturer company [name]` | Valuation, transfers, sell, liquidate |
| `/manufacturer list` | All manufacturers |
| `/manufacturer color <name> <colours...>` | Branding |
| `/manufacturer employee add\|remove <name> <player>` | Staff |
| `/manufacturer delete <name> confirm` | Delete outright |

## Home internet

| Command | Description |
|---|---|
| `/broadband` (`/wifi`, `/bb`) | Open the router you look at |
| `/broadband router install [name]` | Make the wall sign you look at a router |
| `/broadband computer install [name]` | Make it a computer |
| `/broadband extender install [name]` | Make it a Wi-Fi extender that repeats your router (costs `broadband.wifi.extender.price`) |
| `/broadband connect [name\|auto]` · `disconnect` | List the Wi-Fi in range and join one (shown on the HUD), or leave Wi-Fi |
| `/broadband providers\|list\|speedtest\|remove` | Provider list, your devices, a Wi-Fi speed test (like `/cell speedtest`), remove a device |
| `/isp create <name> [carrier <carrier>]` | Start an ISP (one per player by default), or a carrier's 5G Home business |
| `/isp node repair <name> [isp]` | Pay a technician to fix a node fault |
| `/isp transit [isp]` | Buy a tier of backbone |
| `/isp peer offer\|accept\|end <isp> [your isp]` | Peering between ISPs |
| `/isp bundle offer\|accept\|end\|list` | Bundles with carriers |
| `/isp manage [isp]` | The ISP menu (or just `/isp`) |
| `/isp node install <dsl\|cable\|fiber\|satellite> <name> [isp]` | Build a node on the sign you look at |
| `/isp plan add <tech> <price> <mbps> <name...>` | Add a plan |
| `/isp plan delete <name...>` · `delete tech <tech>` · `list` | Delete plans (cancelling their subscribers), or list them |
| `/isp deposit\|withdraw <amount> [isp]` | Move credits in or out |
| `/isp info [isp]` · `/isp list` | Details · all ISPs |
| `/isp employee add\|remove <isp> <player>` | Staff |
| `/isp color <isp> <colors...>` | Name style |
| `/isp delete <isp> confirm` | Shut an ISP down |

See [Home internet](../players/home-internet.md) and [Running an ISP](../isps.md).

## Admin

| Command | Description |
|---|---|
| `/celladmin reload` | Reload `config.yml` and refresh everything |
| `/celladmin save` | Save data now (it also autosaves every minute) |
| `/celladmin credits <player> <amount>` | Add (or, if negative, take) credits from a player |
| `/celladmin carriercredits <carrier> <amount>` | Same for a carrier |
| `/celladmin givephone <player> <model>` | Give a phone (worth nothing on resale) |
| `/celladmin billing` | Run the billing check now |
| `/celladmin timewarp <days>` | Fast-forward the economy (see [Data, backups and testing](data.md)) |
| `/celladmin workforce [on\|off [reset]]` | Show, or switch, tower wear, technicians, staff and stations. `off reset` also clears all tower wear ([Operations](../carriers/operations.md)) |
| `/celladmin workforce reset` | Set every tower's wear to 0 and cancel all repair jobs |
| `/cellsim` | [Simulated users](simulation.md) |
