package org.ndpr.bukkit;

import com.google.gson.JsonObject;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HWID 机器验证
 * 状态机: 创建会话 -> freeze 钉人 + 轮询 -> 完成/取消/超时
 * 线程模型: 验证循环在异步线程, 玩家操作(runTask)回主线程
 */
public class HwidVerifier {

    /** 一个进行中的验证会话 */
    public static class Session {
        final String player;
        final String ip;
        volatile boolean cancelled = false;
        volatile String sessionId = null;
        GameMode originalMode;
        Location anchor;
        long expiresAt;

        Session(String player, String ip) {
            this.player = player;
            this.ip = ip;
        }
    }

    private final NDPRPlugin plugin;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public HwidVerifier(NDPRPlugin plugin) {
        this.plugin = plugin;
    }

    /** 启动验证; force=true 绕过间隔检查(!!ndpr auth) */
    public void start(String playerName, String ip, boolean force) {
        Scheduler.runAsync(plugin, () -> startAsync(playerName, ip, force));
    }

    private void startAsync(String playerName, String ip, boolean force) {
        var tr = plugin.tr();
        var config = plugin.ndprConfig();

        if (config.token().isEmpty()) {
            plugin.getLogger().warning(tr.tr("ndpr.warn.token_missing_hwid"));
            return;
        }

        if (!force) {
            JsonObject record = plugin.hwidStore().get(playerName);
            if (record != null) {
                long lastTime = JsonStore.optLong(record, "time", 0);
                if (System.currentTimeMillis() / 1000.0 - lastTime < config.checkInterval() * 86400L) {
                    return; // 间隔内已验证
                }
            }
        }

        // 取消旧的同玩家会话
        Session old = sessions.remove(playerName);
        if (old != null) {
            old.cancelled = true;
            if (old.sessionId != null) {
                cancelApiSessionAsync(old.sessionId);
            }
        }

        Session session = new Session(playerName, ip);
        sessions.put(playerName, session);

        // 创建后端会话前先确认玩家仍在线(join/quit 竞态防御)
        Player onlineNow = plugin.getServer().getPlayerExact(playerName);
        if (onlineNow == null || !onlineNow.isOnline()) {
            session.cancelled = true;
            sessions.remove(playerName, session);
            return;
        }

        // ---- 玩家所在线程: 施加验证状态 ----
        Scheduler.runEntity(plugin, onlineNow, () -> applyVerifyState(session));

        runVerifyLoop(session);
    }

