package dev.mccomm.cell.gui;

import dev.mccomm.cell.CellPlugin;
import dev.mccomm.cell.band.Band;
import dev.mccomm.cell.economy.BillingService;
import dev.mccomm.cell.economy.PartnerService;
import dev.mccomm.cell.model.Agreement;
import dev.mccomm.cell.model.Carrier;
import dev.mccomm.cell.model.LedgerEntry;
import dev.mccomm.cell.model.Plan;
import dev.mccomm.cell.model.PlayerProfile;
import dev.mccomm.cell.model.Subscription;
import dev.mccomm.cell.model.Tower;
import dev.mccomm.cell.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** /carrier manage: wallet and upkeep overview, ledger, deposit, plans (with editor), towers and staff. */
public final class CarrierMenu {
    private static final long CONFIRM_MS = 5000;

    private final CellPlugin plugin;
    private final Map<UUID, Long> confirm = new HashMap<>();

    public CarrierMenu(CellPlugin plugin) {
        this.plugin = plugin;
    }

    private void click(Player p) {
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.6f);
    }

    private Menu menu(int rows, String title) {
        return new Menu(rows, Msg.mm("<dark_gray>" + title), "carrier").fill(Items.of(Material.BLACK_STAINED_GLASS_PANE, "<gray> "));
    }

    private boolean isOwner(Player p, Carrier c) {
        return p.hasPermission("mccomm.admin") || c.owner().equals(p.getUniqueId());
    }

    public boolean canView(Player p, Carrier c) {
        return p.hasPermission("mccomm.admin") || c.isStaff(p.getUniqueId());
    }

    // ================================================================ overview

    public void open(Player p, Carrier c) {
        if (!canView(p, c)) {
            Msg.send(p, "<red>You don't work for <white><c></white>.", Msg.p("c", c.name()));
            return;
        }
        Menu m = menu(5, Items.esc(c.name()) + " · Management");
        long now = System.currentTimeMillis();
        List<Tower> towers = plugin.store().towersOf(c.id());
        long upkeep = plugin.billing().upkeepCost(c);
        int stage = plugin.billing().decayStage(c);

        m.set(4, Items.of(Material.BEACON, "<b>" + c.styled(),
                "<gray>Owner: <white>" + name(c.owner()),
                "<gray>Numbers: <white>" + c.prefix() + "-xxxx",
                c.mvno() ? "<gray>Type: <light_purple>MVNO (virtual)" : "<gray>Towers: <white>" + towers.size(),
                c.mvno() ? "<gray>Network: <white>" + mvnoNetworkName(c) : "<gray>Type: <white>Network operator"));

        List<String> wallet = new ArrayList<>();
        wallet.add("<gray>Upkeep: <white>" + plugin.settings().money(upkeep) + " <gray>every "
                + BillingService.period(plugin.settings().upkeepPeriodHours));
        wallet.add("<gray>Next upkeep in <white>" + BillingService.until(c.nextUpkeep() - now));
        if (upkeep > 0) wallet.add("<gray>Covers <white>" + (c.balance() / upkeep) + "</white> more period(s)");
        if (c.mvno()) {
            var deal = plugin.partners().mvnoHost(c);
            if (deal != null) wallet.add("<gray>Owed to " + Items.esc(deal.host().name()) + ": <white>" + plugin.settings().money(c.mvnoOwed())
                    + " <dark_gray>(" + Math.round(deal.agreement().sharePercent()) + "% share, settled with upkeep)");
            if (plugin.partners().suspended(c)) wallet.add("<dark_red>Your subscribers are suspended!");
        }
        if (c.balance() < upkeep) wallet.add("<gold>Not enough for the next upkeep!");
        m.set(10, Items.glow(Items.of(Material.GOLD_BLOCK, "<gold><b>" + plugin.settings().money(c.balance()), wallet)));

        m.set(12, Items.of(Material.HOPPER, "<green>Deposit ores", "<gray>Fund upkeep from your ores."), e -> {
            click(p);
            plugin.deposit().open(p, c, () -> open(p, c));
        });

        int subs = 0;
        double perDay = 0;
        for (PlayerProfile pr : plugin.store().profiles()) {
            Subscription sub = pr.sub(c.id());
            if (sub == null || sub.planId() == null) continue;
            Plan plan = c.plans().get(sub.planId());
            if (plan == null) continue;
            subs++;
            perDay += plan.price() * 24.0 / plan.periodHours();
        }
        double upkeepPerDay = upkeep * 24.0 / plugin.settings().upkeepPeriodHours;
        double net = perDay - upkeepPerDay;
        m.set(14, Items.of(Material.EMERALD, "<green>Income",
                "<gray>Paying subscribers: <white>" + subs,
                "<gray>Plan income: <green>+" + plugin.settings().money(Math.round(perDay)) + "</green> /day",
                "<gray>Upkeep: <red>-" + plugin.settings().money(Math.round(upkeepPerDay)) + "</red> /day",
                "<gray>Net: " + (net >= 0 ? "<green>+" : "<red>-") + plugin.settings().money(Math.round(Math.abs(net))) + " /day"));

        if (stage == 0) {
            m.set(16, Items.of(Material.LIME_DYE, "<green>Network healthy", "<gray>Upkeep is paid."));
        } else {
            boolean silent = stage >= plugin.settings().silentStage();
            List<String> lore = new ArrayList<>();
            lore.add(silent ? "<dark_red>Your towers are silent." : "<gray>Decay stage <white>" + stage + "</white> of " + (plugin.settings().silentStage() - 1));
            if (!silent) {
                lore.add("<gray>Speed: <white>" + Math.round(plugin.settings().decaySpeed[stage - 1] * 100) + "%");
                if (stage > 1) lore.add("<gray>Top <white>" + (stage - 1) + "</white> band(s) shut off");
            }
            lore.add("<gray>Unpaid for <white>" + BillingService.until(now - c.unpaidSince()));
            lore.add("");
            lore.add("<yellow>Deposit " + plugin.settings().money(upkeep) + "+ to repair everything");
            m.set(16, Items.glow(Items.of(silent ? Material.REDSTONE_BLOCK : Material.REDSTONE, silent ? "<dark_red>Towers silent" : "<red>Towers decaying", lore)));
        }

        double resale = plugin.settings().resalePercent;
        List<String> ti = new ArrayList<>();
        ti.add("<gray>Subscribers paying off a phone with you can");
        ti.add("<gray>trade in a phone they own: you credit them");
        ti.add("<white>" + Math.round(c.tradeInPercent()) + "%</white> <gray>of its price off their loan and");
        ti.add("<gray>resell it for <white>" + Math.round(resale) + "%</white>.");
        double tiNet = c.tradeInPercent() - resale;
        ti.add(tiNet > 0 ? "<gray>Costs you <red>" + Math.round(tiNet) + "%</red> of a phone's price per trade-in."
                : "<gray>Earns you <green>" + Math.round(-tiNet) + "%</green> of a phone's price per trade-in.");
        if (isOwner(p, c)) {
            ti.add("");
            ti.add("<yellow>Click <gray>turn " + (c.tradeIn() ? "off" : "on") + " · <yellow>Right-click <gray>set rate");
        }
        m.set(22, Items.of(c.tradeIn() ? Material.HOPPER_MINECART : Material.MINECART,
                "<white>Trade-in program: " + (c.tradeIn() ? "<green>on" : "<red>off"), ti), e -> {
            if (!isOwner(p, c)) return;
            click(p);
            if (e.isRightClick()) {
                askDouble(p, "<aqua>Trade-in rate in percent of the phone's price (0-100):", 0, 100, v -> {
                    c.tradeInPercent(v);
                    plugin.store().markDirty();
                    open(p, c);
                });
            } else {
                c.tradeIn(!c.tradeIn());
                plugin.store().markDirty();
                open(p, c);
            }
        });

        m.set(20, Items.of(Material.PAINTING, "<white>Branding: " + c.styled(),
                "<gray>" + dev.mccomm.cell.util.Style.describe(c.colors()),
                "<gray>Your name's colour or gradient everywhere:",
                "<gray>tower screens, phones, sidebar, /internet.",
                isOwner(p, c) ? "<yellow>Click to change" : "<dark_gray>Only the owner can change this"), e -> {
            if (!isOwner(p, c)) return;
            click(p);
            branding(p, "Branding · " + Items.esc(c.name()), c.name(), c.colors(), false, style -> {
                c.colors(style);
                plugin.store().markDirty();
                for (Tower t : plugin.store().towersOf(c.id())) plugin.towers().refresh(t);
            }, () -> open(p, c));
        });

        int offers = plugin.partners().offersTo(c).size();
        m.set(24, Items.of(Material.FILLED_MAP, "<white>Partners" + (offers > 0 ? " <yellow>(" + offers + " offer" + (offers == 1 ? "" : "s") + ")" : ""),
                "<gray>Roaming agreements" + (c.mvno() ? " and your host network." : " and MVNOs you host."),
                "<yellow>Click to open"), e -> {
            click(p);
            partners(p, c);
        });

        m.set(28, Items.of(Material.PAPER, "<white>Plans", "<gray>" + c.plans().size() + " plan(s)",
                isOwner(p, c) ? "<yellow>Click to create and edit plans" : "<gray>Click to view plans"), e -> {
            click(p);
            plans(p, c);
        });
        m.set(30, Items.of(Material.LIGHTNING_ROD, "<gold>Towers", "<gray>Status, decay and upkeep per tower."), e -> {
            click(p);
            towers(p, c);
        });
        List<String> hist = new ArrayList<>();
        for (LedgerEntry le : c.ledger()) {
            if (hist.size() >= 15) break;
            hist.add(plugin.phone().ledgerLine(le, now));
        }
        if (hist.isEmpty()) hist.add("<dark_gray>Nothing yet");
        m.set(32, Items.of(Material.BOOK, "<white>Ledger", hist));
        List<String> staff = new ArrayList<>();
        staff.add("<gray>Owner: <white>" + name(c.owner()));
        for (UUID id : c.employees()) staff.add("<gray>• <white>" + name(id));
        staff.add("");
        staff.add("<dark_gray>/carrier employee add|remove " + Items.esc(c.name()) + " <player>");
        m.set(34, Items.of(Material.PLAYER_HEAD, "<white>Staff", staff));

        if (!c.mvno()) {
            m.set(38, Items.of(Material.EMERALD, "<green>Tower shop", "<gray>Buy tower models from manufacturers,",
                    "<gray>one at a time or in bulk."), e -> {
                click(p);
                plugin.manufacturerMenu().shop(p, c);
            });
            int stock = plugin.manufacturerMenu().stockCount(c);
            m.set(42, Items.amount(Items.of(Material.CHEST, "<white>Tower stock", "<gray>" + stock + " tower(s) bought, not installed.",
                    "<gray>Install with <white>/tower install <name>"), Math.max(1, stock)), e -> {
                click(p);
                plugin.manufacturerMenu().stock(p, c);
            });
        }
        m.set(40, Items.of(Material.BARRIER, "<red>Close"), e -> p.closeInventory());
        m.open(p);
    }

    // =================================================================== plans

    public void plans(Player p, Carrier c) {
        boolean owner = isOwner(p, c);
        Menu m = menu(4, Items.esc(c.name()) + " · Plans");
        int slot = 0;
        for (Plan plan : new ArrayList<>(c.plans().values())) {
            if (slot >= 27) break;
            List<String> lore = new ArrayList<>(plugin.phone().planLore(plan));
            lore.add("<gray>Subscribers: <white>" + plugin.billing().subscribers(c, plan));
            if (owner) {
                lore.add("");
                lore.add("<yellow>Click <gray>edit · <yellow>Shift-right <gray>delete");
            }
            m.set(slot++, Items.of(Material.PAPER, "<b>" + plan.styled(c), lore), e -> {
                if (!owner) return;
                click(p);
                if (e.isShiftClick() && e.isRightClick()) deletePlan(p, c, plan);
                else editor(p, c, plan);
            });
        }
        m.set(27, Items.of(Material.ARROW, "<gray>« Back"), e -> {
            click(p);
            open(p, c);
        });
        if (owner) {
            m.set(31, Items.of(Material.WRITABLE_BOOK, "<green>New plan", "<gray>Starts as a copy of the default plan."), e -> {
                click(p);
                if (c.plans().size() >= 27) {
                    Msg.send(p, "<red>That's the maximum number of plans.");
                    return;
                }
                plugin.prompt().ask(p, "<aqua>Name for the new plan:", in -> {
                    Plan plan = plugin.billing().addDefaultPlan(c);
                    plan.name(clip(in, 24));
                    Msg.send(p, "<green>Plan <white><n></white> created. Set its price and limits.", Msg.p("n", plan.name()));
                    editor(p, c, plan);
                });
            });
        }
        m.open(p);
    }

    private void deletePlan(Player p, Carrier c, Plan plan) {
        long now = System.currentTimeMillis();
        Long until = confirm.get(p.getUniqueId());
        if (until == null || until < now) {
            confirm.put(p.getUniqueId(), now + CONFIRM_MS);
            Msg.send(p, "<yellow>Shift-right-click again within 5 seconds to delete <white><n></white>. Its <white><s></white> subscriber(s) will lose service until they pick another plan.",
                    Msg.p("n", plan.name()), Msg.p("s", "" + plugin.billing().subscribers(c, plan)));
            return;
        }
        confirm.remove(p.getUniqueId());
        c.plans().remove(plan.id());
        plugin.billing().planDeleted(c, plan);
        Msg.send(p, "<yellow>Deleted plan <white><n></white>.", Msg.p("n", plan.name()));
        plans(p, c);
    }

    public void editor(Player p, Carrier c, Plan plan) {
        Menu m = menu(6, "Edit · " + Items.esc(plan.name()));
        Plan before = plan.copy(); // state before this screen's edit, to tell subscribers what changed
        var st = plugin.settings();
        boolean prepaid = plan.prepaid();
        List<String> hdr = new ArrayList<>(plugin.phone().planLore(plan));
        int subCount = plugin.billing().subscribers(c, plan);
        if (subCount > 0) {
            hdr.add("");
            hdr.add("<gray>" + subCount + " subscriber(s). Changes in their favour");
            hdr.add("<gray>apply now; anything worse applies at");
            hdr.add("<gray>their next renewal. They're notified.");
        }
        m.set(4, Items.of(Material.PAPER, "<b>" + plan.styled(c), hdr));

        // ---- row 1: basics
        m.set(9, Items.of(Material.PAINTING, "<white>Style: " + plan.styled(c),
                "<gray>" + (plan.colors().isEmpty() ? "Uses the carrier's style" : dev.mccomm.cell.util.Style.describe(plan.colors())),
                "<yellow>Click to change"), e -> {
            click(p);
            branding(p, "Plan style · " + Items.esc(plan.name()), plan.name(), plan.colors(), true, style -> {
                plan.colors(style);
                plugin.store().markDirty();
            }, () -> editor(p, c, plan));
        });
        m.set(10, Items.of(Material.NAME_TAG, "<white>Name: <aqua>" + Items.esc(plan.name()), "<yellow>Click to rename"), e -> {
            click(p);
            plugin.prompt().ask(p, "<aqua>New name for the plan:", in -> {
                plan.name(clip(in, 24));
                saved(p, c, plan, before);
            });
        });
        String typeName = switch (plan.type()) {
            case POSTPAID -> "Postpaid";
            case FINANCED -> "Financed phone";
            case PREPAID -> "Prepaid";
        };
        m.set(11, Items.of(Material.COMPARATOR, "<white>Type: <aqua>" + typeName,
                "<gray>Postpaid: charged every period.", "<gray>Financed: you buy the subscriber a phone,",
                "<gray>  they repay it with each charge.", "<gray>Prepaid: they top up when they want; no debt.",
                "<yellow>Click to change"), e -> {
            click(p);
            int subs = plugin.billing().subscribers(c, plan);
            if (subs > 0) {
                Msg.send(p, "<red>This plan has <white><n></white> subscriber(s), so its type can't change. Create a new plan instead.",
                        Msg.p("n", "" + subs));
                return;
            }
            Plan.Type[] all = Plan.Type.values();
            plan.type(all[(plan.type().ordinal() + 1) % all.length]);
            if (plan.financed() && plan.financePhone() == null && !st.phones.isEmpty()) plan.financePhone(st.phones.keySet().iterator().next());
            saved(p, c, plan, before);
        });
        m.set(12, Items.of(Material.GOLD_INGOT, "<white>" + (prepaid ? "Top-up price" : "Price") + ": <gold>" + st.money(plan.price()),
                prepaid ? "<gray>Paid each time they top up." : "<gray>Charged every period.", "<yellow>Click to change"), e -> {
            click(p);
            askLong(p, "<aqua>" + (prepaid ? "Top-up price" : "Price per period") + " in " + st.currencyName + ":", 0, 1_000_000, v -> {
                plan.price(v);
                saved(p, c, plan, before);
            });
        });
        m.set(13, Items.of(Material.CLOCK, "<white>" + (prepaid ? "Valid for" : "Period") + ": <aqua>" + BillingService.period(plan.periodHours()),
                prepaid ? "<gray>How long a top-up lasts (real time)." : "<gray>How often subscribers are charged (real time).",
                "<yellow>Click to change"), e -> {
            click(p);
            askLong(p, "<aqua>Hours (" + st.minPeriodHours + "-" + st.maxPeriodHours + ", e.g. 24 = a day, 168 = a week):",
                    st.minPeriodHours, st.maxPeriodHours, v -> {
                        plan.periodHours(v.intValue());
                        saved(p, c, plan, before);
                    });
        });
        String bandName = plan.maxBand() == null ? "All bands" : st.spec(plan.maxBand()).name();
        m.set(14, Items.of(Material.LIGHTNING_ROD, "<white>Max band: <aqua>" + bandName,
                "<gray>Subscribers can't use bands above this,", "<gray>even with a better phone.",
                "<yellow>Click <gray>next · <yellow>Right-click <gray>previous"), e -> {
            click(p);
            Band[] all = Band.values();
            int idx = plan.maxBand() == null ? all.length : plan.maxBand().ordinal();
            idx = e.isRightClick() ? idx - 1 : idx + 1;
            if (idx < 0) idx = all.length;
            if (idx > all.length) idx = 0;
            plan.maxBand(idx == all.length ? null : all[idx]);
            saved(p, c, plan, before);
        });
        m.set(15, Items.of(Material.SUGAR, "<white>Speed cap: <aqua>" + (plan.speedCapMbps() > 0 ? Msg.speed(plan.speedCapMbps()) : "None"),
                "<gray>Download is limited to this.", "<yellow>Click to change <gray>(0 = no cap)"), e -> {
            click(p);
            askDouble(p, "<aqua>Speed cap in Mbps (0 for no cap):", 0, 100_000, v -> {
                plan.speedCapMbps(v);
                saved(p, c, plan, before);
            });
        });
        m.set(16, Items.of(plan.texts() ? Material.WRITABLE_BOOK : Material.BOOK, "<white>Texts: " + (plan.texts() ? "<green>included" : "<red>not included"),
                "<yellow>Click to toggle"), e -> {
            click(p);
            plan.texts(!plan.texts());
            saved(p, c, plan, before);
        });
        m.set(17, Items.of(plan.calls() ? Material.BELL : Material.NOTE_BLOCK, "<white>Calls: " + (plan.calls() ? "<green>included" : "<red>not included"),
                "<yellow>Click to toggle"), e -> {
            click(p);
            plan.calls(!plan.calls());
            saved(p, c, plan, before);
        });

        // ---- row 2: data
        m.set(19, Items.of(Material.REPEATER, "<white>Data: <aqua>" + (plan.unlimited() ? "Unlimited" : dev.mccomm.cell.Settings.data(plan.dataMB())),
                prepaid ? "<gray>Added with each top-up." : "<gray>Resets every period.",
                "<gray>/internet costs " + st.mbPerCharSend + " MB per character sent;", "<gray>a speedtest uses what it downloads.",
                "<yellow>Click to change <gray>(type 'unlimited' or GB)"), e -> {
            click(p);
            plugin.prompt().ask(p, "<aqua>Data in GB (e.g. 5 or 0.5), or <white>unlimited</white>:", in -> {
                if (in.toLowerCase(Locale.ROOT).startsWith("unl")) plan.dataMB(-1);
                else {
                    Double gb = parseDouble(in.toLowerCase(Locale.ROOT).replace("gb", ""));
                    if (gb == null || gb < 0 || gb > 100_000) {
                        Msg.send(p, "<red>Enter a number of GB or 'unlimited'.");
                        return;
                    }
                    plan.dataMB(Math.round(gb * 1000));
                }
                saved(p, c, plan, before);
            });
        });
        if (!plan.unlimited()) {
            String ood = switch (plan.outOfData()) {
                case THROTTLE -> "Slow down (" + Msg.speed(st.throttleMbps) + ")";
                case BLOCK -> "Block data";
                case OVERAGE -> "Charge per GB";
            };
            m.set(20, Items.of(Material.HOPPER, "<white>Out of data: <aqua>" + ood, "<yellow>Click to change"), e -> {
                click(p);
                Plan.OutOfData[] all = Plan.OutOfData.values();
                plan.outOfData(all[(plan.outOfData().ordinal() + 1) % all.length]);
                saved(p, c, plan, before);
            });
            if (plan.outOfData() == Plan.OutOfData.OVERAGE) {
                m.set(21, Items.of(Material.GOLD_NUGGET, "<white>Overage: <gold>" + st.money(plan.overagePerGb()) + " <white>per GB",
                        "<gray>Taken from their wallet as they use it.", "<yellow>Click to change"), e -> {
                    click(p);
                    askLong(p, "<aqua>Price per extra GB:", 0, 100_000, v -> {
                        plan.overagePerGb(v);
                        saved(p, c, plan, before);
                    });
                });
            }
            m.set(23, Items.of(Material.CHEST, "<white>Data pack: <aqua>" + (plan.packMB() > 0 ? dev.mccomm.cell.Settings.data(plan.packMB()) : "none"),
                    "<gray>An add-on subscribers can buy any time.", "<yellow>Click to change <gray>(0 = no packs)"), e -> {
                click(p);
                askDouble(p, "<aqua>Data pack size in GB (0 for none):", 0, 100_000, v -> {
                    plan.packMB(Math.round(v * 1000));
                    saved(p, c, plan, before);
                });
            });
            if (plan.packMB() > 0) {
                m.set(24, Items.of(Material.GOLD_INGOT, "<white>Pack price: <gold>" + st.money(plan.packPrice()), "<yellow>Click to change"), e -> {
                    click(p);
                    askLong(p, "<aqua>Data pack price:", 0, 1_000_000, v -> {
                        plan.packPrice(v);
                        saved(p, c, plan, before);
                    });
                });
            }
        }

        m.set(25, Items.of(plan.roaming() ? Material.COMPASS : Material.BARRIER, "<white>Roaming: " + (plan.roaming() ? "<green>allowed" : "<red>not allowed"),
                "<gray>Lets subscribers use partner networks", "<gray>where you have no coverage.", "<yellow>Click to toggle"), e -> {
            click(p);
            plan.roaming(!plan.roaming());
            saved(p, c, plan, before);
        });
        if (plan.roaming()) {
            m.set(26, Items.of(Material.GOLD_NUGGET, "<white>Roaming surcharge: <gold>" + (plan.roamingPerGb() > 0 ? st.money(plan.roamingPerGb()) + " <white>per GB" : "<green>free"),
                    "<gray>Charged to subscribers for data used", "<gray>while roaming. You pay partners their rate.", "<yellow>Click to change <gray>(0 = free)"), e -> {
                click(p);
                askLong(p, "<aqua>Roaming surcharge per GB (0 for free roaming):", 0, 100_000, v -> {
                    plan.roamingPerGb(v);
                    saved(p, c, plan, before);
                });
            });
        }

        // ---- row 3: financing
        if (plan.financed()) {
            dev.mccomm.cell.economy.PhoneModel ph = st.phone(plan.financePhone());
            m.set(28, Items.of(ph == null ? Material.BARRIER : ph.icon(), "<white>Phone: <light_purple>" + (ph == null ? "none" : Items.esc(ph.name())),
                    ph == null ? "<red>Pick a phone" : "<gray>Retail " + st.money(ph.price()) + ", paid from your wallet",
                    "<gray>when someone signs up.", "<yellow>Click <gray>next · <yellow>Right-click <gray>previous"), e -> {
                click(p);
                List<String> ids = new ArrayList<>(st.phones.keySet());
                if (ids.isEmpty()) return;
                int idx = Math.max(0, ids.indexOf(plan.financePhone()));
                idx = (idx + (e.isRightClick() ? ids.size() - 1 : 1)) % ids.size();
                plan.financePhone(ids.get(idx));
                saved(p, c, plan, before);
            });
            m.set(29, Items.of(Material.REPEATER, "<white>Instalments: <aqua>" + plan.installments(), "<gray>Number of periods to repay over.", "<yellow>Click to change"), e -> {
                click(p);
                askLong(p, "<aqua>Number of instalments (1-60):", 1, 60, v -> {
                    plan.installments(v.intValue());
                    saved(p, c, plan, before);
                });
            });
            m.set(30, Items.of(Material.EXPERIENCE_BOTTLE, "<white>Interest: <aqua>" + plan.interestPercent() + "%", "<gray>Added to the phone price.", "<yellow>Click to change"), e -> {
                click(p);
                askDouble(p, "<aqua>Interest in percent (0-200):", 0, 200, v -> {
                    plan.interestPercent(v);
                    saved(p, c, plan, before);
                });
            });
            m.set(31, Items.of(Material.EMERALD, "<white>Down payment: <gold>" + st.money(plan.downPayment()), "<gray>Paid up front at sign-up.", "<yellow>Click to change"), e -> {
                click(p);
                askLong(p, "<aqua>Down payment:", 0, 1_000_000, v -> {
                    plan.downPayment(v);
                    saved(p, c, plan, before);
                });
            });
            if (ph != null) {
                long total = plan.financeTotal(ph.price());
                m.set(33, Items.of(Material.BOOK, "<white>Phone deal",
                        "<gray>You pay: <red>" + st.money(ph.price()) + " <gray>at sign-up",
                        "<gray>They repay: <green>" + st.money(total + plan.downPayment()) + " <gray>total",
                        "<gray>  " + st.money(plan.downPayment()) + " down + " + st.money(plan.installment(ph.price())) + " × " + plan.installments(),
                        "<gray>If they default the rest comes due at once",
                        "<gray>and their phone is blacklisted.",
                        c.balance() < ph.price() ? "<gold>Your wallet can't cover a phone right now!" : "<green>Your wallet covers " + (c.balance() / Math.max(1, ph.price())) + " phone(s)"));
            }
        }

        m.set(45, Items.of(Material.ARROW, "<gray>« Plans"), e -> {
            click(p);
            plans(p, c);
        });
        m.open(p);
    }

    private void askLong(Player p, String q, long min, long max, java.util.function.Consumer<Long> ok) {
        plugin.prompt().ask(p, q, in -> {
            Long v = parseLong(in);
            if (v == null || v < min || v > max) {
                Msg.send(p, "<red>Enter a whole number from " + min + " to " + max + ".");
                return;
            }
            ok.accept(v);
        });
    }

    private void askDouble(Player p, String q, double min, double max, java.util.function.Consumer<Double> ok) {
        plugin.prompt().ask(p, q, in -> {
            Double v = parseDouble(in);
            if (v == null || v < min || v > max) {
                Msg.send(p, "<red>Enter a number from " + (long) min + " to " + (long) max + ".");
                return;
            }
            ok.accept(v);
        });
    }

    private void saved(Player p, Carrier c, Plan plan, Plan before) {
        plugin.store().markDirty();
        plugin.billing().planEdited(c, before, plan);
        editor(p, c, plan);
    }

    // ================================================================ partners

    private String mvnoNetworkName(Carrier c) {
        var deal = plugin.partners().mvnoHost(c);
        return deal == null ? "<red>none" : deal.host().styled();
    }

    public void partners(Player p, Carrier c) {
        boolean owner = isOwner(p, c);
        Menu m = menu(6, Items.esc(c.name()) + " · Partners");
        long now = System.currentTimeMillis();
        m.set(4, Items.of(Material.FILLED_MAP, "<b>" + c.styled(),
                c.mvno() ? "<light_purple>MVNO: you run on a host network." : "<gray>Network operator: you own towers.",
                "<gray>Roaming: your subscribers use partners'", "<gray>towers where you have no coverage.",
                c.mvno() ? "<gray>Host: their towers are your home network." : "<gray>MVNOs: virtual carriers that rent your network."));

        // Row 1-2: active agreements (as host, then as partner)
        int slot = 9;
        for (Agreement a : new ArrayList<>(c.agreements().values())) {
            if (slot > 26) break;
            Carrier partner = plugin.store().carrier(a.partnerId());
            if (partner == null) continue;
            PartnerService.Deal deal = new PartnerService.Deal(c, a);
            List<String> lore = new ArrayList<>(terms(a));
            lore.add(a.kind() == Agreement.Kind.MVNO ? "<gray>They pay you a revenue share." : "<gray>They pay you per GB their roamers use.");
            if (a.endsAt() != 0) lore.add("<gold>Ends in " + BillingService.until(a.endsAt() - now));
            if (owner) {
                lore.add("");
                lore.add("<yellow>Click <gray>edit terms · <yellow>Shift-right <gray>end");
            }
            m.set(slot++, Items.of(a.kind() == Agreement.Kind.MVNO ? Material.ENDER_EYE : Material.COMPASS,
                    "<white>" + (a.kind() == Agreement.Kind.MVNO ? "Hosting MVNO " : "Roaming partner ") + partner.styled(), lore), e -> {
                if (!owner) return;
                click(p);
                if (e.isShiftClick() && e.isRightClick()) confirmEnd(p, c, deal);
                else terms(p, c, a, () -> partners(p, c));
            });
        }
        for (PartnerService.Deal deal : plugin.partners().asPartner(c)) {
            if (slot > 26) break;
            Agreement a = deal.agreement();
            List<String> lore = new ArrayList<>(terms(a));
            lore.add(a.kind() == Agreement.Kind.MVNO ? "<gray>Their towers are your home network." : "<gray>Your subscribers roam here when you have no coverage.");
            if (a.endsAt() != 0) lore.add("<gold>Ends in " + BillingService.until(a.endsAt() - now));
            if (owner) {
                lore.add("");
                lore.add("<yellow>Shift-right <gray>end");
            }
            m.set(slot++, Items.of(a.kind() == Agreement.Kind.MVNO ? Material.BEACON : Material.MAP,
                    "<white>" + (a.kind() == Agreement.Kind.MVNO ? "Your host " : "Roaming on ") + deal.host().styled(), lore), e -> {
                if (!owner || !(e.isShiftClick() && e.isRightClick())) return;
                click(p);
                confirmEnd(p, c, deal);
            });
        }
        if (slot == 9) m.set(13, Items.of(Material.LIGHT_GRAY_DYE, "<gray>No agreements yet"));

        // Row 3: offers received and offers sent
        slot = 27;
        for (PartnerService.Deal offer : plugin.partners().offersTo(c)) {
            if (slot > 35) break;
            List<String> lore = new ArrayList<>(terms(offer.agreement()));
            if (owner) {
                lore.add("");
                lore.add("<yellow>Click <gray>accept · <yellow>Shift-right <gray>decline");
            }
            m.set(slot++, Items.glow(Items.of(Material.WRITABLE_BOOK, "<yellow>Offer from " + offer.host().styled()
                    + " <gray>(" + (offer.agreement().kind() == Agreement.Kind.MVNO ? "MVNO hosting" : "roaming") + ")", lore)), e -> {
                if (!owner) return;
                click(p);
                if (e.isShiftClick() && e.isRightClick()) plugin.partners().decline(p, offer);
                else plugin.partners().accept(p, c, offer);
                partners(p, c);
            });
        }
        for (Agreement a : new ArrayList<>(c.offers().values())) {
            if (slot > 35) break;
            Carrier partner = plugin.store().carrier(a.partnerId());
            if (partner == null) continue;
            List<String> lore = new ArrayList<>(terms(a));
            lore.add("<gray>Waiting for them to accept.");
            if (owner) {
                lore.add("");
                lore.add("<yellow>Click <gray>edit terms · <yellow>Shift-right <gray>withdraw");
            }
            m.set(slot++, Items.of(Material.PAPER, "<gray>Offer sent to " + partner.styled()
                    + " <gray>(" + (a.kind() == Agreement.Kind.MVNO ? "MVNO hosting" : "roaming") + ")", lore), e -> {
                if (!owner) return;
                click(p);
                if (e.isShiftClick() && e.isRightClick()) {
                    c.offers().remove(a.key());
                    plugin.store().markDirty();
                    partners(p, c);
                } else terms(p, c, a, () -> partners(p, c));
            });
        }

        m.set(45, Items.of(Material.ARROW, "<gray>« Back"), e -> {
            click(p);
            open(p, c);
        });
        if (owner && !c.mvno()) {
            m.set(48, Items.of(Material.COMPASS, "<green>Offer roaming", "<gray>Let another carrier's subscribers", "<gray>use your towers, for a fee per GB."), e -> {
                click(p);
                plugin.prompt().ask(p, "<aqua>Offer roaming to which carrier?", in -> {
                    Carrier other = plugin.store().carrier(in);
                    if (other == null) {
                        Msg.send(p, "<red>No carrier named <white><n></white>.", Msg.p("n", in));
                        return;
                    }
                    Agreement a = plugin.partners().offer(p, c, Agreement.Kind.ROAMING, other);
                    if (a != null) {
                        Msg.send(p, "<green>Offer sent to <white><n></white>. Adjust the terms until they accept.", Msg.p("n", other.name()));
                        terms(p, c, a, () -> partners(p, c));
                    }
                });
            });
            m.set(50, Items.of(Material.ENDER_EYE, "<green>Offer MVNO hosting", "<gray>Let a virtual carrier run on your", "<gray>network for a share of its revenue."), e -> {
                click(p);
                plugin.prompt().ask(p, "<aqua>Host which MVNO?", in -> {
                    Carrier other = plugin.store().carrier(in);
                    if (other == null) {
                        Msg.send(p, "<red>No carrier named <white><n></white>.", Msg.p("n", in));
                        return;
                    }
                    Agreement a = plugin.partners().offer(p, c, Agreement.Kind.MVNO, other);
                    if (a != null) {
                        Msg.send(p, "<green>Offer sent to <white><n></white>. Adjust the terms until they accept.", Msg.p("n", other.name()));
                        terms(p, c, a, () -> partners(p, c));
                    }
                });
            });
        }
        m.open(p);
    }

    private List<String> terms(Agreement a) {
        var st = plugin.settings();
        List<String> lore = new ArrayList<>();
        if (a.kind() == Agreement.Kind.ROAMING) lore.add("<gray>Rate: <white>" + st.money(a.perGb()) + " per GB");
        else lore.add("<gray>Revenue share: <white>" + Math.round(a.sharePercent()) + "%");
        lore.add("<gray>Max band: <white>" + (a.maxBand() == null ? "All" : st.spec(a.maxBand()).name()));
        lore.add("<gray>Priority: <white>" + (a.deprioritize() ? "below the host's customers" : "equal"));
        return lore;
    }

    private void confirmEnd(Player p, Carrier c, PartnerService.Deal deal) {
        long now = System.currentTimeMillis();
        Long until = confirm.get(p.getUniqueId());
        if (until == null || until < now) {
            confirm.put(p.getUniqueId(), now + CONFIRM_MS);
            Msg.send(p, "<yellow>Shift-right-click again within 5 seconds to end this agreement. It keeps working for "
                    + plugin.settings().noticeHours + " h.");
            return;
        }
        confirm.remove(p.getUniqueId());
        plugin.partners().end(p, deal);
        partners(p, c);
    }

    /** Host-side terms editor (works on an offer or an active agreement). */
    public void terms(Player p, Carrier host, Agreement a, Runnable back) {
        Carrier partner = plugin.store().carrier(a.partnerId());
        Menu m = menu(3, "Terms · " + Items.esc(partner == null ? "?" : partner.name()));
        var st = plugin.settings();
        m.set(4, Items.of(a.kind() == Agreement.Kind.MVNO ? Material.ENDER_EYE : Material.COMPASS,
                "<white>" + (a.kind() == Agreement.Kind.MVNO ? "MVNO hosting for " : "Roaming for ") + (partner == null ? "?" : partner.styled()), terms(a)));
        if (a.kind() == Agreement.Kind.ROAMING) {
            m.set(10, Items.of(Material.GOLD_INGOT, "<white>Rate: <gold>" + st.money(a.perGb()) + " <white>per GB",
                    "<gray>Paid by their carrier for data", "<gray>their roamers use on your towers.", "<yellow>Click to change"), e -> {
                click(p);
                askLong(p, "<aqua>Wholesale price per GB:", 0, 100_000, v -> {
                    a.perGb(v);
                    plugin.store().markDirty();
                    terms(p, host, a, back);
                });
            });
        } else {
            m.set(10, Items.of(Material.GOLD_INGOT, "<white>Revenue share: <gold>" + Math.round(a.sharePercent()) + "%",
                    "<gray>Your cut of every plan payment,", "<gray>top-up, data pack and overage they collect.", "<yellow>Click to change"), e -> {
                click(p);
                askDouble(p, "<aqua>Revenue share in percent (0-90):", 0, 90, v -> {
                    a.sharePercent(v);
                    plugin.store().markDirty();
                    terms(p, host, a, back);
                });
            });
        }
        m.set(12, Items.of(Material.LIGHTNING_ROD, "<white>Max band: <aqua>" + (a.maxBand() == null ? "All bands" : st.spec(a.maxBand()).name()),
                "<gray>Their users can't go above this on your towers.", "<yellow>Click <gray>next · <yellow>Right-click <gray>previous"), e -> {
            click(p);
            Band[] all = Band.values();
            int idx = a.maxBand() == null ? all.length : a.maxBand().ordinal();
            idx = e.isRightClick() ? idx - 1 : idx + 1;
            if (idx < 0) idx = all.length;
            if (idx > all.length) idx = 0;
            a.maxBand(idx == all.length ? null : all[idx]);
            plugin.store().markDirty();
            terms(p, host, a, back);
        });
        m.set(14, Items.of(a.deprioritize() ? Material.HOPPER : Material.CHEST, "<white>Priority: <aqua>" + (a.deprioritize() ? "Below your customers" : "Equal"),
                "<gray>When a band is busy, deprioritized users", "<gray>get " + Math.round(st.deprioritizedSpeed * 100) + "% of the speed.", "<yellow>Click to toggle"), e -> {
            click(p);
            a.deprioritize(!a.deprioritize());
            plugin.store().markDirty();
            terms(p, host, a, back);
        });
        m.set(18, Items.of(Material.ARROW, "<gray>« Back"), e -> {
            click(p);
            back.run();
        });
        m.open(p);
    }

    // ================================================================ branding

    /**
     * Pick a solid colour, a preset gradient or type a custom style. {@code allowReset} adds a
     * "use carrier style" option (for plans, where an empty style means inherit).
     */
    public void branding(Player p, String title, String sample, List<net.kyori.adventure.text.format.TextColor> current,
                         boolean allowReset, java.util.function.Consumer<List<net.kyori.adventure.text.format.TextColor>> apply, Runnable back) {
        Menu m = menu(6, title);
        m.set(4, Items.glow(Items.of(Material.NAME_TAG, "<b>" + dev.mccomm.cell.util.Style.mm(current, sample),
                "<gray>Current: <white>" + dev.mccomm.cell.util.Style.describe(current))));
        int[] solidSlots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
        int i = 0;
        for (dev.mccomm.cell.util.Style.Solid sol : dev.mccomm.cell.util.Style.SOLIDS) {
            if (i >= solidSlots.length) break;
            List<net.kyori.adventure.text.format.TextColor> style = List.of(sol.color());
            m.set(solidSlots[i++], Items.of(sol.swatch(), "<b>" + dev.mccomm.cell.util.Style.mm(style, sample),
                    "<gray>" + sol.name() + " · solid", "<yellow>Click to use"), e -> {
                click(p);
                apply.accept(style);
                branding(p, title, sample, style, allowReset, apply, back);
            });
        }
        int[] gradSlots = {28, 29, 30, 31, 32, 33, 34, 37, 38, 39};
        i = 0;
        for (var g : dev.mccomm.cell.util.Style.GRADIENTS.entrySet()) {
            if (i >= gradSlots.length) break;
            List<net.kyori.adventure.text.format.TextColor> style = g.getValue();
            m.set(gradSlots[i++], Items.of(Material.FIREWORK_STAR, "<b>" + dev.mccomm.cell.util.Style.mm(style, sample),
                    "<gray>" + g.getKey() + " · gradient", "<yellow>Click to use"), e -> {
                click(p);
                apply.accept(style);
                branding(p, title, sample, style, allowReset, apply, back);
            });
        }
        m.set(41, Items.of(Material.WRITABLE_BOOK, "<aqua>Custom style",
                "<gray>Type 1 colour for solid, 2-4 for a gradient:", "<white>#33ccff", "<white>#ff5f6d #ffc371", "<white>red gold aqua",
                "<yellow>Click to type"), e -> {
            click(p);
            plugin.prompt().ask(p, "<aqua>Colours (hex or names), 1 for solid or 2-4 for a gradient:", in -> {
                List<net.kyori.adventure.text.format.TextColor> style = dev.mccomm.cell.util.Style.parse(in);
                if (style == null) {
                    Msg.send(p, "<red>Couldn't read that. Try <white>#ff5f6d #ffc371</white> or <white>aqua</white>.");
                    branding(p, title, sample, current, allowReset, apply, back);
                    return;
                }
                apply.accept(style);
                Msg.send(p, "<green>Style set: <b>" + dev.mccomm.cell.util.Style.mm(style, sample));
                branding(p, title, sample, style, allowReset, apply, back);
            });
        });
        if (allowReset) {
            m.set(43, Items.of(Material.BARRIER, "<white>Use the carrier's style", "<gray>Clears this plan's own colours."), e -> {
                click(p);
                apply.accept(List.of());
                back.run();
            });
        }
        m.set(45, Items.of(Material.ARROW, "<gray>« Back"), e -> {
            click(p);
            back.run();
        });
        m.open(p);
    }

    // ================================================================== towers

    public void towers(Player p, Carrier c) {
        Menu m = menu(6, Items.esc(c.name()) + " · Towers");
        int stage = plugin.billing().decayStage(c);
        int slot = 0;
        for (Tower t : plugin.store().towersOf(c.id())) {
            if (slot >= 45) break;
            String world = t.world() == null ? "?" : t.world().getName();
            List<String> lore = new ArrayList<>();
            var maker = plugin.store().manufacturer(t.manufacturerId());
            lore.add("<gray>Model: <white>" + Items.esc(plugin.towers().modelName(t)) + " <dark_gray>(Gen " + t.generation() + ")");
            lore.add("<gray>Made by: " + (maker == null ? "<white>?" : maker.styled()));
            lore.add("<gray>Backhaul: <white>" + (t.backhaul() == null ? "<red>none" : DepositMenu.TowerGuiNames.pretty(t.backhaul())));
            lore.add("<gray>Height: <white>" + Math.round(t.heightAboveGround()) + " blocks");
            lore.add("<gray>Users now: <white>" + plugin.signal().usersOn(t));
            lore.add("<gray>Upkeep: <white>" + plugin.settings().money(plugin.settings().upkeepCost(t)) + " <gray>per "
                    + BillingService.period(plugin.settings().upkeepPeriodHours));
            lore.add("<gray>Location: <white>" + world + " " + t.x() + ", " + t.y() + ", " + t.z());
            String status = !t.online() ? "<red>Offline" : stage >= plugin.settings().silentStage() ? "<dark_red>Silent (unpaid)"
                    : stage > 0 ? "<gold>Decaying (stage " + stage + ")" : "<green>Online";
            lore.add(status);
            Material icon = !t.online() || stage >= plugin.settings().silentStage() ? Material.IRON_BARS : Material.LIGHTNING_ROD;
            m.set(slot++, Items.of(icon, "<gold>" + Items.esc(t.name()), lore));
        }
        if (slot == 0) m.set(22, Items.of(Material.LIGHT_GRAY_DYE, "<gray>No towers yet",
                "<dark_gray>Look at a wall sign: /tower create " + Items.esc(c.name()) + " <name>"));
        m.set(45, Items.of(Material.ARROW, "<gray>« Back"), e -> {
            click(p);
            open(p, c);
        });
        m.set(49, Items.of(Material.GOLD_NUGGET, "<gold>Total upkeep: " + plugin.settings().money(plugin.billing().upkeepCost(c)),
                "<gray>per " + BillingService.period(plugin.settings().upkeepPeriodHours)));
        m.open(p);
    }

    // ================================================================= helpers

    private static String clip(String s, int n) {
        return s.length() > n ? s.substring(0, n) : s;
    }

    private static Long parseLong(String s) {
        try {
            return Long.parseLong(s.replace(",", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double parseDouble(String s) {
        try {
            return Double.parseDouble(s.replace(",", "").trim().toLowerCase(Locale.ROOT).replace("mbps", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String name(UUID id) {
        String n = Bukkit.getOfflinePlayer(id).getName();
        return n == null ? id.toString().substring(0, 8) : n;
    }
}
