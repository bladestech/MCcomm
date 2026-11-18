# Roaming and MVNOs

Open the **Partners** tab of **`/carrier manage`**. Agreements are always offered by the **host** (the carrier with the towers) and accepted by the **partner**.

## Roaming agreements

A host lets another carrier's subscribers use its towers **where the partner's own network has no coverage**.

**Making a deal:**

1. The host clicks **Offer roaming** and names the carrier.
2. The host sets the **terms**. They stay editable until the partner accepts:
    - **Rate**: credits per GB the partner pays for data its roamers use.
    - **Max band**: roamers can't go above this on your towers.
    - **Priority**: deprioritized roamers get 50% speed on a busy band.
3. The partner gets a notice and **clicks to accept** (shift-right-click to decline) in its Partners page.

**How it works for subscribers:**

- When they have no home coverage, their phone uses the best partner tower. The sidebar shows **R** and the partner's name.
- Only if their **plan allows roaming**. The plan can also charge a per-GB surcharge.
- The **home carrier** pays the host per GB used. If the home carrier's wallet is empty, roaming stops.
- Only data is charged; calls and texts are free.

## MVNOs

An **MVNO** is a virtual carrier with **no towers** (`/carrier create <name> mvno`). It runs on a host network.

1. A network carrier clicks **Offer MVNO hosting** and names the MVNO, then sets:
    - **Revenue share**: the host's cut of the MVNO's plan income.
    - **Max band** and **Priority**, as for roaming.
2. The MVNO accepts in its Partners page. An MVNO can only have one host.

**What it means:**

- The MVNO's subscribers use the host's towers **as their home network**: no roaming marker, and the MVNO's own branding, plans and prices.
- The host's share builds up from every plan payment, top-up, data pack and overage the MVNO collects, but not phone instalments. It's settled from the MVNO's wallet with upkeep.
- If the MVNO can't pay, its subscribers keep service for **24 hours**, then show **Network suspended** until it pays.
- If the host network decays or goes silent, the MVNO's users feel it too.
- If the host is liquidated, the MVNO gets **farewell money** (see [Companies and economy](../economy.md#liquidating-a-carrier)) and needs a new host.

## Ending an agreement

Either side shift-right-clicks it twice. It keeps working for a **24-hour notice period**, then ends, and both owners are notified.
