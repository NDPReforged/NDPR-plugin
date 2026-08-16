package org.ndpr.bukkit;

import com.google.gson.JsonObject;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * /ndpr 命令分发
 * 管理命令需要权限 ndpr.admin
 */
public class CommandHandler implements CommandExecutor {

    private final NDPRPlugin plugin;

    public CommandHandler(NDPRPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            help(sender);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "help" -> help(sender);
            case "d", "download" -> requireAdmin(sender, () -> download(sender));
            case "ban" -> requireAdmin(sender, () -> ban(sender, args));
            case "check" -> check(sender, args);
            case "reload" -> requireAdmin(sender, () -> reload(sender));
            case "cu", "checkupdate" -> requireAdmin(sender, () -> checkUpdate(sender));
            case "auth" -> requireAdmin(sender, () -> auth(sender, args));
            default -> help(sender);
        }
        return true;
    }

    private interface AdminAction {
        void run();
    }

    private void requireAdmin(CommandSender sender, AdminAction action) {
        if (!sender.hasPermission("ndpr.admin")) {
            sender.sendMessage("§c" + plugin.tr().tr("ndpr.reply.permission_denied"));
            return;
        }
        action.run();
    }

    // ============ help ============

    private void help(CommandSender sender) {
        var tr = plugin.tr();
        sender.sendMessage("§6========== §b" + tr.tr("ndpr.help.title") + " §6==========");
        sender.sendMessage("§e" + tr.tr("ndpr.help.version", "version", NDPRPlugin.VERSION));
        sender.sendMessage("§e" + tr.tr("ndpr.help.author"));
        sender.sendMessage(tr.tr("ndpr.help.qq_group"));
        sender.sendMessage("");
        sender.sendMessage("§b" + tr.tr("ndpr.help.commands"));
        sender.sendMessage("§f/ndpr help §7- " + tr.tr("ndpr.help.desc.help"));
        sender.sendMessage("§f/ndpr d / download §7- " + tr.tr("ndpr.help.desc.download"));
        sender.sendMessage("§f/ndpr ban <ID> <reason> §7- " + tr.tr("ndpr.help.desc.ban"));
        sender.sendMessage("§f/ndpr check <ID/IP/UUID> §7- " + tr.tr("ndpr.help.desc.check"));
        sender.sendMessage("§f/ndpr reload §7- " + tr.tr("ndpr.help.desc.reload"));
        sender.sendMessage("§f/ndpr cu / checkupdate §7- " + tr.tr("ndpr.help.desc.checkupdate"));
        sender.sendMessage("§f/ndpr auth <ID> §7- " + tr.tr("ndpr.help.desc.auth"));
        sender.sendMessage("");
        sender.sendMessage(tr.tr("ndpr.help.footer"));
    }

    // ============ download ============

    private void download(CommandSender sender) {
        sender.sendMessage("§e" + plugin.tr().tr("ndpr.reply.downloading"));
        plugin.downloadBanDatabaseAsync(sender);
    }

    // ============ ban ============

    private void ban(CommandSender sender, String[] args) {
        var tr = plugin.tr();
        if (args.length < 3) {
            sender.sendMessage("§c" + tr.tr("ndpr.reply.ban_reason_required"));
            sender.sendMessage("§7" + tr.tr("ndpr.reply.ban_usage"));
            return;
        }
        String player = args[1];
        String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
        Scheduler.runAsync(plugin, () -> addBanPlayer(sender, player, reason));
    }

    /** 提交封禁审核 */
    private void addBanPlayer(CommandSender src, String player, String reason) {
        var tr = plugin.tr();
        var config = plugin.ndprConfig();

        if (config.token().isEmpty()) {
            src.sendMessage("§c" + tr.tr("ndpr.reply.token_not_configured"));
            return;
        }

        src.sendMessage("§e" + tr.tr("ndpr.reply.getting_player_info", "player", player));

        JsonObject info = plugin.playerStore().get(player);
        String playerIp = JsonStore.optString(info, "ip");
        String playerUuid = JsonStore.optString(info, "uuid");
        String playerIpv6 = JsonStore.optString(info, "ipv6");

        if (playerIp == null && playerUuid == null && playerIpv6 == null) {
            src.sendMessage("§c" + tr.tr("ndpr.reply.player_info_not_found"));
            src.sendMessage("§7" + tr.tr("ndpr.reply.player_info_hint"));
            return;
        }

        StringBuilder infoLine = new StringBuilder();
        if (playerIp != null) infoLine.append("IP: ").append(playerIp);
        if (playerIpv6 != null) infoLine.append(infoLine.length() > 0 ? ", " : "").append("IPv6: ").append(playerIpv6);
        if (playerUuid != null) infoLine.append(infoLine.length() > 0 ? ", " : "").append("UUID: ").append(playerUuid);
        if (infoLine.length() > 0) {
            src.sendMessage("§e" + tr.tr("ndpr.reply.info_obtained", "info", infoLine.toString()));
        }

        src.sendMessage("§e" + tr.tr("ndpr.reply.ban_reason_echo", "reason", reason));

        JsonObject data = new JsonObject();
        data.addProperty("player_id", player);
        data.addProperty("ip", playerIp);
        data.addProperty("ipv6", playerIpv6);
        data.addProperty("uuid", playerUuid);
        data.addProperty("onlinemode", config.onlineMode());
        data.addProperty("reason", reason);

        src.sendMessage("§e" + tr.tr("ndpr.reply.submitting"));

        try {
            JsonObject result = plugin.api().postAuth("/check/uploader", data, 10);
            if (result != null && result.has("result") && "success".equals(result.get("result").getAsString())) {
                String checkId = result.has("check_id") ? result.get("check_id").getAsString() : "";
                src.sendMessage("§a" + tr.tr("ndpr.reply.submit_success"));
                src.sendMessage("§7" + tr.tr("ndpr.reply.check_id", "check_id", checkId));
                src.sendMessage("§7" + tr.tr("ndpr.reply.wait_review"));
            } else {
                String message = (result != null && result.has("message") && !result.get("message").isJsonNull())
                        ? result.get("message").getAsString() : tr.tr("ndpr.reply.unknown_error");
                src.sendMessage("§c" + tr.tr("ndpr.reply.submit_failed", "message", message));
            }
        } catch (ApiClient.HttpStatusException e) {
            if (e.status == 403) {
                src.sendMessage("§c" + tr.tr("ndpr.reply.no_upload_permission"));
            } else {
                src.sendMessage("§c" + tr.tr("ndpr.reply.submit_failed_http", "code", String.valueOf(e.status)));
                String body = e.body != null && e.body.length() > 200 ? e.body.substring(0, 200) : e.body;
                src.sendMessage("§7" + tr.tr("ndpr.reply.response_body", "body", String.valueOf(body)));
            }
        } catch (Exception e) {
            src.sendMessage("§c" + tr.tr("ndpr.reply.connection_error"));
        }
    }

    // ============ check ============

    private void check(CommandSender sender, String[] args) {
        var tr = plugin.tr();
        if (args.length < 2) {
            sender.sendMessage("§7" + tr.tr("ndpr.help.desc.check"));
            return;
        }
        String target = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        Scheduler.runAsync(plugin, () -> {
            if (!plugin.banDb().exists()) {
                sender.sendMessage("§c" + tr.tr("ndpr.reply.no_data"));
                return;
            }
            String type = detectIdentifierType(target);
            try {
                if ("ip".equals(type) || "ipv6".equals(type) || "uuid".equals(type)) {
                    String[] hit = plugin.banDb().queryByIdentifier(type, target);
                    if (hit != null) {
                        sender.sendMessage("§7" + tr.tr("ndpr.label.player", "player", hit[1]));
                        sender.sendMessage("§7" + tr.tr("ndpr.label.reason", "reason", hit[2]));
                        sender.sendMessage("§7" + tr.tr("ndpr.label.ban_time", "time", hit[3]));
                    } else {
                        sender.sendMessage(tr.tr("ndpr.reply.record_not_found", "type", type, "value", target));
                    }
                } else {
                    checkByPlayerName(sender, target);
                }
            } catch (Exception e) {
                sender.sendMessage("§c" + tr.tr("ndpr.reply.query_failed", "error", String.valueOf(e)));
            }
        });
    }

    private void checkByPlayerName(CommandSender sender, String player) {
        var tr = plugin.tr();
        try {
            String[] hit = plugin.banDb().queryBan(player, null, null, null);
            if (hit != null) {
                sender.sendMessage("§c" + tr.tr("ndpr.reply.banned_in_table", "player", player, "table", hit[0]));
                if (sender.hasPermission("ndpr.admin")) {
                    sender.sendMessage("§7" + tr.tr("ndpr.label.reason", "reason", hit[2]));
                    sender.sendMessage("§7" + tr.tr("ndpr.label.ban_time", "time", hit[3]));
                }
            } else {
                sender.sendMessage("§a" + tr.tr("ndpr.reply.not_banned", "player", player));
                List<String> suggestions = plugin.banDb().fuzzySuggest(player, 5);
                if (!suggestions.isEmpty()) {
                    sender.sendMessage("§7" + tr.tr("ndpr.reply.fuzzy_suggestion"));
                    for (String name : suggestions) {
                        // 点击直接展开查询, 无需手动回车
                        Messages.sendRunCommand(sender, "§8" + name + " §7[" + tr.tr("ndpr.reply.fuzzy_expand") + "]",
                                "/ndpr check " + (name.contains(" ") ? "\"" + name + "\"" : name),
                                tr.tr("ndpr.reply.fuzzy_hover", "player", name));
                    }
                }
            }
        } catch (Exception e) {
            sender.sendMessage("§c" + tr.tr("ndpr.reply.query_failed", "error", String.valueOf(e)));
        }
    }

    /** 识别标识符类型: ip / ipv6 / uuid / id (纯字面量正则判断, 不触发 DNS 解析) */
    static String detectIdentifierType(String target) {
        if (target.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            return "ip";
        }
        if (target.indexOf(':') >= 0 && target.matches("[0-9a-fA-F:]+")) {
            return "ipv6";
        }
        if (target.length() == 36 && target.chars().filter(c -> c == '-').count() == 4) {
            return "uuid";
        }
        return "id";
    }

    // ============ reload ============

    private void reload(CommandSender sender) {
        var tr = plugin.tr();
        sender.sendMessage("§e" + tr.tr("ndpr.reply.reloading"));
        Scheduler.runAsync(plugin, () -> {
            try {
                plugin.ndprConfig().reload();
                if (!plugin.ndprConfig().isValid()) {
                    sender.sendMessage("§c" + tr.tr("ndpr.reply.reload_failed", "error", plugin.ndprConfig().getError()));
                    return;
                }
                plugin.banDb().download(sender);
                sender.sendMessage("§a" + tr.tr("ndpr.reply.reloaded"));
            } catch (Exception e) {
                sender.sendMessage("§c" + tr.tr("ndpr.reply.reload_failed", "error", String.valueOf(e)));
            }
        });
    }

    // ============ checkupdate ============

    private void checkUpdate(CommandSender sender) {
        Scheduler.runAsync(plugin, () -> plugin.checkPluginUpdate(sender));
    }

    // ============ auth ============

    private void auth(CommandSender sender, String[] args) {
        var tr = plugin.tr();
        if (args.length < 2) {
            sender.sendMessage("§7" + tr.tr("ndpr.reply.auth_usage"));
            return;
        }
        String player = args[1];
        Player online = plugin.getServer().getPlayerExact(player);
        if (online == null || !online.isOnline()) {
            sender.sendMessage("§c" + tr.tr("ndpr.reply.auth_player_not_online", "player", player));
            return;
        }
        String ip = JsonStore.optString(plugin.playerStore().get(player), "ip");
        sender.sendMessage("§e" + tr.tr("ndpr.reply.auth_starting", "player", player));
        plugin.hwidVerifier().start(player, ip, true);
    }
}
