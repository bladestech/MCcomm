# Data, backups and testing

## Data files

Everything is stored in `plugins/MCcomm/`:

| File | Contains |
|---|---|
| `config.yml` | Settings |
| `carriers.yml` | Carriers: wallets, plans, agreements, stock, ledgers |
| `towers.yml` | Towers: location, model, bands, backhaul, height |
| `players.yml` | Players: phones, wallets, SIMs, numbers, subscriptions, contacts, inbox, debts |
| `manufacturers.yml` | Manufacturers: research, models, wallets |
| `server.yml` | Server-wide state (the simulated revenue toggle) |

Data is saved every minute when something changed, on `/celladmin save`, and on shutdown.

!!! tip "Back up before updating"
    Copy the whole `plugins/MCcomm/` folder before installing a new version.

## Stopping the server

Stop the server with `stop` (or through RCON) so everything saves. Killing the process can lose up to a minute of plugin data.

## Fast-forwarding time

Billing runs on **real time**. To test what happens over days or weeks:

```
/celladmin timewarp <days>
```

This fast-forwards the economy in hourly steps, as if that time had passed: plan charges, low-balance warnings, overdue debt, upkeep, decay, MVNO settlement, agreement expiries and simulated revenue. Sixty days take a fraction of a second.

!!! warning
    Timewarp really charges and pays. Use it on a test server, not your live economy.

## Development

```bash
./gradlew build        # builds build/libs/MCcomm-<version>.jar
./gradlew runServer    # local Paper test server in run/ (port 25565)
./gradlew runFlat      # second, superflat test server in run-flat/ (port 25566)
```

Releases are built by GitHub Actions: publishing a GitHub release builds the jar with the tag's version and attaches it.
