package org.ndpr.bukkit;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Spigot / CraftBukkit 回退实现: BungeeCord chat + 旧版 Player 方法
 * 这些 API 在新版服务端已标记弃用但功能完整, 用于保证非 Paper 服务端可用性
 */
@SuppressWarnings("deprecation")
class LegacyChatBridge implements ChatBridge {

    @Override
    public void sendClickableText(CommandSender sender, String text, ClickAction action, String value, String hover) {
        if (sender instanceof Player player) {
            TextComponent msg = new TextComponent(text);
            msg.setClickEvent(new ClickEvent(toBungee(action), value));
            if (hover != null) {
                msg.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        new ComponentBuilder(hover).create()));
            }
            player.spigot().sendMessage(msg);
        } else {
            sender.sendMessage(text + " " + value);
        }
    }

    @Override
    public void sendLink(CommandSender sender, String prefix, String linkText, String url, String hover) {
        if (sender instanceof Player player) {
            TextComponent msg = new TextComponent(prefix);
            TextComponent link = new TextComponent(linkText);
            link.setColor(ChatColor.AQUA);
            link.setUnderlined(true);
            link.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url));
            if (hover != null) {
                link.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        new ComponentBuilder(hover).create()));
            }
            player.spigot().sendMessage(msg, link);
        } else {
            sender.sendMessage(prefix + linkText);
        }
    }

    @Override
    public void showTitle(Player player, String title, String subtitle) {
        player.sendTitle(title, subtitle, 10, 70, 20);
    }

    @Override
    public void kick(Player player, String legacyReason) {
        player.kickPlayer(legacyReason);
    }

    private static ClickEvent.Action toBungee(ClickAction action) {
        return action == ClickAction.OPEN_URL ? ClickEvent.Action.OPEN_URL : ClickEvent.Action.RUN_COMMAND;
    }
}
