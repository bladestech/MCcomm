package dev.mccomm.cell.gui;

import dev.mccomm.cell.CellPlugin;
import dev.mccomm.cell.util.Msg;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Asks a player to type something in chat (a number, a message). Their next chat line is captured, not broadcast. */
public final class ChatPrompt implements Listener {
    private record Pending(Consumer<String> answer, long expiresAt) {
    }

    private final CellPlugin plugin;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    public ChatPrompt(CellPlugin plugin) {
        this.plugin = plugin;
    }

    public void ask(Player p, String question, Consumer<String> answer) {
        p.closeInventory();
        pending.put(p.getUniqueId(), new Pending(answer, System.currentTimeMillis() + plugin.settings().promptTimeout * 1000L));
        Msg.send(p, question + " <dark_gray>(type in chat, or <white>cancel</white>)");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Pending pd = pending.remove(e.getPlayer().getUniqueId());
        if (pd == null) return;
        e.setCancelled(true);
        if (pd.expiresAt() < System.currentTimeMillis()) return;
        String text = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (text.equalsIgnoreCase("cancel") || text.isEmpty()) {
                Msg.send(p, "<gray>Cancelled.");
                return;
            }
            pd.answer().accept(text);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        pending.remove(e.getPlayer().getUniqueId());
    }
}
