# Getting started

## Install

1. Download `MCcomm-<version>.jar` from [GitHub Releases](https://github.com/bladestech/MCcomm/releases).
2. Put it in your server's `plugins/` folder. You need Paper or Purpur 26.1 or newer, running on Java 25.
3. Start the server. MCcomm creates `plugins/MCcomm/config.yml`.
4. Optional: install [squaremap](admin/squaremap.md) to see towers and coverage on a web map.

There's nothing else to install. Data is stored in YAML files in `plugins/MCcomm/`.

## Your first network in five minutes

The steps below use an op account (permission `mccomm.admin`), which skips limits and lets you hand out credits for testing.

### 1. Get some credits

Everything costs credits, and credits come from depositing ores. For a quick test, give yourself some:

```
/celladmin credits <you> 5000
```

On a real server, players deposit ores in **`/cell wallet` → Deposit ores**.

### 2. Buy a phone

```
/cell eshop
```

Buy a phone. The **Pilot+** supports every band and two SIMs. Without a phone you get no service at all.

### 3. Start a carrier

```
/carrier create NovaMobile
```

Open **`/carrier manage`** and put credits into the carrier's wallet: use **Company → Add from your wallet**, or deposit ores. The wallet pays for towers and their upkeep.

### 4. Buy and install a tower

In `/carrier manage`, open the **Tower shop**. The server-run **Generic** manufacturer always sells standard towers. Buy one, then place a **wall sign** on a tall pillar, look at it, and run:

```
/tower install NM1
```

Pick the model from your stock. The tower is **offline** until you right-click the sign and put a **backhaul block** (Netherite, Diamond, Gold, Iron or Copper block) in its slot. The backhaul block sets the tower's capacity.

### 5. Subscribe

```
/cell
```

Open the **Carrier store**, pick NovaMobile and choose a plan. You now have a phone number and service. The sidebar shows your signal, band and data.

!!! tip
    Run `/cell speedtest` to see your speed. Walk away from the tower and watch the band drop from 5G to 4G to 3G.

## Next steps

- Read [Running a carrier](carriers/index.md) to understand plans, upkeep and roaming.
- Tune prices and ranges in [Configuration](admin/config.md).
- Look up commands and permissions in [Commands and permissions](admin/commands.md).
