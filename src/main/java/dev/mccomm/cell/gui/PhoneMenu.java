package dev.mccomm.cell.gui;

import dev.mccomm.cell.CellPlugin;
import dev.mccomm.cell.Settings;
import dev.mccomm.cell.band.Band;
import dev.mccomm.cell.comm.Numbers;
import dev.mccomm.cell.economy.BillingService;
import dev.mccomm.cell.economy.PhoneModel;
import dev.mccomm.cell.model.LedgerEntry;
import dev.mccomm.cell.model.Plan;
import dev.mccomm.cell.model.Subscription;
import dev.mccomm.cell.model.Carrier;
import dev.mccomm.cell.model.PlayerProfile;
import dev.mccomm.cell.model.SmsMessage;
import dev.mccomm.cell.model.Tower;
import dev.mccomm.cell.signal.SignalService;
import dev.mccomm.cell.storage.DataStore;
import dev.mccomm.cell.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** The /cell phone: a home screen with eight apps (messages, contacts, dial, signal, SIMs and plans, shop, wallet, settings) and Work. */
public final class PhoneMenu {
    private static final String HOME = "home";
    private static final long CONFIRM_MS = 5000;

    private final CellPlugin plugin;
    private final Map<UUID, Long> confirm = new HashMap<>();

    public PhoneMenu(CellPlugin plugin) {
        this.plugin = plugin;
    }

    private PlayerProfile prof(Player p) {
        return plugin.store().profile(p.getUniqueId());
    }

    private static ItemStack bg() {
        return Items.of(Material.BLACK_STAINED_GLASS_PANE, "<gray> ");
    }

    private Menu menu(Player p, String title, String page) {
        Menu m = Frame.create(p, title, page);
        return m;
    }

