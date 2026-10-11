package org.ndpr.bukkit;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * 文本 / 标题 / 踢出 兼容层
 *
 * 服务端 API 更新后, Bukkit 旧聊天组件 (net.md_5.bungee.api.chat)、
 * Player#kickPlayer(String)、Player#sendTitle(String...) 均已标记弃用;
 * Paper/Folia 推荐使用 Adventure。
 *
 * 本层在运行时自动选择实现:
 * - Paper / Folia  -> {@link PaperChatBridge}(Adventure 组件, 无弃用 API)
 * - Spigot / CraftBukkit(无 Adventure 或 Player 无 kick(Component)) -> {@link LegacyChatBridge}(旧版 API)
 *
 * 说明: Adventure 实现类单独成文件, 仅在确认可用后才被加载,
 * 避免在没有 Adventure 的服务端触发 NoClassDefFoundError。
 */
public interface ChatBridge {

    /** 点击动作 */
    enum ClickAction {
        OPEN_URL,
        RUN_COMMAND
    }

    /** 发送整段可点击(+可悬停)文本 */
    void sendClickableText(CommandSender sender, String text, ClickAction action, String value, String hover);

    /** 发送 前缀 + 可点击链接 的复合消息 */
    void sendLink(CommandSender sender, String prefix, String linkText, String url, String hover);

    /** 发送标题/副标题 */
    void showTitle(Player player, String title, String subtitle);

    /** 踢出玩家(理由为旧式 § 着色文本) */
    void kick(Player player, String legacyReason);

    /** 运行时探测可用实现 */
    static ChatBridge create() {
        try {
            Class.forName("net.kyori.adventure.text.Component");
            Class.forName("net.kyori.adventure.title.Title");
            Class.forName("net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer");
            // kick(Component) 为 Paper 系专有; Spigot 即使自带 Adventure 也不具备
            Player.class.getMethod("kick", Class.forName("net.kyori.adventure.text.Component"));
            return new PaperChatBridge();
        } catch (Throwable ignored) {
            return new LegacyChatBridge();
        }
    }
}
