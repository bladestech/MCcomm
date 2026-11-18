package dev.mccomm.cell.gui;

import dev.mccomm.cell.util.Msg;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.IntConsumer;

/**
 * The shared layout every menu uses. Six rows:
 * <pre>
 *  row 0   tabs (or a title)                      · Close (8)
 *  rows 1-4 content (slots 9-44)
 *  row 5   Back (45) · Prev (48) · page (49) · Next (50) · Help (53)
 * </pre>
 * The top and bottom rows are grey glass so the content area stands out.
 */
public final class Frame {
    public static final int CLOSE = 8, BACK = 45, PREV = 48, PAGE = 49, NEXT = 50, HELP = 53;
    /** Every content slot, in reading order. */
    public static final int[] CONTENT = range(9, 44);

    /** A tab: its key, icon and name. */
    public record Tab(String key, Material icon, String name) {
    }

    private Frame() {
    }

    public static int[] range(int from, int to) {
        int[] r = new int[to - from + 1];
        for (int i = 0; i < r.length; i++) r[i] = from + i;
        return r;
    }

    /** Content slots of the given rows (1-4). */
    public static int[] rows(int first, int last) {
        return range(first * 9, last * 9 + 8);
    }

    public static void click(Player p) {
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.6f);
    }

    /** A blank 6-row menu with the frame's background and a Close button. */
    public static Menu create(Player p, String title, String page) {
        Menu m = new Menu(6, Msg.mm("<dark_gray>" + title), page);
        ItemStack content = Items.of(Material.BLACK_STAINED_GLASS_PANE, "<gray> ");
        ItemStack chrome = Items.of(Material.GRAY_STAINED_GLASS_PANE, "<gray> ");
        for (int i = 0; i < 54; i++) m.set(i, i < 9 || i >= 45 ? chrome : content);
        m.set(CLOSE, Items.of(Material.BARRIER, "<red>Close"), e -> p.closeInventory());
        return m;
    }

    /** Draws the tab row; the active tab glows. {@code open} is called with a tab's key when clicked. */
    public static void tabs(Menu m, Player p, List<Tab> tabs, String active, java.util.function.Consumer<String> open) {
        for (int i = 0; i < tabs.size() && i < 8; i++) {
            Tab t = tabs.get(i);
            boolean on = t.key().equals(active);
            ItemStack it = Items.of(t.icon(), (on ? "<aqua><b>▶ " : "<gray>") + t.name(), on ? List.of() : List.of("<yellow>Click to open"));
            if (on) Items.glow(it);
            m.set(i, it, on ? null : e -> {
                click(p);
                open.accept(t.key());
            });
        }
    }

    /** A title item in the top-left (for pages without tabs). */
    public static void title(Menu m, ItemStack item) {
        m.set(0, item);
    }

    public static void back(Menu m, Player p, String label, Runnable back) {
        m.set(BACK, Items.of(Material.ARROW, "<gray>« " + label), e -> {
            click(p);
            back.run();
        });
    }

    public static void help(Menu m, String title, String... lines) {
        List<String> lore = new ArrayList<>();
        for (String l : lines) lore.add(l.isEmpty() ? "" : "<gray>" + l);
        m.set(HELP, Items.of(Material.KNOWLEDGE_BOOK, "<aqua>" + title, lore));
    }

    /**
     * Lays out one page of a list in {@code slots}, with Prev/Next and a page number on the bottom row.
     *
     * @return the page actually shown (clamped)
     */
    public static <T> int page(Menu m, Player p, List<T> items, int page, int[] slots, Function<T, ItemStack> render,
                               BiConsumer<T, InventoryClickEvent> onClick, IntConsumer reopen) {
        int per = slots.length;
        int pages = Math.max(1, (items.size() + per - 1) / per);
        int pg = Math.clamp(page, 0, pages - 1);
        for (int i = 0; i < per; i++) {
            int idx = pg * per + i;
            if (idx >= items.size()) break;
            T item = items.get(idx);
            m.set(slots[i], render.apply(item), onClick == null ? null : e -> onClick.accept(item, e));
        }
        if (pages > 1) {
            m.set(PAGE, Items.of(Material.PAPER, "<white>Page " + (pg + 1) + "/" + pages, "<gray>" + items.size() + " in total"));
            if (pg > 0) m.set(PREV, Items.of(Material.SPECTRAL_ARROW, "<white>« Previous page"), e -> {
                click(p);
                reopen.accept(pg - 1);
            });
            if (pg < pages - 1) m.set(NEXT, Items.of(Material.SPECTRAL_ARROW, "<white>Next page »"), e -> {
                click(p);
                reopen.accept(pg + 1);
            });
        }
        return pg;
    }

    /** A grey placeholder for an empty list. */
    public static ItemStack empty(String title, String... lines) {
        List<String> lore = new ArrayList<>();
        for (String l : lines) lore.add("<dark_gray>" + l);
        return Items.of(Material.LIGHT_GRAY_DYE, "<gray>" + title, lore);
    }
}
