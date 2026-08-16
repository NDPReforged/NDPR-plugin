package org.ndpr.bukkit;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * 聊天消息工具: 可点击/悬停组件 (bungeecord-chat, Spigot/Paper 均内置)
 */
public final class Messages {

    private Messages() {
    }

    /** 发送点击直接执行命令的消息 (RUN_COMMAND, 命令需以 / 开头) */
    public static void sendRunCommand(CommandSender sender, String text, String command, String hover) {
        if (sender instanceof Player p) {
            TextComponent msg = new TextComponent(text);
            msg.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command));
            if (hover != null) {
                msg.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        new ComponentBuilder(hover).create()));
            }
            p.spigot().sendMessage(msg);
        } else {
            sender.sendMessage(text + " " + command);
        }
    }

    /** 发送带打开链接事件的消息 */
    public static void sendOpenUrl(Player player, String prefix, String url, String hover) {
        TextComponent msg = new TextComponent(prefix);
        TextComponent link = new TextComponent(url);
        link.setColor(ChatColor.AQUA);
        link.setUnderlined(true);
        link.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url));
        if (hover != null) {
            link.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    new ComponentBuilder(hover).create()));
        }
        player.spigot().sendMessage(msg, link);
    }
}