    /** 玩家线程: 记录原状态, 冒险模式 + 失明 + 标题 + 提示 */
    private void applyVerifyState(Session session) {
        Player p = plugin.getServer().getPlayerExact(session.player);
        if (p == null || !p.isOnline()) {
            session.cancelled = true;
            return;
        }
        var tr = plugin.tr();
        session.originalMode = p.getGameMode();
        session.anchor = p.getLocation().clone();
        p.setGameMode(GameMode.ADVENTURE);
        p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, Integer.MAX_VALUE, 0, false, false, true));
        p.sendTitle(tr.tr("ndpr.title.verify"), tr.tr("ndpr.subtitle.verify"), 10, 70, 20);
        p.sendMessage("§e" + tr.tr("ndpr.tell.verify_enabled"));
        p.sendMessage("§7" + tr.tr("ndpr.tell.verify_freeze_notice"));
    }

    /** 异步验证循环 */
    private void runVerifyLoop(Session session) {
        var tr = plugin.tr();
        var config = plugin.ndprConfig();

        JsonObject created = null;
        try {
            created = plugin.api().postAuth("/hwid/upd", createPayload(session), 10);
        } catch (Exception e) {
            plugin.getLogger().warning(tr.tr("ndpr.error.verify_start_failed", "player", session.player) + ": " + e);
        }

        if (created == null || !created.has("session_id")) {
            // 验证服务不可用: 踢出
            plugin.kickPlayer(session.player, "§c" + tr.tr("ndpr.kick.verify_unavailable"), false);
            sessions.remove(session.player, session);
            restorePlayer(session);
            return;
        }

        session.sessionId = created.get("session_id").getAsString();

        int verifyTimeout = config.verifyTimeout();
        long nowSec = System.currentTimeMillis() / 1000;
        long rawExpires = created.has("expires_at") && !created.get("expires_at").isJsonNull()
                ? created.get("expires_at").getAsLong() : 0;
        if (rawExpires <= 0) {
            rawExpires = nowSec + verifyTimeout;
        }
        session.expiresAt = Math.min(Math.max(rawExpires, nowSec + 30), nowSec + verifyTimeout);

        String verifyUrl = created.has("verify_url") ? created.get("verify_url").getAsString() : "";
        String playerName = session.player;

        // 玩家线程发送可点击验证链接
        if (!verifyUrl.isEmpty()) {
            Player online = plugin.getServer().getPlayerExact(playerName);
            if (online != null && online.isOnline()) {
                Scheduler.runEntity(plugin, online, () -> {
                    Player p = plugin.getServer().getPlayerExact(playerName);
                    if (p != null && p.isOnline()) {
                        Messages.sendOpenUrl(p, "§e" + tr.tr("ndpr.tell.click_verify"), verifyUrl, tr.tr("ndpr.hover.open_verify_page"));
                    }
                });
            }
        }

        long freezeIntervalMs = config.freezeInterval() * 1000L;
        long lastFreeze = 0;

        while (!session.cancelled && System.currentTimeMillis() / 1000 < session.expiresAt) {
            // 钉回锚点(玩家线程)
            if (session.anchor != null && System.currentTimeMillis() - lastFreeze >= freezeIntervalMs) {
                Location anchor = session.anchor;
                Player online = plugin.getServer().getPlayerExact(session.player);
                if (online != null && online.isOnline()) {
                    Scheduler.runEntity(plugin, online, () -> {
                        if (online.isOnline()) {
                            online.teleport(anchor);
                        }
                    });
                }
                lastFreeze = System.currentTimeMillis();
            }

            sleep(1000);
            if (session.cancelled) {
                break;
            }

            // 轮询验证状态
            JsonObject status = null;
            try {
                status = plugin.api().postAuth("/hwid/upd/check",
                        payload("session_id", session.sessionId), 3);
            } catch (Exception e) {
                continue;
            }
            if (status == null) {
                continue;
            }

            // 状态解析容错: 后端返回异常结构时跳过本轮而非炸掉循环(否则玩家会卡在验证状态)
            try {
                if (status.has("completed") && status.get("completed").getAsBoolean()) {
                    boolean banned;
                    String reason = null;
                    if (status.has("banned")) {
                        banned = status.get("banned").getAsBoolean();
                        reason = status.has("reason") && !status.get("reason").isJsonNull()
                                ? status.get("reason").getAsString() : null;
                    } else {
                        JsonObject has = null;
                        try {
                            has = plugin.api().postAuth("/hwid/has", payload("player_id", playerName), 5);
                        } catch (Exception e) {
                            // ignore
                        }
                        if (has == null) {
                            plugin.kickPlayer(playerName, "§c" + tr.tr("ndpr.kick.hwid_status_unknown"), false);
                            sessions.remove(playerName, session);
                            restorePlayer(session);
                            return;
                        }
                        banned = has.has("banned") && has.get("banned").getAsBoolean();
                        reason = has.has("reason") && !has.get("reason").isJsonNull()
                                ? has.get("reason").getAsString() : null;
                    }

                    if (banned) {
                        String kickReason = reason != null ? reason : tr.tr("ndpr.word.hwid_banned");
                        plugin.kickPlayer(playerName, "§c" + tr.tr("ndpr.kick.banned_with_reason", "reason", kickReason), true);
                    } else {
                        plugin.hwidStore().save(playerName, session.ip);
                        Player online = plugin.getServer().getPlayerExact(playerName);
                        if (online != null && online.isOnline()) {
                            Scheduler.runEntity(plugin, online, () -> {
                                if (online.isOnline()) {
                                    online.sendMessage("§a" + tr.tr("ndpr.tell.verify_done"));
                                }
                            });
                        }
                    }
                    sessions.remove(playerName, session);
                    restorePlayer(session);
                    return;
                }

                if (status.has("status")) {
                    String s = status.get("status").getAsString();
                    if ("cancelled".equals(s)) {
                        sessions.remove(playerName, session);
                        restorePlayer(session);
                        return;
                    }
                    if ("expired".equals(s)) {
                        break;
                    }
                }
            } catch (RuntimeException e) {
                // 响应结构异常: 记录并继续下一轮
                plugin.getLogger().warning("HWID check response malformed for " + playerName + ": " + e);
            }
        }

        restorePlayer(session);

        if (!session.cancelled) {
            // 超时踢出
            plugin.kickPlayer(session.player, "§c" + tr.tr("ndpr.kick.verify_timeout"), false);
        }
        sessions.remove(session.player, session);
    }

    /** 玩家线程: 清除失明效果并恢复原游戏模式 */
    private void restorePlayer(Session session) {
        String playerName = session.player;
        GameMode mode = session.originalMode;
        Player p = plugin.getServer().getPlayerExact(playerName);
        if (p == null || !p.isOnline()) {
            return;
        }
        Scheduler.runEntity(plugin, p, () -> {
            Player p2 = plugin.getServer().getPlayerExact(playerName);
            if (p2 == null || !p2.isOnline()) {
                return;
            }
            p2.removePotionEffect(PotionEffectType.BLINDNESS);
            if (mode != null) {
                p2.setGameMode(mode);
            }
        });
    }

    /** 玩家离开: 取消会话并通知后端 */
    public void onQuit(String playerName) {
        Session session = sessions.remove(playerName);
        if (session != null) {
            session.cancelled = true;
            if (session.sessionId != null) {
                cancelApiSessionAsync(session.sessionId);
            }
        }
    }

    /** 插件卸载: 取消全部会话 */
    public void cancelAll() {
        for (Session session : sessions.values()) {
            session.cancelled = true;
            if (session.sessionId != null) {
                cancelApiSessionAsync(session.sessionId);
            }
        }
        sessions.clear();
    }

    private void cancelApiSessionAsync(String sessionId) {
        Scheduler.runAsync(plugin, () -> {
            try {
                plugin.api().postAuth("/hwid/upd/cancel", payload("session_id", sessionId), 5);
            } catch (Exception ignored) {
            }
        });
    }

    // ============ helpers ============

    private static JsonObject payload(String key, String value) {
        JsonObject obj = new JsonObject();
        obj.addProperty(key, value);
        return obj;
    }

    private static JsonObject createPayload(Session session) {
        JsonObject obj = new JsonObject();
        obj.addProperty("player_id", session.player);
        if (session.ip != null && !session.ip.isEmpty()) {
            obj.addProperty("ip", session.ip);
        }
        return obj;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
