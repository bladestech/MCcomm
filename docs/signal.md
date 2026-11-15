# Signal reference

How MCcomm decides your band, bars and speed. All numbers below are the defaults from `config.yml`.

## Bands

| Band | Range | Peak speed | Ping | Needs generation |
|---|---|---|---|---|
| 2G | 3,000 | 0.2 Mbps | 320 ms | 1 |
| 3G | 2,400 | 8 Mbps | 120 ms | 1 |
| 4G | 1,200 | 100 Mbps | 45 ms | 2 |
| 4G+ | 950 | 300 Mbps | 32 ms | 3 |
| 5G Low | 1,000 | 250 Mbps | 25 ms | 3 |
| 5G Mid | 550 | 1 Gbps | 18 ms | 4 |
| 5G High | 275 | 2 Gbps | 12 ms | 4 |
| 5G mmWave | 90 | 4 Gbps | 6 ms | 5 |

Ranges are in blocks, before the [height bonus](carriers/towers.md#height-bonus) (up to +50%). The peak speed assumes a Netherite backhaul and perfect signal.

## Which band you get

For each SIM, MCcomm looks at every working tower of your network and picks the **highest band** that still arrives above the cutoff (−115 dBm). If two give the same band, it takes the stronger signal. A band is skipped if:

- the tower doesn't have it enabled, or its model doesn't include it;
- your **phone** or **plan** can't use it;
- you're roaming or on an MVNO and the host caps it;
- the tower is decaying and that band has been shut off.

If your network has nothing, partner networks are tried ([roaming](carriers/partners.md)).

## Signal strength (dBm)

```
dBm = -50 - 65 × (d / r) - walls - environment
```

- **d** is the 3D distance from your eyes to the antenna (1.5 blocks above the sign). **r** is the band's range × height bonus.
- **walls**: solid blocks on the line towards the tower, counted **only within 6 blocks of you** (at most 6), × the band's loss per block, capped per band:

| Band | 2G | 3G | 4G | 4G+ | 5G Low | 5G Mid | 5G High | mmWave |
|---|---|---|---|---|---|---|---|---|
| dB per block | 0.5 | 0.75 | 1 | 1.25 | 1.25 | 2 | 3 | 10 |
| Cap (dB) | 6 | 8 | 10 | 10 | 10 | 14 | 18 | 45 |

  Hills and terrain between you and the tower are **ignored**: only the walls of the building you're in count. mmWave still mostly needs line of sight.
- **environment**: rain −2 dB, thunder −4 dB. Underground, the first **8 blocks** below the surface are free, then −0.4 dB per block, up to −18 dB.

**Bars:** 4 bars at −75 dBm or better, 3 at −88 or better, 2 at −101 or better, 1 down to the −115 cutoff.

## Speed

```
speed = peak × backhaul × signal factor × share × decay
```

- **backhaul**: 100% Netherite down to 10% Copper.
- **signal factor**: 15% at the cutoff, rising linearly to 100% at −50 dBm.
- **share**: 1 until the band's load passes its capacity, then capacity ÷ load. Capacity is 4/6/9/13/18 users per band for Gen 1–5. Load = real users + simulated users ÷ 500.
- **decay**: 100%, or 60/40/25% while upkeep is unpaid.

Then the result is limited by:

- the plan's **speed cap**;
- the **throttle** (0.5 Mbps) when out of data on a "slow down" plan;
- the **deprioritized** share (50%) for roamers or MVNO users on a busy band, if the host chose it.

Upload is the download × the band's upload ratio (0.2–0.5).

**Ping** is the band's base ping + distance ÷ 100 + up to one more base ping at weak signal.

## Data use

| Action | Data |
|---|---|
| Speedtest | (download × 2.5 s + upload × 2.5 s) ÷ 8, in MB. About 300 MB at 1 Gbps |
| `/internet` message, sending | 1 MB per character |
| `/internet` message, receiving | 0.1 MB per character |
| Texts and calls | None |
