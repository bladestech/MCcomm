package dev.mccomm.cell.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Name styles for carriers and plans: one colour (solid) or 2-4 colours (a left-to-right gradient).
 * Everything that shows a carrier or plan name goes through here so the style is consistent.
 */
public final class Style {
    private Style() {
    }

    public static final int MAX_COLORS = 4;

    /** Preset solid colours with the dye used as their swatch. */
    public record Solid(String name, TextColor color, Material swatch) {
    }

    public static final List<Solid> SOLIDS = List.of(
            new Solid("White", TextColor.color(0xFFFFFF), Material.WHITE_DYE),
            new Solid("Silver", TextColor.color(0xAAAAAA), Material.LIGHT_GRAY_DYE),
            new Solid("Red", TextColor.color(0xFF5555), Material.RED_DYE),
            new Solid("Orange", TextColor.color(0xFFA040), Material.ORANGE_DYE),
            new Solid("Gold", TextColor.color(0xFFD700), Material.YELLOW_DYE),
            new Solid("Lime", TextColor.color(0x7CFC00), Material.LIME_DYE),
            new Solid("Green", TextColor.color(0x2ECC71), Material.GREEN_DYE),
            new Solid("Aqua", TextColor.color(0x55FFFF), Material.CYAN_DYE),
            new Solid("Sky", TextColor.color(0x5DADE2), Material.LIGHT_BLUE_DYE),
            new Solid("Blue", TextColor.color(0x3B6CFF), Material.BLUE_DYE),
            new Solid("Purple", TextColor.color(0xA569BD), Material.PURPLE_DYE),
            new Solid("Magenta", TextColor.color(0xFF55FF), Material.MAGENTA_DYE),
            new Solid("Pink", TextColor.color(0xFF8FB1), Material.PINK_DYE),
            new Solid("Brown", TextColor.color(0xB07B4F), Material.BROWN_DYE));

    /** Preset gradients. */
    public static final Map<String, List<TextColor>> GRADIENTS = new LinkedHashMap<>();

    static {
        GRADIENTS.put("Sunset", hex("#FF5F6D", "#FFC371"));
        GRADIENTS.put("Ocean", hex("#2E86DE", "#48DBFB"));
        GRADIENTS.put("Neon", hex("#FF00CC", "#3333FF"));
        GRADIENTS.put("Forest", hex("#11998E", "#38EF7D"));
        GRADIENTS.put("Fire", hex("#F12711", "#F5AF19"));
        GRADIENTS.put("Ice", hex("#E0F7FF", "#7FDBFF", "#4A90E2"));
        GRADIENTS.put("Royal", hex("#8E2DE2", "#4A00E0"));
        GRADIENTS.put("Candy", hex("#FF9A9E", "#FAD0C4", "#A18CD1"));
        GRADIENTS.put("Rainbow", hex("#FF5555", "#FFD700", "#55FF55", "#55AAFF"));
        GRADIENTS.put("Mono", hex("#FFFFFF", "#777777"));
    }

    private static List<TextColor> hex(String... h) {
        List<TextColor> out = new ArrayList<>();
        for (String s : h) out.add(TextColor.fromHexString(s));
        return List.copyOf(out);
    }

    /** MiniMessage for {@code text} (escaped) in the style. Empty style = white. */
    public static String mm(List<TextColor> colors, String text) {
        String safe = MiniMessage.miniMessage().escapeTags(text == null ? "" : text);
        if (colors == null || colors.isEmpty()) return "<white>" + safe + "</white>";
        if (colors.size() == 1) {
            String h = colors.getFirst().asHexString();
            return "<" + h + ">" + safe + "</" + h + ">";
        }
        StringBuilder g = new StringBuilder("<gradient");
        for (TextColor c : colors) g.append(':').append(c.asHexString());
        return g.append('>').append(safe).append("</gradient>").toString();
    }

    public static Component component(List<TextColor> colors, String text) {
        return Msg.mm(mm(colors, text));
    }

    /**
     * Parses "aqua", "#33ccff" or several of them ("#ff5f6d #ffc371", "red,gold") into a style,
     * or a preset gradient name ("sunset"). Returns null if invalid.
     */
    public static List<TextColor> parse(String input) {
        if (input == null) return null;
        String in = input.trim();
        for (Map.Entry<String, List<TextColor>> e : GRADIENTS.entrySet()) {
            if (e.getKey().equalsIgnoreCase(in)) return e.getValue();
        }
        String[] parts = in.split("[\\s,:>]+");
        List<TextColor> out = new ArrayList<>();
        for (String part : parts) {
            if (part.isBlank()) continue;
            TextColor c = color(part);
            if (c == null) return null;
            out.add(c);
        }
        return out.isEmpty() || out.size() > MAX_COLORS ? null : List.copyOf(out);
    }

    private static TextColor color(String s) {
        String t = s.toLowerCase(Locale.ROOT);
        if (!t.startsWith("#") && t.matches("[0-9a-f]{6}")) t = "#" + t;
        if (t.startsWith("#")) return TextColor.fromHexString(t);
        NamedTextColor named = NamedTextColor.NAMES.value(t);
        if (named != null) return named;
        for (Solid sol : SOLIDS) if (sol.name().equalsIgnoreCase(s)) return sol.color();
        return null;
    }

    public static List<String> toHex(List<TextColor> colors) {
        List<String> out = new ArrayList<>();
        for (TextColor c : colors) out.add(c.asHexString());
        return out;
    }

    public static List<TextColor> fromHex(List<String> hex) {
        List<TextColor> out = new ArrayList<>();
        for (String h : hex) {
            TextColor c = TextColor.fromHexString(h);
            if (c != null) out.add(c);
        }
        return out;
    }

    /** "Solid #55FFFF" or "Gradient #FF5F6D → #FFC371". */
    public static String describe(List<TextColor> colors) {
        if (colors == null || colors.isEmpty()) return "Default";
        if (colors.size() == 1) return "Solid " + colors.getFirst().asHexString().toUpperCase(Locale.ROOT);
        return "Gradient " + String.join(" → ", toHex(colors)).toUpperCase(Locale.ROOT);
    }
}