    private void click(Player p) {
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.6f);
    }

    private void back(Menu m, Player p) {
        Frame.back(m, p, "Home", () -> open(p));
    }

    /** Re-renders the home screen if the player is looking at it (called every signal update). */
    public void refreshIfOpen(Player p) {
        if (p.getOpenInventory().getTopInventory().getHolder(false) instanceof Menu m && HOME.equals(m.page())) {
            renderHome(p, m);
        }
    }

    // =================================================================== home

    public void open(Player p) {
        Menu m = new Menu(6, Msg.mm("<dark_gray>Phone"), HOME);
        renderHome(p, m);
        m.open(p);
    }

    private void renderHome(Player p, Menu m) {
        m.clearActions();
        ItemStack content = bg(), chrome = Items.of(Material.GRAY_STAINED_GLASS_PANE, "<gray> ");
        for (int i = 0; i < 54; i++) m.set(i, i < 9 || i >= 45 ? chrome : content);
        SignalService.Status st = plugin.signal().status(p);
        PlayerProfile prof = prof(p);
        boolean service = st.hasService();

        // Status bar: one item per SIM slot, your numbers, phone, clock, close.
        int slots = plugin.billing().simSlots(p);
        for (int i = 0; i < slots && i < 3; i++) {
            SignalService.SimStatus sim = st.slot(i + 1);
            m.set(i, simStatusItem(p, i + 1, sim, st.emergency(), sim != null && st.isDataSim(sim)), e -> {
                click(p);
                sims(p, 0);
            });
        }
        List<String> nums = new ArrayList<>();
        for (String cid : prof.active()) {
            Carrier c = plugin.store().carrier(cid);
            if (c != null) nums.add("<gray>" + Items.esc(c.name()) + ": <white>" + Numbers.format(prof.number(cid)));
        }
        if (nums.isEmpty()) nums.add("<gray>No active SIM.");
        m.set(4, Items.of(Material.NAME_TAG, "<aqua>Your numbers", nums));
        PhoneModel phone = st.phone();
        m.set(6, phone == null
                ? Items.glow(Items.of(Material.BARRIER, "<red>No phone", "<gray>You need a phone for service.", "<yellow>Click · Shop"))
                : Items.of(phone.icon(), "<white>" + Items.esc(phone.name()), "<gray>Up to <white>" + plugin.settings().spec(phone.maxBand()).name()
                + " <gray>· " + phone.sims() + " SIM slot" + (phone.sims() == 1 ? "" : "s"), "<yellow>Click · Shop"), e -> {
            click(p);
            eshop(p);
        });
        m.set(7, Items.of(Material.CLOCK, "<white>" + clock(p), "<gray>Day " + (p.getWorld().getFullTime() / 24000 + 1)));
        m.set(8, Items.of(Material.BARRIER, "<red>Close"), e -> p.closeInventory());

        // Apps: two rows of four.
        int unread = prof.unread();
        ItemStack msgs = Items.of(Material.WRITABLE_BOOK, "<yellow>Messages" + (unread > 0 ? " <red>(" + unread + " new)" : ""), "<gray>Read and send texts.");
        if (unread > 0) Items.glow(Items.amount(msgs, unread));
        m.set(19, msgs, e -> {
            click(p);
            messages(p, 0);
        });
        m.set(21, Items.of(Material.PLAYER_HEAD, "<green>Contacts", "<gray>" + prof.contacts().size() + " saved"), e -> {
            click(p);
            contacts(p, 0);
        });
        if (service) {
            m.set(23, Items.of(Material.LEVER, "<aqua>Dial", "<gray>Call a number, contact or player."), e -> {
                click(p);
                plugin.prompt().ask(p, "<aqua>Enter a number, contact or player to call:", in -> dial(p, in));
            });
        } else {
            m.set(23, Items.of(Material.GRAY_DYE, "<dark_gray>Dial", "<red>No Service"));
        }
        m.set(25, Items.of(Material.SPYGLASS, "<light_purple>Signal", "<gray>Speedtest and nearby towers."), e -> {
            click(p);
            signal(p, 0);
        });
        m.set(28, Items.of(Material.IRON_INGOT, "<white>SIMs and plans", "<gray>Your SIMs, carriers and plans."), e -> {
            click(p);
            sims(p, 0);
        });
        m.set(30, Items.of(Material.ENDER_CHEST, "<light_purple>Shop", "<gray>Buy, switch, sell or trade in phones."), e -> {
            click(p);
            eshop(p);
        });
        m.set(32, Items.of(Material.GOLD_INGOT, "<gold>Wallet", "<gray>Balance: <white>" + plugin.settings().money(prof.balance()),
                prof.totalOverdue() > 0 ? "<red>Overdue: " + plugin.settings().money(prof.totalOverdue()) : "<gray>Plans, deposits, history."), e -> {
            click(p);
            wallet(p);
        });
        m.set(34, Items.of(Material.COMPARATOR, "<gray>Settings", "<dark_gray>Sidebar, Do Not Disturb."), e -> {
            click(p);
            settings(p);
        });
        List<Runnable> work = workApps(p);
        if (!work.isEmpty()) {
            m.set(40, Items.of(Material.BUNDLE, "<aqua>Work", "<gray>Your carrier or factory."), e -> {
                click(p);
                if (work.size() == 1) work.getFirst().run();
                else work(p);
            });
        }

        // Bottom: emergency, call controls, help.
        String sos = plugin.settings().emergencyNumber;
        m.set(45, Items.of(Material.RED_DYE, "<red><b>Emergency " + sos, st.emergency() ? "<gray>Click to dial." : "<dark_gray>No signal at all."),
                e -> {
                    p.closeInventory();
                    plugin.calls().dialEmergency(p);
                });
        String incoming = plugin.calls().incoming(p);
        String partner = plugin.calls().partnerName(p);
        if (incoming != null) {
            m.set(48, Items.glow(Items.of(Material.LIME_DYE, "<green>Answer", "<gray>" + Items.esc(incoming) + " is calling")), e -> {
                p.closeInventory();
                plugin.calls().accept(p);
            });
            m.set(50, Items.of(Material.RED_CONCRETE, "<red>Decline"), e -> {
                p.closeInventory();
                plugin.calls().deny(p);
            });
        } else if (partner != null) {
            m.set(48, Items.glow(Items.of(Material.BELL, "<dark_aqua>In call", "<white>" + Items.esc(partner),
                    "<gray>Your chat goes only to them.")));
            m.set(50, Items.of(Material.RED_CONCRETE, "<red>Hang up"), e -> {
                p.closeInventory();
                plugin.calls().end(p, "<gray>Call ended.");
            });
        } else if (plugin.calls().ringingOut(p)) {
            m.set(50, Items.of(Material.RED_CONCRETE, "<red>Cancel call"), e -> {
                p.closeInventory();
                plugin.calls().end(p, "<gray>Call ended.");
            });
        }
        Frame.help(m, "Your phone", "Top: your SIMs (click for details),", "numbers, phone and the time.",
                "", "Need service? Buy a phone in Shop,", "add credits in Wallet, then pick a", "carrier in SIMs and plans.");
    }

    /** One opener per company the player works for (carriers first). */
    private List<Runnable> workApps(Player p) {
        List<Runnable> out = new ArrayList<>();
        for (Carrier c : plugin.store().carriers()) if (c.isStaff(p.getUniqueId())) out.add(() -> plugin.carrierMenu().open(p, c));
        for (dev.mccomm.cell.model.Manufacturer mf : plugin.store().manufacturers()) {
            if (!mf.system() && mf.isStaff(p.getUniqueId())) out.add(() -> plugin.manufacturerMenu().open(p, mf));
        }
        return out;
    }

    /** Pick which company to open when the player works for several. */
    public void work(Player p) {
        Menu m = menu(p, "Work", "work");
        back(m, p);
        int slot = 10;
        for (Carrier c : plugin.store().carriers()) {
            if (!c.isStaff(p.getUniqueId()) || slot > 34) continue;
            m.set(slot++, Items.of(Material.BEACON, "<b>" + c.styled(), "<gray>Carrier", "<yellow>Click · open"), e -> {
                click(p);
                plugin.carrierMenu().open(p, c);
            });
        }
        for (dev.mccomm.cell.model.Manufacturer mf : plugin.store().manufacturers()) {
            if (mf.system() || !mf.isStaff(p.getUniqueId()) || slot > 34) continue;
            m.set(slot++, Items.of(Material.SMITHING_TABLE, "<b>" + mf.styled(), "<gray>Manufacturer", "<yellow>Click · open"), e -> {
                click(p);
                plugin.manufacturerMenu().open(p, mf);
            });
        }
        m.open(p);
    }


    private ItemStack simStatusItem(Player p, int slot, SignalService.SimStatus sim, boolean emergency, boolean data) {
        if (sim == null) {
            return Items.of(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "<gray>SIM " + slot + " <dark_gray>· empty",
                    emergency ? "<red>Emergency calls only" : "<dark_gray>No signal", "", "<dark_gray>Get a SIM in the Carrier store");
        }
        Carrier c = sim.carrier();
        String head = "<gray>SIM " + slot + " <dark_gray>· " + c.styled() + (data ? " <dark_gray>[<aqua>Data</aqua>]" : "");
        String number = "<gray>Number: <white>" + Numbers.format(prof(p).number(c.id()));
        SignalService.Link l = sim.link();
        String planLine = sim.plan() == null ? "<red>No plan" : "<gray>Plan: " + sim.plan().styled(c);
        if (l == null) {
            String why = sim.wifi() ? "<aqua>Wi-Fi calling <dark_gray>· calls and texts over your home Wi-Fi" : sim.issue() != null ? "<red>" + Items.esc(sim.issue())
                    : emergency ? "<red>No Service · SOS only" : "<dark_gray>No Signal";
            return Items.of(Material.GRAY_STAINED_GLASS_PANE, head, why, number);
        }
        Material pane = switch (l.bars()) {
            case 4 -> Material.LIME_STAINED_GLASS_PANE;
            case 3 -> Material.YELLOW_STAINED_GLASS_PANE;
            case 2 -> Material.ORANGE_STAINED_GLASS_PANE;
            default -> Material.RED_STAINED_GLASS_PANE;
        };
        return Items.of(pane, head,
                Msg.bars(l.bars()) + " <white>" + plugin.settings().spec(l.band()).name(),
                "<gray>" + String.format(Locale.ROOT, "%.0f", l.dbm()) + " dBm · " + Msg.distance(l.distance()),
                "<gray>Tower: <white>" + Items.esc(l.tower().name()),
                sim.roamingOn() != null ? "<gold>Roaming on " + sim.roamingOn().styled() : "<dark_gray>Home network",
                number, planLine);
    }

    private static String clock(Player p) {
        long t = (p.getWorld().getTime() + 6000) % 24000;
        int h = (int) (t / 1000), min = (int) ((t % 1000) * 60 / 1000);
        return String.format(Locale.ROOT, "%02d:%02d", h, min);
    }

    /** Prev/next buttons for a manually paged list (36 per page in rows 1-4). */
    private void pager(Menu m, Player p, int pg, int total, int per, java.util.function.IntConsumer reopen) {
        int pages = Math.max(1, (total + per - 1) / per);
        if (pages <= 1) return;
        m.set(Frame.PAGE, Items.of(Material.PAPER, "<white>Page " + (pg + 1) + "/" + pages));
        if (pg > 0) m.set(Frame.PREV, Items.of(Material.SPECTRAL_ARROW, "<white>« Previous page"), e -> {
            click(p);
            reopen.accept(pg - 1);
        });
        if (pg < pages - 1) m.set(Frame.NEXT, Items.of(Material.SPECTRAL_ARROW, "<white>Next page »"), e -> {
            click(p);
            reopen.accept(pg + 1);
        });
    }

    private static <T> List<T> slice(List<T> all, int pg, int per) {
        int from = Math.min(all.size(), pg * per);
        return all.subList(from, Math.min(all.size(), from + per));
    }

    private static int clampPage(int pg, int total, int per) {
        return Math.clamp(pg, 0, Math.max(0, (total - 1) / per));
    }

    private void dial(Player p, String input) {
        if (input.equals(plugin.settings().emergencyNumber)) {
            plugin.calls().dialEmergency(p);
            return;
        }
        String n = plugin.sims().resolve(p, input);
        if (n == null) {
            Msg.send(p, "<red>No number, contact or online player called <white><x></white>.", Msg.p("x", input));
            return;
        }
        plugin.calls().start(p, n);
    }

    private void textTo(Player p, String number) {
        plugin.prompt().ask(p, "<aqua>Message to <white>" + Items.esc(plugin.sims().displayName(p, number)) + "</white>:",
                text -> plugin.sms().send(p, number, text));
    }

    // =============================================================== messages

    public void messages(Player p) {
        messages(p, 0);
    }

    public void messages(Player p, int page) {
        PlayerProfile prof = prof(p);
        Menu m = menu(p, "Messages", "messages");
        long now = System.currentTimeMillis();
        List<SmsMessage> all = new ArrayList<>(prof.inbox());
        int pg = clampPage(page, all.size(), 36);
        int slot = 9;
        for (SmsMessage msg : slice(all, pg, 36)) {
            List<String> lore = new ArrayList<>(Items.wrap(msg.text(), 32, "<white>"));
            lore.add("");
            lore.add("<dark_gray>" + ago(now - msg.time()) + " · to " + Numbers.format(msg.toNumber()));
            lore.add("<yellow>Click · reply · <yellow>Right-click · save · <yellow>Shift-click · delete");
            String from = Items.esc(plugin.sims().displayName(p, msg.fromNumber()));
            ItemStack it = Items.of(msg.read() ? Material.PAPER : Material.MAP, (msg.read() ? "<gray>" : "<yellow>● ") + from, lore);
            if (!msg.read()) Items.glow(it);
            m.set(slot++, it, e -> {
                click(p);
                if (e.isShiftClick()) {
                    prof.inbox().remove(msg);
                    plugin.store().markDirty();
                    messages(p, pg);
                } else if (e.isRightClick()) {
                    saveContactPrompt(p, msg.fromNumber(), msg.fromName());
                } else {
                    textTo(p, msg.fromNumber());
                }
            });
        }
        if (prof.inbox().isEmpty()) m.set(22, Frame.empty("No messages yet"));
        back(m, p);
        pager(m, p, pg, all.size(), 36, n -> messages(p, n));
        m.set(46, Items.of(Material.FEATHER, "<aqua>New message", "<gray>Text a number, contact or player."), e -> {
            click(p);
            plugin.prompt().ask(p, "<aqua>Text who? Enter a number, contact or player:", in -> {
                String n = plugin.sims().resolve(p, in);
                if (n == null) Msg.send(p, "<red>No number, contact or online player called <white><x></white>.", Msg.p("x", in));
                else textTo(p, n);
            });
        });
        m.set(52, Items.of(Material.LAVA_BUCKET, "<red>Delete all", "<gray>Shift-click to clear your inbox."), e -> {
            if (!e.isShiftClick()) return;
            prof.inbox().clear();
            plugin.store().markDirty();
            messages(p, 0);
        });
        m.open(p);
        // Seen now: next time they show as read.
        boolean changed = false;
        for (SmsMessage msg : prof.inbox()) {
            if (!msg.read()) {
                msg.read(true);
                changed = true;
            }
        }
        if (changed) plugin.store().markDirty();
    }

    private static String ago(long ms) {
        long s = ms / 1000;
        if (s < 60) return "just now";
        if (s < 3600) return (s / 60) + " min ago";
        if (s < 86400) return (s / 3600) + " h ago";
        return (s / 86400) + " d ago";
    }

    // =============================================================== contacts

    public void contacts(Player p) {
        contacts(p, 0);
    }

    public void contacts(Player p, int page) {
        PlayerProfile prof = prof(p);
        Menu m = menu(p, "Contacts", "contacts");
        List<Map.Entry<String, String>> all = new ArrayList<>(prof.contacts().entrySet());
        int pg = clampPage(page, all.size(), 36);
        int slot = 9;
        for (Map.Entry<String, String> c : slice(all, pg, 36)) {
            String number = c.getKey();
            DataStore.NumberOwner owner = plugin.store().numberOwner(number);
            OfflinePlayer op = owner == null ? null : Bukkit.getOfflinePlayer(owner.player());
            Player online = plugin.sims().reachablePlayer(number);
            List<String> lore = new ArrayList<>();
            lore.add("<gray>" + Numbers.format(number));
            lore.add(owner == null ? "<red>Not in service" : online != null ? "<green>Reachable" : "<dark_gray>Unavailable");
            lore.add("");
            lore.add("<yellow>Click · call · <yellow>Right-click · text");
            lore.add("<yellow>Shift-click · rename · <yellow>Shift-right · delete");
            m.set(slot++, Items.head(op, "<white>" + Items.esc(c.getValue()), lore), e -> {
                click(p);
                if (e.isShiftClick() && e.isRightClick()) {
                    prof.contacts().remove(number);
                    plugin.store().markDirty();
                    Msg.send(p, "<yellow>Deleted contact <white><n></white>.", Msg.p("n", c.getValue()));
                    contacts(p);
                } else if (e.isShiftClick()) {
                    plugin.prompt().ask(p, "<aqua>New name for <white>" + Items.esc(c.getValue()) + "</white>:", name -> {
                        prof.contacts().put(number, clip(name));
                        plugin.store().markDirty();
                        Msg.send(p, "<green>Contact renamed.");
                        contacts(p);
                    });
                } else if (e.isRightClick()) {
                    textTo(p, number);
                } else {
                    p.closeInventory();
                    plugin.calls().start(p, number);
                }
            });
        }
        if (prof.contacts().isEmpty()) m.set(22, Frame.empty("No contacts yet", "Add someone below."));
        back(m, p);
        pager(m, p, pg, all.size(), 36, n -> contacts(p, n));
        m.set(46, Items.of(Material.OAK_SIGN, "<aqua>Add by number", "<gray>Type a number and a name."), e -> {
            click(p);
            plugin.prompt().ask(p, "<aqua>Contact's number (like 312-4821):", in -> {
                String n = Numbers.normalize(in);
                if (n == null) {
                    Msg.send(p, "<red>That's not a valid number. Numbers have 7 digits, like 312-4821.");
                    return;
                }
                saveContactPrompt(p, n, null);
            });
        });
        m.set(47, Items.of(Material.PLAYER_HEAD, "<aqua>Add online player", "<gray>Pick from players with a SIM."), e -> {
            click(p);
            people(p);
        });
        m.open(p);
    }

    private static String clip(String s) {
        return s.length() > 24 ? s.substring(0, 24) : s;
    }

    private void saveContactPrompt(Player p, String number, String suggested) {
        String hint = suggested == null ? "" : " <dark_gray>(e.g. " + Items.esc(suggested) + ")";
        plugin.prompt().ask(p, "<aqua>Name for <white>" + Numbers.format(number) + "</white>:" + hint, name -> addContact(p, number, name));
    }

    private void addContact(Player p, String number, String name) {
        PlayerProfile prof = prof(p);
        if (!prof.contacts().containsKey(number) && prof.contacts().size() >= plugin.settings().contactsMax) {
            Msg.send(p, "<red>Your contact list is full.");
            return;
        }
        prof.contacts().put(number, clip(name));
        plugin.store().markDirty();
        Msg.send(p, "<green>Saved <white><n></white> as <white><c></white>.", Msg.p("n", Numbers.format(number)), Msg.p("c", clip(name)));
    }

    public void people(Player p) {
        Menu m = menu(p, "Add a contact", "people");
        PlayerProfile prof = prof(p);
        int slot = 9;
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (slot > 44) break;
            if (o.equals(p)) continue;
            String n = plugin.sims().outgoingNumber(o);
            if (n == null) continue;
            boolean saved = prof.contacts().containsKey(n);
            m.set(slot++, Items.head(o, "<white>" + Items.esc(o.getName()), List.of("<gray>" + Numbers.format(n),
                    saved ? "<green>Already in contacts" : "<yellow>Click to add")), e -> {
                click(p);
                if (!saved) {
                    addContact(p, n, o.getName());
                    people(p);
                }
            });
        }
        if (slot == 9) m.set(22, Frame.empty("Nobody online has a SIM"));
        Frame.back(m, p, "Contacts", () -> contacts(p, 0));
        m.open(p);
    }

    // ==================================================================== sims

    public void sims(Player p) {
        sims(p, 0);
    }

    /** SIMs and plans: your SIMs on top, carriers to subscribe to below. */
    public void sims(Player p, int page) {
        PlayerProfile prof = prof(p);
        Menu m = menu(p, "SIMs and plans", "sims");
        SignalService.Status st = plugin.signal().status(p);
        m.set(4, Items.of(Material.BOOK, "<white>SIM cards", "<gray>Up to " + plugin.billing().simSlots(p) + " active (your phone) and "
                + plugin.settings().maxInactive + " inactive.", "<gray>Only active SIMs get calls and texts."));
        m.set(10, Items.of(Material.LIME_STAINED_GLASS_PANE, "<green>Active"));
        for (int i = 0; i < plugin.billing().simSlots(p) && i < 6; i++) {
            int slot = 11 + i;
            if (i < prof.active().size()) {
                Carrier c = plugin.store().carrier(prof.active().get(i));
                if (c == null) continue;
                SignalService.SimStatus sim = st.slot(i + 1);
                String signal = sim == null || sim.link() == null ? (sim != null && sim.wifi() ? "<aqua>Wi-Fi calling" : "<red>No Service")
                        : Msg.bars(sim.link().bars()) + " <white>" + plugin.settings().spec(sim.link().band()).name();
                boolean data = sim != null && st.isDataSim(sim);
                ItemStack it = Items.of(Material.IRON_INGOT, c.styled()
                                + (data ? " <dark_gray>[<aqua>Data</aqua>]" : ""),
                        "<gray>SIM " + (i + 1) + " · <white>" + Numbers.format(prof.number(c.id())), signal,
                        data ? "<aqua>Used for data, calls and texts" : "<dark_gray>Backup for data", "",
                        "<yellow>Click · deactivate · <yellow>Right-click · use for data",
                        "<yellow>Shift-click · unsubscribe");
                m.set(slot, data ? Items.glow(it) : it, e -> {
                    click(p);
                    if (e.isShiftClick()) confirmUnsub(p, c);
                    else if (e.isRightClick()) plugin.sims().setDataSim(p, c);
                    else plugin.sims().deactivate(p, c);
                    plugin.signal().tick();
                    sims(p, page);
                });
            } else {
                m.set(slot, Items.of(Material.GRAY_STAINED_GLASS_PANE, "<dark_gray>Empty slot", "<gray>Activate a SIM or pick a carrier below."));
            }
        }
        m.set(19, Items.of(Material.GRAY_STAINED_GLASS_PANE, "<gray>Inactive"));
        for (int i = 0; i < plugin.settings().maxInactive && i < 6; i++) {
            int slot = 20 + i;
            if (i < prof.inactive().size()) {
                Carrier c = plugin.store().carrier(prof.inactive().get(i));
                if (c == null) continue;
                m.set(slot, Items.of(Material.IRON_NUGGET, c.styled(),
                        "<gray>Number: <white>" + Numbers.format(prof.number(c.id())), "<dark_gray>Inactive", "",
                        "<yellow>Click · activate · <yellow>Shift-click · unsubscribe"), e -> {
                    click(p);
                    if (e.isShiftClick()) confirmUnsub(p, c);
                    else plugin.sims().activate(p, c);
                    sims(p, page);
                });
            } else {
                m.set(slot, Items.of(Material.BLACK_STAINED_GLASS_PANE, "<dark_gray>Empty"));
            }
        }
        back(m, p);
        storeRows(p, m, page);
        Carrier ds = prof.dataSim() == null ? null : plugin.store().carrier(prof.dataSim());
        m.set(17, Items.of(Material.REPEATER, "<aqua>Data SIM: <white>" + (ds == null ? "Automatic" : Items.esc(ds.name())),
                "<gray>Speedtests, data and outgoing calls/texts", "<gray>use this SIM. If it has no service,", "<gray>the other SIM takes over.",
                "", ds == null ? "<dark_gray>Right-click a SIM above to choose one" : "<yellow>Click <gray>switch to automatic"), e -> {
            click(p);
            if (prof.dataSim() != null) {
                plugin.sims().setDataSim(p, null);
                plugin.signal().tick();
                sims(p, page);
            }
        });
        Frame.help(m, "SIMs and plans", "Row 1: active SIMs (your phone's slots).", "Row 2: inactive SIMs.",
                "Rows 3-4: carriers. Click one to see its", "plans and subscribe; each gives a number.");
        m.open(p);
    }

    private void confirmUnsub(Player p, Carrier c) {
        long now = System.currentTimeMillis();
        Long until = confirm.get(p.getUniqueId());
        if (until != null && until > now) {
            confirm.remove(p.getUniqueId());
            plugin.sims().unsubscribe(p, c);
        } else {
            confirm.put(p.getUniqueId(), now + CONFIRM_MS);
            Msg.send(p, "<yellow>Shift-click again within 5 seconds to unsubscribe from <white><c></white>. You'll lose your number.",
                    Msg.p("c", c.name()));
        }
    }

    // =================================================================== store

    /** Old entry point: the SIMs and plans page. */
    public void store(Player p) {
        sims(p, 0);
    }

    /** Carriers to subscribe to, in rows 3-4 of the SIMs page (paged). */
    private void storeRows(Player p, Menu m, int page) {
        PlayerProfile prof = prof(p);
        Map<String, SignalService.Link> here = plugin.signal().bestLinks(p);
        List<Carrier> all = new ArrayList<>(plugin.store().carriers());
        int pg = clampPage(page, all.size(), 18);
        int slot = 27;
        for (Carrier c : slice(all, pg, 18)) {
            boolean has = prof.has(c.id());
            List<Tower> towers = plugin.store().towersOf(c.id());
            long online = towers.stream().filter(Tower::online).count();
            var host = c.mvno() ? plugin.partners().mvnoHost(c) : null;
            SignalService.Link l = here.get(host != null ? host.host().id() : c.id());
            List<String> lore = new ArrayList<>();
            lore.add("<gray>Numbers: <white>" + c.prefix() + "-xxxx");
            if (c.mvno()) lore.add("<light_purple>Virtual network" + (host != null ? " on " + host.host().styled() : " <red>(no network)"));
            else lore.add("<gray>Towers: <white>" + towers.size() + " <dark_gray>(" + online + " online)");
            int partners = plugin.partners().roamingHosts(c).size();
            if (partners > 0) lore.add("<gray>Roaming partners: <white>" + partners);
            lore.addAll(plugin.market().ratingLore(c));
            lore.add("<gray>Here: " + (l == null ? "<red>No coverage" : Msg.bars(l.bars()) + " <white>" + plugin.settings().spec(l.band()).name()));
            lore.add("<gray>Plans: <white>" + c.plans().size() + (c.plans().isEmpty() ? "" : " <dark_gray>(from "
                    + plugin.settings().money(c.plans().values().stream().mapToLong(Plan::price).min().orElse(0)) + ")"));
            lore.add(has ? "<green>Subscribed · " + Numbers.format(prof.number(c.id())) + " <dark_gray>· click to change plan"
                    : "<yellow>Click to see plans");
            ItemStack it = Items.of(has ? Material.LIME_DYE : Material.EMERALD, "<b>" + c.styled(), lore);
            m.set(slot++, has ? Items.glow(it) : it, e -> {
                click(p);
                plans(p, c);
            });
        }
        if (all.isEmpty()) m.set(31, Frame.empty("No carriers yet", "Start one with /carrier create"));
        pager(m, p, pg, all.size(), 18, n -> sims(p, n));
    }

    // ================================================================== towers

    /** Old entry point: the Signal app. */
    public void towers(Player p) {
        signal(p, 0);
    }

    /** Signal: your SIMs' signal, a speedtest, and towers around you. */
    public void signal(Player p, int page) {
        Menu m = menu(p, "Signal", "signal");
        SignalService.Status st = plugin.signal().status(p);
        for (int i = 0; i < plugin.billing().simSlots(p) && i < 3; i++) {
            SignalService.SimStatus sim = st.slot(i + 1);
            m.set(10 + i, simStatusItem(p, i + 1, sim, st.emergency(), sim != null && st.isDataSim(sim)));
        }
        if (st.hasService()) {
            m.set(15, Items.of(Material.SPYGLASS, "<light_purple>Run a speedtest", "<gray>Uses the data it downloads.", "<yellow>Click · start"), e -> {
                click(p);
                p.closeInventory();
                plugin.speedTest().start(p, 0);
            });
        } else {
            m.set(15, Items.of(Material.GRAY_DYE, "<dark_gray>Speedtest", "<red>No Service"));
        }
        Location eye = p.getEyeLocation();
        double h = plugin.settings().antennaHeight;
        List<Tower> near = new ArrayList<>();
        for (Tower t : plugin.store().towers()) {
            if (!t.worldId().equals(p.getWorld().getUID())) continue;
            Location a = t.antenna(h);
            if (a != null && a.distanceSquared(eye) <= 4000.0 * 4000.0) near.add(t);
        }
        near.sort(Comparator.comparingDouble(t -> t.antenna(h).distanceSquared(eye)));
        int pg = clampPage(page, near.size(), 27);
        int slot = 18;
        for (Tower t : slice(near, pg, 27)) {
            Carrier c = plugin.store().carrier(t.carrierId());
            List<String> bands = new ArrayList<>();
            for (Band b : t.enabledBands()) bands.add(plugin.settings().spec(b).name());
            m.set(slot++, Items.of(t.online() ? Material.LIGHTNING_ROD : Material.IRON_BARS, "<gold>" + Items.esc(t.name()),
                    "<gray>Carrier: <white>" + Items.esc(c == null ? "?" : c.name()),
                    "<gray>Model: <white>" + Items.esc(plugin.settings().generation(t.generation()).model()) + " <dark_gray>(Gen " + t.generation() + ")",
                    "<gray>Bands: <white>" + (bands.isEmpty() ? "none" : String.join(", ", bands)),
                    "<gray>Distance: <white>" + Msg.distance(t.antenna(h).distance(eye)),
                    "<gray>Height: <white>" + Math.round(t.heightAboveGround()) + " blocks <dark_gray>(+"
                            + Math.round((plugin.settings().rangeMultiplier(t.heightAboveGround()) - 1) * 100) + "% range)",
                    t.online() ? "<green>Online" : "<red>Offline"));
        }
        if (near.isEmpty()) m.set(31, Frame.empty("No towers within 4 km"));
        back(m, p);
        pager(m, p, pg, near.size(), 27, n -> signal(p, n));
        Frame.help(m, "Signal", "Row 1: each SIM's signal and a speedtest.", "Below: towers within 4 km, nearest first.",
                "", "Walls right around you, depth, weather", "and distance all weaken the signal.");
        m.open(p);
    }

    // ================================================================== plans

    /** A carrier's plans; clicking one subscribes (or switches plan if already subscribed). */
    public void plans(Player p, Carrier c) {
        PlayerProfile prof = prof(p);
        Menu m = menu(p, "Plans · " + Items.esc(c.name()), "plans");
        Frame.title(m, Items.of(Material.BEACON, "<b>" + c.styled(), plugin.market().ratingLore(c)));
        Plan current = plugin.billing().planOf(prof, c.id());
        int slot = 9;
        for (Plan plan : c.plans().values()) {
            if (slot > 35) break;
            boolean mine = current != null && current.id().equals(plan.id());
            List<String> lore = new ArrayList<>(planLore(plan));
            if (plan.financed() && c.tradeIn()) {
                lore.add("<aqua>Trade-in: " + Math.round(c.tradeInPercent()) + "% of a phone's price off the loan");
            }
            lore.add("");
            if (mine) lore.add("<green>Your current plan");
            else if (prof.has(c.id())) lore.add("<yellow>Click to switch to this plan");
            else lore.add("<yellow>Click to subscribe (" + plugin.settings().money(plan.price()) + " now)");
            ItemStack it = Items.of(mine ? Material.LIME_DYE : Material.PAPER, "<b>" + plan.styled(c), lore);
            m.set(slot++, mine ? Items.glow(it) : it, e -> {
                click(p);
                if (mine) return;
                if (prof.has(c.id())) plugin.billing().changePlan(p, c, plan);
                else plugin.sims().subscribe(p, c, plan);
                plans(p, c);
            });
        }
        if (c.plans().isEmpty()) m.set(22, Frame.empty("This carrier has no plans yet"));
        Frame.back(m, p, "SIMs and plans", () -> sims(p, 0));
        m.set(40, Items.of(Material.GOLD_INGOT, "<gold>Balance: <white>" + plugin.settings().money(prof.balance()),
                prof.totalOverdue() > 0 ? "<red>Overdue: " + plugin.settings().money(prof.totalOverdue()) : "<gray>Click to open your wallet"), e -> {
            click(p);
            wallet(p);
        });
        m.open(p);
    }

    public List<String> planLore(Plan plan) {
        List<String> lore = new ArrayList<>();
        var st = plugin.settings();
        String type = switch (plan.type()) {
            case POSTPAID -> "<aqua>Postpaid";
            case FINANCED -> "<light_purple>Phone included (financed)";
            case PREPAID -> "<green>Prepaid";
        };
        lore.add(type);
        if (plan.prepaid()) {
            lore.add("<gray>Top-up: <white>" + st.money(plan.price()) + " <gray>· lasts " + BillingService.period(plan.periodHours()));
        } else {
            lore.add("<gray>Price: <white>" + st.money(plan.price()) + " <gray>per " + BillingService.period(plan.periodHours()));
        }
        if (plan.financed()) {
            PhoneModel ph = st.phone(plan.financePhone());
            if (ph == null) {
                lore.add("<red>No phone set");
            } else {
                lore.add("<gray>Phone: <white>" + Items.esc(ph.name()) + " <dark_gray>(" + st.money(ph.price()) + " retail)");
                lore.add("<gray>Down payment: <white>" + st.money(plan.downPayment()));
                lore.add("<gray>Then <white>" + st.money(plan.installment(ph.price())) + "</white> × " + plan.installments()
                        + " <gray>(" + plan.interestPercent() + "% interest)");
                lore.add("<dark_gray>Locked to this carrier until paid off");
            }
        }
        lore.add("<gray>Roaming: " + (!plan.roaming() ? "<red>not included" : plan.roamingPerGb() > 0
                ? "<white>" + st.money(plan.roamingPerGb()) + " per GB" : "<green>included"));
        String data = plan.unlimited() ? "Unlimited" : Settings.data(plan.dataMB()) + (plan.prepaid() ? " per top-up" : " per period");
        lore.add("<gray>Data: <white>" + data);
        if (!plan.unlimited()) {
            lore.add("<gray>When out of data: <white>" + switch (plan.outOfData()) {
                case THROTTLE -> "slowed to " + Msg.speed(st.throttleMbps);
                case BLOCK -> "data blocked";
                case OVERAGE -> st.money(plan.overagePerGb()) + " per extra GB";
            });
            if (plan.packMB() > 0) lore.add("<gray>Data packs: <white>" + Settings.data(plan.packMB()) + " for " + st.money(plan.packPrice()));
        }
        lore.add("<gray>Max band: <white>" + (plan.maxBand() == null ? "All" : plugin.settings().spec(plan.maxBand()).name()));
        lore.add("<gray>Speed cap: <white>" + (plan.speedCapMbps() > 0 ? Msg.speed(plan.speedCapMbps()) : "None"));
        lore.add("<gray>Texts: " + (plan.texts() ? "<green>yes" : "<red>no") + " <gray>· Calls: " + (plan.calls() ? "<green>yes" : "<red>no"));
        return lore;
    }

    // ================================================================== eshop

    public void eshop(Player p) {
        PlayerProfile prof = prof(p);
        Menu m = menu(p, "Shop", "eshop");
        int i = 0;
        for (PhoneModel pm : plugin.settings().phones.values()) {
            if (i >= 12) break;
            boolean owned = prof.ownedPhones().contains(pm.id());
            boolean using = pm.id().equals(prof.phone());
            List<String> lore = new ArrayList<>();
            lore.add("<gray>Bands: up to <white>" + plugin.settings().spec(pm.maxBand()).name());
            lore.add("<gray>SIM slots: <white>" + pm.sims() + (pm.sims() > 1 ? " <aqua>(dual SIM)" : ""));
            lore.add("<gray>Price: <white>" + plugin.settings().money(pm.price()));
            lore.add("");
            String lock = prof.phoneLocks().get(pm.id());
            String bl = prof.blacklist().get(pm.id());
            if (owned && plugin.billing().paperweight(prof, pm)) {
                lore.add("<dark_red>Paperweight: locked to a carrier that closed");
            } else if (bl != null) {
                Carrier bc = plugin.store().carrier(bl);
                lore.add("<dark_red>Blacklisted by " + Items.esc(bc == null ? bl : bc.name()) + " until your debt is paid");
            } else if (lock != null) {
                Carrier lc = plugin.store().carrier(lock);
                lore.add("<light_purple>Financed · locked to " + Items.esc(lc == null ? lock : lc.name()));
            }
            lore.add(using ? "<green>In use" : owned ? "<yellow>Owned · click to use" : "<yellow>Click to buy");
            boolean sellable = owned && plugin.billing().cantPart(prof, pm) == null;
            if (sellable) lore.add("<yellow>Shift-right-click · sell for " + plugin.settings().money(plugin.billing().sellValue(prof, pm)));
            ItemStack it = Items.of(pm.icon(), (using ? "<green><b>" : "<white><b>") + Items.esc(pm.name()), lore);
            m.set(slotFor(i++), using ? Items.glow(it) : it, e -> {
                click(p);
                if (e.isShiftClick() && e.isRightClick()) {
                    if (!sellable) {
                        String why = plugin.billing().cantPart(prof, pm);
                        if (why != null) Msg.send(p, "<red>" + Msg.escape(why));
                    } else if (confirmed(p, "sell:" + pm.id())) {
                        plugin.billing().sellPhone(p, pm);
                    } else {
                        Msg.send(p, "<yellow>Shift-right-click again within 5 seconds to sell your <white><n></white> for <white><v></white>.",
                                Msg.p("n", pm.name()), Msg.p("v", plugin.settings().money(plugin.billing().sellValue(prof, pm))));
                    }
                } else if (!using) {
                    plugin.billing().buyOrUse(p, pm);
                }
                eshop(p);
            });
        }
        back(m, p);
        // Trade-ins: carriers you're paying a phone off with that run a trade-in program.
        int ts = 28;
        for (String cid : prof.active()) {
            Carrier c = plugin.store().carrier(cid);
            Subscription sub = prof.sub(cid);
            if (c == null || sub == null || !sub.financing() || !c.tradeIn() || ts > 34) continue;
            m.set(ts++, Items.of(Material.HOPPER_MINECART, "<aqua>Trade in · " + c.styled(),
                    "<gray>" + Math.round(c.tradeInPercent()) + "% of a phone's price off your", "<gray>phone loan (" + plugin.settings().money(sub.financeRemaining()) + " left).",
                    "<yellow>Click · choose a phone"), e -> {
                click(p);
                tradeIn(p, c);
            });
        }
        m.set(40, Items.of(Material.GOLD_INGOT, "<gold>Balance: <white>" + plugin.settings().money(prof.balance()), "<yellow>Click · Wallet"), e -> {
            click(p);
            wallet(p);
        });
        Frame.help(m, "Shop", "Rows 1-2: phones, in pairs (the + model", "has two SIM slots). Click to buy or use;",
                "shift-right-click to sell one you own", "for " + Math.round(plugin.settings().resalePercent) + "% of its price.", "",
                "Row 3: trade-ins against a phone loan.");
        m.open(p);
    }

    /** Lays phones out in pairs (base model, then its + model) across two rows. */
    private static int slotFor(int index) {
        int[] layout = {10, 11, 13, 14, 16, 17, 19, 20, 22, 23, 25, 26};
        return layout[Math.min(index, layout.length - 1)];
    }

    // =============================================================== trade-in

    /** Pick an owned phone to trade in against a phone loan with this carrier. */
    public void tradeIn(Player p, Carrier c) {
        PlayerProfile prof = prof(p);
        Subscription sub = prof.sub(c.id());
        if (sub == null || !sub.financing() || !c.tradeIn()) {
            wallet(p);
            return;
        }
        Menu m = menu(p, "Trade in · " + Items.esc(c.name()), "tradein");
        m.set(4, Items.of(Material.PAPER, "<white>Phone loan: <gold>" + plugin.settings().money(sub.financeRemaining()) + " left",
                "<gray>Now " + plugin.settings().money(sub.financeInstallment()) + " per period.",
                "<gray>" + Items.esc(c.name()) + " gives " + Math.round(c.tradeInPercent()) + "% of a phone's price.",
                "<gray>Your instalment drops; the number of payments stays."));
        int slot = 9;
        for (String id : new ArrayList<>(prof.ownedPhones())) {
            PhoneModel pm = plugin.settings().phone(id);
            if (pm == null || slot > 35) continue;
            String why = plugin.billing().cantPart(prof, pm);
            long value = plugin.billing().tradeInValue(c, pm);
            List<String> lore = new ArrayList<>();
            if (why != null) {
                lore.add("<red>" + Items.esc(why));
                m.set(slot++, Items.of(Material.GRAY_DYE, "<dark_gray>" + Items.esc(pm.name()), lore));
                continue;
            }
            long credited = Math.min(value, sub.financeRemaining());
            lore.add("<gray>Trade-in value: <green>" + plugin.settings().money(value));
            if (value > sub.financeRemaining()) {
                lore.add("<gold>Only " + plugin.settings().money(credited) + " is needed to pay off the loan;");
                lore.add("<gold>the rest is lost. Selling it in the eShop pays " + plugin.settings().money(plugin.settings().resaleValue(pm)) + ".");
            }
            if (pm.id().equals(prof.phone())) lore.add("<yellow>This is the phone you're using.");
            lore.add("");
            lore.add("<yellow>Click twice to trade in");
            m.set(slot++, Items.of(pm.icon(), "<white><b>" + Items.esc(pm.name()), lore), e -> {
                click(p);
                if (confirmed(p, "trade:" + pm.id())) {
                    plugin.billing().tradeIn(p, c, pm);
                    wallet(p);
                } else {
                    Msg.send(p, "<yellow>Click again within 5 seconds to trade in your <white><n></white> for <white><v></white> off your loan.",
                            Msg.p("n", pm.name()), Msg.p("v", plugin.settings().money(credited)));
                }
            });
        }
        if (slot == 9) m.set(22, Frame.empty("No phones to trade in", "Only phones you fully own count."));
        Frame.back(m, p, "Shop", () -> eshop(p));
        m.open(p);
    }

    private final Map<String, Long> confirms = new HashMap<>();

    /** True on the second click of the same action within 5 seconds. */
    private boolean confirmed(Player p, String action) {
        String key = p.getUniqueId() + ":" + action;
        long now = System.currentTimeMillis();
        Long until = confirms.get(key);
        if (until != null && until > now) {
            confirms.remove(key);
            return true;
        }
        confirms.put(key, now + CONFIRM_MS);
        return false;
    }

    // ================================================================= wallet

    public void wallet(Player p) {
        PlayerProfile prof = prof(p);
        Menu m = menu(p, "Wallet", "wallet");
        long now = System.currentTimeMillis();
        m.set(13, Items.glow(Items.of(Material.GOLD_BLOCK, "<gold><b>" + plugin.settings().money(prof.balance()),
                "<gray>Your balance pays your plans.")));
        if (prof.totalOverdue() > 0) {
            List<String> lore = new ArrayList<>();
            lore.add("<gray>Missed payments. You can't subscribe");
            lore.add("<gray>anywhere until this is paid.");
            for (var e : prof.overdue().entrySet()) {
                Carrier c = plugin.store().carrier(e.getKey());
                lore.add("<gray>• " + Items.esc(c == null ? e.getKey() : c.name()) + ": <red>" + plugin.settings().money(e.getValue()));
            }
            lore.add("");
            lore.add("<yellow>Deposits pay this off first.");
            m.set(15, Items.of(Material.REDSTONE_BLOCK, "<red>Overdue: " + plugin.settings().money(prof.totalOverdue()), lore));
        }
        m.set(11, Items.of(Material.HOPPER, "<green>Deposit ores", "<gray>Turn ores into credits."), e -> {
            click(p);
            plugin.deposit().open(p, null, () -> wallet(p));
        });

        // Subscriptions: row 2
        int slot = 19;
        List<String> sims = new ArrayList<>(prof.active());
        sims.addAll(prof.inactive());
        for (String cid : sims) {
            if (slot > 34) break;
            Carrier c = plugin.store().carrier(cid);
            if (c == null) continue;
            Subscription sub = prof.sub(cid);
            Plan plan = plugin.billing().planOf(prof, cid);
            List<String> lore = new ArrayList<>();
            lore.add("<gray>Number: <white>" + Numbers.format(prof.number(cid)) + (prof.active().contains(cid) ? "" : " <dark_gray>(inactive)"));
            if (plan == null) {
                lore.add("<red>No plan · no service");
            } else {
                lore.add("<gray>Plan: " + plan.styled(c));
                if (sub.grandfathered() != null) {
                    Plan live = plugin.billing().livePlan(prof, cid);
                    lore.add("<gold>Plan updated by " + Items.esc(c.name()) + ". Your current terms stay until "
                            + (plan.prepaid() ? "your next top-up." : "renewal."));
                    if (live != null) lore.add("<gray>New: " + plugin.billing().shortTerms(live));
                }
                if (plan.prepaid()) {
                    boolean expired = now >= sub.nextBill();
                    lore.add(expired ? "<red>Expired" : "<gray>Expires in <white>" + BillingService.until(sub.nextBill() - now));
                } else {
                    long charge = plugin.billing().nextCharge(prof, cid);
                    lore.add("<gray>Next charge: <white>" + plugin.settings().money(charge) + " <gray>in <white>" + BillingService.until(sub.nextBill() - now));
                    if (prof.balance() < charge) lore.add("<gold>Balance too low for the next charge!");
                }
                lore.add("<gray>Data: <white>" + plugin.billing().dataLeftText(prof, cid));
                if (sub.financing()) {
                    PhoneModel ph = plugin.settings().phone(sub.financePhone());
                    lore.add("<gray>Phone loan: <white>" + plugin.settings().money(sub.financeRemaining()) + " left <dark_gray>("
                            + (ph == null ? "?" : Items.esc(ph.name())) + ", " + plugin.settings().money(sub.financeInstallment()) + "/period)");
                }
            }
            lore.add("");
            lore.add("<yellow>Click · " + (plan == null ? "choose a plan" : "change plan"));
            if (plan != null && plan.prepaid()) lore.add("<yellow>Right-click · top up (" + plugin.settings().money(plan.price()) + ")");
            else if (plan != null && !plan.unlimited() && plan.packMB() > 0)
                lore.add("<yellow>Right-click · buy " + Settings.data(plan.packMB()) + " (" + plugin.settings().money(plan.packPrice()) + ")");
            if (sub != null && sub.financing()) {
                lore.add("<yellow>Shift-click · pay off phone (" + plugin.settings().money(sub.financeRemaining()) + ")");
                if (c.tradeIn()) lore.add("<yellow>Shift-right-click · trade in a phone");
            }
            final Plan fplan = plan;
            final Subscription fsub = sub;
            m.set(slot++, Items.of(plan == null ? Material.GRAY_DYE : Material.PAPER, c.styled(), lore), e -> {
                click(p);
                if (e.isShiftClick() && e.isRightClick() && fsub != null && fsub.financing() && c.tradeIn()) {
                    tradeIn(p, c);
                } else if (e.isShiftClick() && fsub != null && fsub.financing()) {
                    plugin.billing().payOffPhone(p, c);
                    wallet(p);
                } else if (e.isRightClick() && fplan != null && fplan.prepaid()) {
                    plugin.billing().topUp(p, c);
                    wallet(p);
                } else if (e.isRightClick() && fplan != null && fplan.packMB() > 0 && !fplan.unlimited()) {
                    plugin.billing().buyDataPack(p, c);
                    wallet(p);
                } else {
                    plans(p, c);
                }
            });
        }
        if (sims.isEmpty()) m.set(22, Frame.empty("No subscriptions", "Pick a carrier in SIMs and plans."));

        // History
        List<String> hist = new ArrayList<>();
        for (LedgerEntry le : prof.ledger()) {
            if (hist.size() >= 12) break;
            hist.add(ledgerLine(le, now));
        }
        if (hist.isEmpty()) hist.add("<dark_gray>Nothing yet");
        m.set(40, Items.of(Material.BOOK, "<white>History", hist));
        back(m, p);
        m.set(42, Items.of(Material.IRON_INGOT, "<white>SIMs and plans"), e -> {
            click(p);
            sims(p, 0);
        });
        m.set(38, Items.of(Material.PAPER, "<aqua>Send credits", "<gray>To another player. Free."), e -> {
            click(p);
            plugin.prompt().ask(p, "<aqua>Send credits to which player?", who -> {
                org.bukkit.OfflinePlayer to = Bukkit.getOfflinePlayerIfCached(who);
                if (to == null) {
                    Msg.send(p, "<red>Unknown player <white><n></white>.", Msg.p("n", who));
                    return;
                }
                plugin.prompt().ask(p, "<aqua>How many credits to <white>" + Items.esc(to.getName()) + "</white>?", in -> {
                    try {
                        plugin.companies().pay(p, to, Long.parseLong(in.replace(",", "").trim()));
                    } catch (NumberFormatException ex) {
                        Msg.send(p, "<red>Enter a whole number.");
                    }
                });
            });
        });
        Frame.help(m, "Wallet", "Deposit ores for credits; they pay your", "plans. Row 2: your subscriptions (click",
                "to change plan, right-click to top up", "or buy data). Sending credits is free.");
        m.open(p);
    }

    public String ledgerLine(LedgerEntry le, long now) {
        String amt = le.amount() > 0 ? "<green>+" + plugin.settings().money(le.amount())
                : le.amount() < 0 ? "<red>-" + plugin.settings().money(-le.amount()) : "<gray>·";
        return amt + " <gray>" + Items.esc(le.note()) + " <dark_gray>" + ago(now - le.time());
    }

    // ================================================================ settings

    public void settings(Player p) {
        PlayerProfile prof = prof(p);
        Menu m = menu(p, "Settings", "settings");
        boolean hud = plugin.hud().enabled(p);
        m.set(20, Items.of(hud ? Material.REDSTONE_TORCH : Material.LEVER, "<white>Signal sidebar: " + (hud ? "<green>on" : "<red>off"),
                "<gray>Shows your signal on the right of the screen.", "<yellow>Click to toggle"), e -> {
            click(p);
            prof.hud(!hud);
            plugin.store().markDirty();
            if (hud) plugin.hud().clear(p);
            else plugin.hud().update(p, plugin.signal().compute(p));
            settings(p);
        });
        boolean dnd = prof.dnd();
        m.set(24, Items.of(dnd ? Material.BELL : Material.NOTE_BLOCK, "<white>Do Not Disturb: " + (dnd ? "<green>on" : "<red>off"),
                "<gray>Blocks incoming calls and silences texts.", "<yellow>Click to toggle"), e -> {
            click(p);
            prof.dnd(!dnd);
            plugin.store().markDirty();
            settings(p);
        });
        back(m, p);
        m.open(p);
    }
}
