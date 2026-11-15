# squaremap

If [squaremap](https://modrinth.com/plugin/squaremap) is installed, MCcomm shows its network on the web map automatically. Without squaremap, nothing loads. Tested with squaremap 1.4.3 on Paper 26.3.

## Layers

| Layer | Shows |
|---|---|
| **Cell towers** | A mast icon per tower in its carrier's colour, grey when silent or offline. Hover or click for carrier, tower, model and maker, generation, bands, status, users (and simulated), backhaul and height bonus |
| **Coverage · <carrier>** | One per carrier: each working tower's furthest reach, including the height bonus, shaded in the carrier's colour |
| **Band · <band>** | One per band, each in its own colour: every tower's range on that band. **Hidden by default**; tick the bands you want |

Band colours: 2G grey, 3G orange, 4G yellow, 4G+ lime, 5G Low cyan, 5G Mid blue, 5G High purple, mmWave magenta.

Layers update every 30 seconds, so decay, band changes and new towers appear on their own.

## Settings

```yaml
squaremap:
  enabled: true
  update-seconds: 30
  icon-size: 20
  coverage: true                     # the per-carrier coverage layers
  coverage-style: max                # max = furthest band per tower; bands = a ring per band
  coverage-hidden-by-default: false
  band-layers: true                  # the per-band layers
  band-layers-hidden-by-default: true
```

Run `/celladmin reload` after changing them.
