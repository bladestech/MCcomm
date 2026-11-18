# Design spec: Menu reorganization

Status: **built** (shared Frame, tabbed carrier and manufacturer menus, 8-app phone).

## Why

Hubs had grown to 15–17 items with three more levels underneath, and every screen
had its own layout. 33 screens in total.

## Decisions

- **Tabs across the top** for the big management screens.
- **Merge phone apps**: 15 → 8.
- **Short item text** (about 5 lines: key numbers and the click hint), with a **Help**
  book on each page for explanations.

## The shared frame

Every menu is 6 rows.

| Row | Content |
|---|---|
| Top (0–8) | Tabs (selected tab glows), or a title item on single pages; Close in slot 8 |
| Middle (9–44) | Content |
| Bottom (45–53) | Back (45), Prev page (48), page number (49), Next page (50), Help (53) |

- Lists longer than the content area get pages instead of being cut off.
- Click hints use one format: `Click · open`, `Right-click · edit`, `Shift-click · …`.
- One shared builder (`Frame`) for tabs, nav row, paging and help, so screens can't drift.

## Carrier management (`/carrier manage`)

Absorbs Operations, the Tower shop, Tower stock and the Company menu.

| Tab | Content |
|---|---|
| Overview | Name, balance, net per day, customers and share, health; alerts (worn towers, understaffing, unpaid upkeep, bankruptcy countdown, payday); quick actions (Deposit, Plans, Hire) |
| Network | Towers by wear (paged; click for details), Tower shop, Stock, Facilities, Contractors toggle |
| Customers | Market standing (satisfaction breakdown, churn), Plans (list and editor), Trade-in program, Branding |
| Staff | Technicians, Support, Engineers (each opens hiring and staff list), real-player employees |
| Money | Ledger, Deposit, Withdraw, Add from wallet, Valuation, Sell, Liquidate |
| Partners | Roaming and MVNO agreements, offers, terms |

## Manufacturer (`/manufacturer manage`)

| Tab | Content |
|---|---|
| Overview | Name, balance, research level, models, upkeep income; Branding; staff |
| Research | Generations and bands |
| Models | Model list and editor |
| Money | Ledger, Deposit, Withdraw, Add, Valuation, Sell, Liquidate |

## Phone (`/cell`)

Status bar on top (numbers, signal, clock). Eight apps:

| App | Merges |
|---|---|
| Messages | Inbox |
| Contacts | Contacts, add a contact |
| Dial | Dialer |
| Signal | Speedtest, nearby towers, current signal |
| SIMs and plans | SIM manager, carrier store, plans |
| Shop | eShop, selling phones, trade-ins |
| Wallet | Balance, deposit, send credits, overdue, history |
| Settings | Settings |

Plus **Work** (opens your carrier or factory, or asks which) and the emergency button.
Call controls (answer, decline, hang up) stay on the bottom row while a call is active.

## Unchanged in structure

Tower sign screen (`TowerGui`), facility pages, `/cellsim`, deposit menu: restyled with
the shared frame only.
