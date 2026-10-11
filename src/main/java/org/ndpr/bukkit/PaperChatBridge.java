package org.ndpr.bukkit;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;

/**
 * Paper / Folia 实现: 使用 Adventure 组件 (MC 1.20.1+ 与 26.x 均为官方推荐 API)
 * 兼容 Adventure 4.x(1.20.1) 与 5.x(26.3): ClickEvent 在 5.x 变为泛型, 此处统一按裸类型使用
 */
@SuppressWarnings({"deprecation", "rawtypes", "unchecked"})
class PaperChatBridge implements ChatBridge {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private static Component parse(String text) {
        return LEGACY.deserialize(text == null ? "" : text);
    }

    @Override
    public void sendClickableText(CommandSender sender, String text, ClickAction action, String value, String hover) {
        Component component = parse(text).clickEvent(clickEvent(action, value));
        if (hover != null) {
            component = component.hoverEvent(HoverEvent.showText(parse(hover)));
        }
        sender.sendMessage(component);
    }

    @Override
    public void sendLink(CommandSender sender, String prefix, String linkText, String url, String hover) {
        Component link = Component.text(linkText)
                .color(NamedTextColor.AQUA)
                .decoration(TextDecoration.UNDERLINED, true)
                .clickEvent(ClickEvent.openUrl(url));
        if (hover != null) {
            link = link.hoverEvent(HoverEvent.showText(parse(hover)));
        }
        sender.sendMessage(parse(prefix).append(link));
    }

    @Override
    public void showTitle(Player player, String title, String subtitle) {
        player.showTitle(Title.title(
                parse(title),
                parse(subtitle),
                Title.Times.times(Duration.ofMillis(500), Duration.ofMillis(3500), Duration.ofMillis(1000))));
    }

    @Override
    public void kick(Player player, String legacyReason) {
        player.kick(parse(legacyReason));
    }

    /** 裸类型以同时兼容 Adventure 4.x(非泛型) 与 5.x(泛型) */
    private static ClickEvent clickEvent(ClickAction action, String value) {
        return action == ClickAction.OPEN_URL ? ClickEvent.openUrl(value) : ClickEvent.runCommand(value);
    }
}
