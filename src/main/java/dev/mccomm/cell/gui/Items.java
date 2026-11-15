package dev.mccomm.cell.gui;

import dev.mccomm.cell.util.Msg;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;

/** Item builders for menus. Names and lore are MiniMessage; never pass raw user text without escaping. */
public final class Items {
    private Items() {
    }

    public static ItemStack of(Material m, String name, String... lore) {
        return of(m, name, List.of(lore));
    }

    public static ItemStack of(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        it.editMeta(meta -> {
            meta.displayName(Msg.item(name));
            List<Component> l = new ArrayList<>();
            for (String line : lore) l.add(Msg.item(line));
            meta.lore(l);
            meta.addItemFlags(ItemFlag.values());
        });
        return it;
    }

    public static ItemStack head(OfflinePlayer owner, String name, List<String> lore) {
        ItemStack it = of(Material.PLAYER_HEAD, name, lore);
        if (owner != null) it.editMeta(SkullMeta.class, m -> m.setOwningPlayer(owner));
        return it;
    }

    public static ItemStack glow(ItemStack it) {
        it.editMeta(m -> m.setEnchantmentGlintOverride(true));
        return it;
    }

    public static ItemStack amount(ItemStack it, int n) {
        it.setAmount(Math.clamp(n, 1, 64));
        return it;
    }

    public static String esc(String s) {
        return net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(s == null ? "" : s);
    }

    /** Splits long text into lore lines of roughly {@code width} characters. */
    public static List<String> wrap(String text, int width, String style) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (!line.isEmpty() && line.length() + word.length() + 1 > width) {
                out.add(style + esc(line.toString()));
                line.setLength(0);
            }
            if (!line.isEmpty()) line.append(' ');
            line.append(word);
        }
        if (!line.isEmpty()) out.add(style + esc(line.toString()));
        return out;
    }
}
