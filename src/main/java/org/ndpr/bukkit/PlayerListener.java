package org.ndpr.bukkit;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * 玩家事件监听
 * 加入: 收集 IP/UUID -> 缓存 -> 封禁库四维匹配 -> 踢出/放行 -> HWID 验证
 * 离开: 取消进行中的验证会话
 */
public class PlayerListener implements Listener {

    private final NDPRPlugin plugin;

    public PlayerListener(NDPRPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        String name = p.getName();
        String uuid = p.getUniqueId().toString();

        InetSocketAddress addr = p.getAddress();
        InetAddress inet = addr != null ? addr.getAddress() : null;
        final String ip = (inet instanceof Inet4Address) ? inet.getHostAddress() : null;
        final String ipv6 = (inet instanceof Inet6Address) ? inet.getHostAddress() : null;

        // 异步: 保存信息 -> 封禁检查 -> 处理
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin,
                () -> handleJoin(name, uuid, ip, ipv6));
    }

    private void handleJoin(String name, String uuid, String ip, String ipv6) {
        var tr = plugin.tr();
        var config = plugin.ndprConfig();

        plugin.getLogger().info(tr.tr("ndpr.log.player_info", "player", name,
                "ip", String.valueOf(ip), "uuid", uuid, "ipv6", String.valueOf(ipv6)));

        plugin.playerStore().save(name, ip, uuid, ipv6);

        if (!plugin.banDb().exists()) {
            plugin.getLogger().warning(tr.tr("ndpr.warn.db_missing"));
            plugin.scheduleDownload(); // 后台下载, 不阻塞当前异步线程
            if (config.failClosed()) {
                plugin.getLogger().warning(tr.tr("ndpr.warn.fail_closed_rejected", "player", name));
                plugin.kickPlayer(name, "§c" + tr.tr("ndpr.kick.system_initializing"), false);
                return;
            }
            plugin.getLogger().warning(tr.tr("ndpr.warn.fail_open_allowed", "player", name));
        } else {
            try {
                String[] hit = plugin.banDb().queryBan(name, uuid, ip, ipv6);
                if (hit != null) {
                    plugin.getLogger().info(tr.tr("ndpr.log.banned_detected", "player", name, "table", hit[0]));
                    plugin.kickPlayer(name, "§c" + tr.tr("ndpr.kick.banned"), true);
                    return;
                }
            } catch (Exception e) {
                if (config.failClosed()) {
                    plugin.getLogger().warning(tr.tr("ndpr.warn.fail_closed_query_error", "player", name, "error", String.valueOf(e)));
                    plugin.kickPlayer(name, "§c" + tr.tr("ndpr.kick.system_initializing"), false);
                    return;
                }
                plugin.getLogger().warning(tr.tr("ndpr.warn.fail_open_query_error", "player", name, "error", String.valueOf(e)));
            }
        }

        if (config.checkHwid()) {
            plugin.hwidVerifier().start(name, ip, false);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.hwidVerifier().onQuit(event.getPlayer().getName());
    }
}
