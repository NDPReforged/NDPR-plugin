package org.ndpr.bukkit;

import com.google.gson.JsonObject;
import org.bukkit.command.CommandSender;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 封禁数据库 (SQLite, online/offline 两表)
 * 结构自适应: 动态检测时间列(ban_time/last_seen)与 mcuuid 列
 * 下载: 鉴权 -> 临时URL -> 下载校验 -> 原子替换
 * 并发: 内置 inflight 锁, 定时/手动/join 触发共用同一把锁, 防止并发写同一 tmp 文件
 */
public class BanDatabase {

    private final NDPRPlugin plugin;
    private final File dbFile;
    private final Map<String, Set<String>> schemaCache = new ConcurrentHashMap<>();
    private final AtomicBoolean inflight = new AtomicBoolean(false);

    public BanDatabase(NDPRPlugin plugin) {
        this.plugin = plugin;
        this.dbFile = new File(plugin.getDataFolder(), "data/ban_database.db");
    }

    public boolean exists() {
        return dbFile.exists();
    }

    public File file() {
        return dbFile;
    }

    private Connection open(File file) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
    }

    // ============ Schema introspection ============

    private Set<String> tableColumns(Connection conn, String table) {
        return schemaCache.computeIfAbsent(table, t -> {
            Set<String> cols = new HashSet<>();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("PRAGMA table_info(" + t + ")")) {
                while (rs.next()) {
                    cols.add(rs.getString("name").toLowerCase());
                }
            } catch (SQLException ignored) {
            }
            return cols;
        });
    }

    private String timeCol(Connection conn, String table) {
        Set<String> cols = tableColumns(conn, table);
        if (cols.contains("ban_time")) return "ban_time";
        if (cols.contains("last_seen")) return "last_seen";
        return "online".equals(table) ? "last_seen" : "ban_time";
    }

    private boolean hasMcUuid(Connection conn, String table) {
        return tableColumns(conn, table).contains("mcuuid");
    }

    // ============ Download ============

    /**
     * 下载封禁数据库. 流程:
     * GET /bans/download -> 取 url -> 下载 -> tmp 校验 -> 原子替换 -> 回报 done
     * 并发保护: inflight 锁, 重复触发返回 false
     */
    public boolean download(CommandSender src) throws Exception {
        if (!inflight.compareAndSet(false, true)) {
            if (src != null) {
                src.sendMessage("§e" + plugin.tr().tr("ndpr.reply.download_inflight"));
            }
            return false;
        }
        try {
            return doDownload(src);
        } finally {
            inflight.set(false);
        }
    }

    private boolean doDownload(CommandSender src) throws Exception {
        String token = plugin.ndprConfig().token();
        if (token.isEmpty()) {
            String msg = plugin.tr().tr("ndpr.warn.token_missing");
            if (src != null) {
                plugin.getLogger().warning(msg);
                src.sendMessage("§c" + msg);
            } else {
                // 定时/自动路径: 降为 info, 避免每 15 分钟刷一条 warning
                plugin.getLogger().info("NDPR token not configured, skip auto ban database download");
            }
            return false;
        }
        String apiUrl = plugin.ndprConfig().apiUrl();
        if (apiUrl.isEmpty()) {
            String msg = plugin.tr().tr("ndpr.error.db_api_unconfigured");
            plugin.getLogger().severe(msg);
            if (src != null) src.sendMessage("§c" + msg);
            return false;
        }

        JsonObject data = plugin.api().getAuth("/bans/download", 30);
        if (data == null || !data.has("url")) {
            throw new Exception(plugin.tr().tr("ndpr.error.db_download_no_url"));
        }
        String downloadUrl = data.get("url").getAsString();

        byte[] bytes = plugin.api().getBytes(downloadUrl, 60);

        File tmpFile = new File(dbFile.getParentFile(), dbFile.getName() + ".tmp");
        dbFile.getParentFile().mkdirs();
        Files.write(tmpFile.toPath(), bytes);

        // 校验: 两表可查询
        try {
            int count = 0;
            try (Connection conn = open(tmpFile)) {
                for (String table : new String[]{"online", "offline"}) {
                    try (Statement st = conn.createStatement();
                         ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
                        if (rs.next()) {
                            count += rs.getInt(1);
                        }
                    }
                }
            }
            Files.move(tmpFile.toPath(), dbFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            schemaCache.clear();

            String detail = plugin.tr().tr("ndpr.log.db_updated", "count", String.valueOf(count));
            plugin.getLogger().info(detail);
            if (src != null) {
                src.sendMessage("§a" + plugin.tr().tr("ndpr.reply.db_download_success"));
                src.sendMessage("§7" + detail);
            }
        } catch (SQLException e) {
            Files.deleteIfExists(tmpFile.toPath());
            throw new Exception(plugin.tr().tr("ndpr.error.db_file_invalid", "error", String.valueOf(e)));
        }

        // 回报下载完成(失败静默)
        try {
            plugin.api().postAuth("/bans/download/done", new JsonObject(), 10);
        } catch (Exception ignored) {
        }
        return true;
    }

    // ============ Queries ============

    /** 封禁命中记录 {table, player, reason, time}; 未命中返回 null */
    public String[] queryBan(String player, String uuid, String ip, String ipv6) throws SQLException {
        try (Connection conn = open(dbFile)) {
            for (String table : new String[]{"online", "offline"}) {
                String sql;
                if (hasMcUuid(conn, table)) {
                    sql = "SELECT player, ban_reason, " + timeCol(conn, table) +
                            " FROM " + table + " WHERE mcuuid = ? OR player = ? OR ip = ? OR ipv6 = ? LIMIT 1";
                } else {
                    sql = "SELECT player, ban_reason, " + timeCol(conn, table) +
                            " FROM " + table + " WHERE player = ? OR ip = ? OR ipv6 = ? LIMIT 1";
                }
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, player);
                    if (hasMcUuid(conn, table)) {
                        ps.setString(2, uuid);
                        ps.setString(3, ip);
                        ps.setString(4, ipv6);
                    } else {
                        ps.setString(2, ip);
                        ps.setString(3, ipv6);
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            return new String[]{table, rs.getString(1), rs.getString(2), rs.getString(3)};
                        }
                    }
                }
            }
        }
        return null;
    }

    /** 按标识符类型查询 (ip / ipv6 / uuid); 返回 {table, player, reason, time} 或 null */
    public String[] queryByIdentifier(String type, String value) throws SQLException {
        try (Connection conn = open(dbFile)) {
            for (String table : new String[]{"online", "offline"}) {
                String col;
                switch (type) {
                    case "ip" -> col = "ip";
                    case "ipv6" -> col = "ipv6";
                    case "uuid" -> {
                        if (!hasMcUuid(conn, table)) continue;
                        col = "mcuuid";
                    }
                    default -> {
                        return null;
                    }
                }
                String sql = "SELECT player, ban_reason, " + timeCol(conn, table) +
                        " FROM " + table + " WHERE " + col + " = ? LIMIT 1";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, value);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            return new String[]{table, rs.getString(1), rs.getString(2), rs.getString(3)};
                        }
                    }
                }
            }
        }
        return null;
    }

    /** 模糊匹配玩家名 (!!ndpr check 未命中时建议) */
    public List<String> fuzzySuggest(String query, int limit) {
        if (!exists()) {
            return Collections.emptyList();
        }
        List<String> matches = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String pattern = "%" + query.toLowerCase() + "%";
        try (Connection conn = open(dbFile)) {
            for (String table : new String[]{"online", "offline"}) {
                String sql = "SELECT player FROM " + table + " WHERE LOWER(player) LIKE ? LIMIT " + (limit * 2);
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, pattern);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next() && matches.size() < limit) {
                            String name = rs.getString(1);
                            if (seen.add(name)) {
                                matches.add(name);
                            }
                        }
                    }
                }
            }
        } catch (SQLException ignored) {
        }
        return matches;
    }
}
