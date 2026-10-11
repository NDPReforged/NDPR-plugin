package org.ndpr.bukkit;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * 聊天消息工具门面
 * 具体实现由 {@link ChatBridge} 运行时选择: Paper/Folia 用 Adventure, 其余回退旧版 API
 */
public final class Messages {

    private static final ChatBridge BRIDGE = ChatBridge.create();

    private Messages() {
    }

    /** 发送点击直接执行命令的消息 (RUN_COMMAND, 命令需以 / 开头) */
    public static void sendRunCommand(CommandSender sender, String text, String command, String hover) {
        BRIDGE.sendClickableText(sender, text, ChatBridge.ClickAction.RUN_COMMAND, command, hover);
    }

    /** 发送带打开链接事件的消息 */
    public static void sendOpenUrl(Player player, String prefix, String url, String hover) {
        BRIDGE.sendLink(player, prefix, url, url, hover);
    }

    /** 发送标题与副标题 */
    public static void showTitle(Player player, String title, String subtitle) {
        BRIDGE.showTitle(player, title, subtitle);
    }

    /** 踢出玩家(理由为旧式 § 着色文本) */
    public static void kick(Player player, String legacyReason) {
        BRIDGE.kick(player, legacyReason);
    }
}
