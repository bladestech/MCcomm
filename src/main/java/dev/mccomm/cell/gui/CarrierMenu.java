package dev.mccomm.cell.gui;

import dev.mccomm.cell.CellPlugin;
import dev.mccomm.cell.band.Band;
import dev.mccomm.cell.economy.BillingService;
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
        String col = "<" + c.color().asHexString() + ">";
        Menu m = menu(5, Items.esc(c.name()) + " · Management");
        long now = System.currentTimeMillis();
        List<Tower> towers = plugin.store().towersOf(c.id());
        long upkeep = plugin.billing().upkeepCost(c);
        int stage = plugin.billing().decayStage(c);

        m.set(4, Items.of(Material.BEACON, col + "<b>" + Items.esc(c.name()),
                "<gray>Owner: <white>" + name(c.owner()),
                "<gray>Numbers: <white>" + c.prefix() + "-xxxx",
                "<gray>Towers: <white>" + towers.size()));

        List<String> wallet = new ArrayList<>();
        wallet.add("<gray>Upkeep: <white>" + plugin.settings().money(upkeep) + " <gray>every "
                + BillingService.period(plugin.settings().upkeepPeriodHours));
        wallet.add("<gray>Next upkeep in <white>" + BillingService.until(c.nextUpkeep() - now));
        if (upkeep > 0) wallet.add("<gray>Covers <white>" + (c.balance() / upkeep) + "</white> more period(s)");
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
            m.set(slot++, Items.of(Material.PAPER, "<" + c.color().asHexString() + "><b>" + Items.esc(plan.name()), lore), e -> {
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
        var st = plugin.settings();
        boolean prepaid = plan.prepaid();
        m.set(4, Items.of(Material.PAPER, "<" + c.color().asHexString() + "><b>" + Items.esc(plan.name()), plugin.phone().planLore(plan)));

        // ---- row 1: basics
        m.set(10, Items.of(Material.NAME_TAG, "<white>Name: <aqua>" + Items.esc(plan.name()), "<yellow>Click to rename"), e -> {
            click(p);
            plugin.prompt().ask(p, "<aqua>New name for the plan:", in -> {
                plan.name(clip(in, 24));
                saved(p, c, plan);
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
            Plan.Type[] all = Plan.Type.values();
            plan.type(all[(plan.type().ordinal() + 1) % all.length]);
            if (plan.financed() && plan.financePhone() == null && !st.phones.isEmpty()) plan.financePhone(st.phones.keySet().iterator().next());
            saved(p, c, plan);
        });
        m.set(12, Items.of(Material.GOLD_INGOT, "<white>" + (prepaid ? "Top-up price" : "Price") + ": <gold>" + st.money(plan.price()),
                prepaid ? "<gray>Paid each time they top up." : "<gray>Charged every period.", "<yellow>Click to change"), e -> {
            click(p);
            askLong(p, "<aqua>" + (prepaid ? "Top-up price" : "Price per period") + " in " + st.currencyName + ":", 0, 1_000_000, v -> {
                plan.price(v);
                saved(p, c, plan);
            });
        });
        m.set(13, Items.of(Material.CLOCK, "<white>" + (prepaid ? "Valid for" : "Period") + ": <aqua>" + BillingService.period(plan.periodHours()),
                prepaid ? "<gray>How long a top-up lasts (real time)." : "<gray>How often subscribers are charged (real time).",
                "<yellow>Click to change"), e -> {
            click(p);
            askLong(p, "<aqua>Hours (" + st.minPeriodHours + "-" + st.maxPeriodHours + ", e.g. 24 = a day, 168 = a week):",
                    st.minPeriodHours, st.maxPeriodHours, v -> {
                        plan.periodHours(v.intValue());
                        saved(p, c, plan);
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
            saved(p, c, plan);
        });
        m.set(15, Items.of(Material.SUGAR, "<white>Speed cap: <aqua>" + (plan.speedCapMbps() > 0 ? Msg.speed(plan.speedCapMbps()) : "None"),
                "<gray>Download is limited to this.", "<yellow>Click to change <gray>(0 = no cap)"), e -> {
            click(p);
            askDouble(p, "<aqua>Speed cap in Mbps (0 for no cap):", 0, 100_000, v -> {
                plan.speedCapMbps(v);
                saved(p, c, plan);
            });
        });
        m.set(16, Items.of(plan.texts() ? Material.WRITABLE_BOOK : Material.BOOK, "<white>Texts: " + (plan.texts() ? "<green>included" : "<red>not included"),
                "<yellow>Click to toggle"), e -> {
            click(p);
            plan.texts(!plan.texts());
            saved(p, c, plan);
        });
        m.set(17, Items.of(plan.calls() ? Material.BELL : Material.NOTE_BLOCK, "<white>Calls: " + (plan.calls() ? "<green>included" : "<red>not included"),
                "<yellow>Click to toggle"), e -> {
            click(p);
            plan.calls(!plan.calls());
            saved(p, c, plan);
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
                saved(p, c, plan);
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
                saved(p, c, plan);
            });
            if (plan.outOfData() == Plan.OutOfData.OVERAGE) {
                m.set(21, Items.of(Material.GOLD_NUGGET, "<white>Overage: <gold>" + st.money(plan.overagePerGb()) + " <white>per GB",
                        "<gray>Taken from their wallet as they use it.", "<yellow>Click to change"), e -> {
                    click(p);
                    askLong(p, "<aqua>Price per extra GB:", 0, 100_000, v -> {
                        plan.overagePerGb(v);
                        saved(p, c, plan);
                    });
                });
            }
            m.set(23, Items.of(Material.CHEST, "<white>Data pack: <aqua>" + (plan.packMB() > 0 ? dev.mccomm.cell.Settings.data(plan.packMB()) : "none"),
                    "<gray>An add-on subscribers can buy any time.", "<yellow>Click to change <gray>(0 = no packs)"), e -> {
                click(p);
                askDouble(p, "<aqua>Data pack size in GB (0 for none):", 0, 100_000, v -> {
                    plan.packMB(Math.round(v * 1000));
                    saved(p, c, plan);
                });
            });
            if (plan.packMB() > 0) {
                m.set(24, Items.of(Material.GOLD_INGOT, "<white>Pack price: <gold>" + st.money(plan.packPrice()), "<yellow>Click to change"), e -> {
                    click(p);
                    askLong(p, "<aqua>Data pack price:", 0, 1_000_000, v -> {
                        plan.packPrice(v);
                        saved(p, c, plan);
                    });
                });
            }
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
                saved(p, c, plan);
            });
            m.set(29, Items.of(Material.REPEATER, "<white>Instalments: <aqua>" + plan.installments(), "<gray>Number of periods to repay over.", "<yellow>Click to change"), e -> {
                click(p);
                askLong(p, "<aqua>Number of instalments (1-60):", 1, 60, v -> {
                    plan.installments(v.intValue());
                    saved(p, c, plan);
                });
            });
            m.set(30, Items.of(Material.EXPERIENCE_BOTTLE, "<white>Interest: <aqua>" + plan.interestPercent() + "%", "<gray>Added to the phone price.", "<yellow>Click to change"), e -> {
                click(p);
                askDouble(p, "<aqua>Interest in percent (0-200):", 0, 200, v -> {
                    plan.interestPercent(v);
                    saved(p, c, plan);
                });
            });
            m.set(31, Items.of(Material.EMERALD, "<white>Down payment: <gold>" + st.money(plan.downPayment()), "<gray>Paid up front at sign-up.", "<yellow>Click to change"), e -> {
                click(p);
                askLong(p, "<aqua>Down payment:", 0, 1_000_000, v -> {
                    plan.downPayment(v);
                    saved(p, c, plan);
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

    private void saved(Player p, Carrier c, Plan plan) {
        plugin.store().markDirty();
        editor(p, c, plan);
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
            lore.add("<gray>Model: <white>" + Items.esc(plugin.settings().generation(t.generation()).model()) + " <dark_gray>(Gen " + t.generation() + ")");
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
