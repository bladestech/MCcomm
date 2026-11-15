package dev.mccomm.cell.util;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.Locale;

/** MiniMessage helpers. User-supplied strings must go through {@link #p} so they are never parsed as tags. */
public final class Msg {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static String prefix = "";

    private Msg() {
    }

    public static void prefix(String p) {
        prefix = p == null ? "" : p;
    }

    public static Component mm(String s, TagResolver... r) {
        return MM.deserialize(s, r);
    }

    /** Item names/lore: same as {@link #mm} but never italic. */
    public static Component item(String s, TagResolver... r) {
        return MM.deserialize(s, r).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static void send(Audience a, String s, TagResolver... r) {
        a.sendMessage(MM.deserialize(prefix + s, r));
    }

    public static void raw(Audience a, String s, TagResolver... r) {
        a.sendMessage(MM.deserialize(s, r));
    }

    public static TagResolver p(String key, String value) {
        return Placeholder.unparsed(key, value == null ? "" : value);
    }

    public static TagResolver c(String key, Component value) {
        return Placeholder.component(key, value);
    }

    public static String speed(double mbps) {
        if (mbps >= 1000) return String.format(Locale.ROOT, "%.2f Gbps", mbps / 1000);
        if (mbps >= 1) return String.format(Locale.ROOT, "%.1f Mbps", mbps);
        return String.format(Locale.ROOT, "%.0f kbps", mbps * 1000);
    }

    public static String bars(int bars) {
        // ASCII pipes from Minecraft's main font so every bar renders the same width.
        String lit = switch (bars) {
            case 4 -> "green";
            case 3 -> "yellow";
            case 2 -> "gold";
            default -> "red";
        };
        StringBuilder sb = new StringBuilder("<b>");
        for (int i = 0; i < 4; i++) {
            sb.append(i < bars ? "<" + lit + ">" : "<dark_gray>").append('|');
        }
        return sb.append("</b>").toString();
    }

    public static String distance(double blocks) {
        if (blocks >= 1000) return String.format(Locale.ROOT, "%.2f km", blocks / 1000);
        return String.format(Locale.ROOT, "%.0f m", blocks);
    }
}
