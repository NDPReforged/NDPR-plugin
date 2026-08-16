package org.ndpr.bukkit;

import com.google.gson.JsonObject;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * NDPR (NotDPR) Bukkit/Paper 客户端主类
 * 云端封禁数据库 + 玩家加入检测 + HWID 设备验证
 * 仅使用 Bukkit API, 兼容 Spigot / Bukkit / Paper
 */
public final class NDPRPlugin extends JavaPlugin {

    public static final String VERSION = "2.1";
    public static final String DEFAULT_LANGUAGE = "zh_CN";

    private static NDPRPlugin instance;

    private ConfigManager config;
    private Translations tr;
    private ApiClient api;
    private BanDatabase banDb;
    private PlayerStore playerStore;
    private HwidStore hwidStore;
    private HwidVerifier hwidVerifier;

    private int autoDownloadTask = -1;

    public static NDPRPlugin get() {
        return instance;
    }

    public ConfigManager ndprConfig() { return config; }
    public Translations tr() { return tr; }
    public ApiClient api() { return api; }
    public BanDatabase banDb() { return banDb; }
    public PlayerStore playerStore() { return playerStore; }
    public HwidStore hwidStore() { return hwidStore; }
    public HwidVerifier hwidVerifier() { return hwidVerifier; }

    // ============ Lifecycle ============

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        config = new ConfigManager(this);
        if (!config.isValid()) {
            getLogger().severe("配置文件校验失败, 插件未加载: " + config.getError());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        tr = new Translations(this);
        api = new ApiClient(this);
        banDb = new BanDatabase(this);
        playerStore = new PlayerStore(this);
        hwidStore = new HwidStore(this);
        hwidVerifier = new HwidVerifier(this);

        getCommand("ndpr").setExecutor(new CommandHandler(this));
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);

        getLogger().info(tr.tr("ndpr.log.uuid", "uuid",
                config.uuid().isEmpty() ? tr.tr("ndpr.word.unset") : config.uuid()));

        getServer().getScheduler().runTaskAsynchronously(this, this::asyncInit);
    }

    @Override
    public void onDisable() {
        if (hwidVerifier != null) {
            hwidVerifier.cancelAll();
        }
        if (autoDownloadTask >= 0) {
            getServer().getScheduler().cancelTask(autoDownloadTask);
        }
        getLogger().info(tr != null ? tr.tr("ndpr.log.unloaded") : "NDPR plugin unloaded");
    }

    /** 异步初始化: UUID -> 封禁库 -> 更新检查 -> 启动定时下载 */
    private void asyncInit() {
        try {
            obtainUuid();
        } catch (Exception e) {
            getLogger().warning(tr.tr("ndpr.error.init_stage_failed",
                    "stage", tr.tr("ndpr.word.stage_uuid"), "error", String.valueOf(e)));
        }
        try {
            downloadBanDatabase();
        } catch (Exception e) {
            getLogger().warning(tr.tr("ndpr.error.init_stage_failed",
                    "stage", tr.tr("ndpr.word.stage_db"), "error", String.valueOf(e)));
        }
        try {
            checkPluginUpdate();
        } catch (Exception e) {
            getLogger().warning(tr.tr("ndpr.error.init_stage_failed",
                    "stage", tr.tr("ndpr.word.stage_update"), "error", String.valueOf(e)));
        }
        startAutoDownload();
        getLogger().info(tr.tr("ndpr.log.init_done"));
    }

    // ============ UUID ============

    /** 首次启动从云端获取服务器 UUID 并写回配置 */
    public void obtainUuid() throws Exception {
        if (config.apiUrl().isEmpty()) {
            throw new Exception("Error: " + tr.tr("ndpr.error.api_url_missing"));
        }
        if (!config.uuid().isEmpty()) {
            return;
        }
        getLogger().info(tr.tr("ndpr.log.getting_uuid"));

        JsonObject data = api.post("/uuid/getuuid", new JsonObject(), 10);
        if (data == null || !data.has("uuid")) {
            throw new Exception("Error: " + tr.tr("ndpr.error.get_uuid_invalid", "data", String.valueOf(data)));
        }
        String uuid = data.get("uuid").getAsString();
        config.setUuid(uuid);
        getLogger().info(tr.tr("ndpr.log.uuid_obtained", "uuid", uuid));
    }

    // ============ Ban database ============

    /** 同步下载封禁数据库(启动初始化用, 已在异步线程中) */
    public void downloadBanDatabase() {
        try {
            banDb.download(null);
        } catch (Exception e) {
            getLogger().warning("Ban database download failed: " + e);
        }
    }

    /** 后台 fire-and-forget 下载(玩家加入触发, 不阻塞调用线程) */
    public void scheduleDownload() {
        getServer().getScheduler().runTaskAsynchronously(this, this::downloadBanDatabase);
    }

    /** 命令触发的下载(防并发由 BanDatabase.inflight 统一保证) */
    public void downloadBanDatabaseAsync(CommandSender src) {
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                banDb.download(src);
            } catch (Exception e) {
                if (src != null) {
                    src.sendMessage("§c" + tr.tr("ndpr.reply.query_failed", "error", String.valueOf(e)));
                }
            }
        });
    }

    // ============ Kick / report ============

    /** 在主线程踢出玩家; 插件已禁用时静默跳过(关服场景) */
    public void kickPlayer(String playerName, String reason, boolean report) {
        try {
            getServer().getScheduler().runTask(this, () -> {
                Player p = getServer().getPlayerExact(playerName);
                if (p != null && p.isOnline()) {
                    p.kickPlayer(reason);
                }
            });
        } catch (IllegalStateException ignored) {
            // 插件已禁用, 任务无法注册(服务器关服中)
        }
        if (report) {
            reportKick();
        }
    }

    /** 上报拦截统计(失败静默) */
    public void reportKick() {
        try {
            api.postAuth("/stats/a", new JsonObject(), 5);
        } catch (Exception ignored) {
        }
    }

    // ============ Auto download ============

    private void startAutoDownload() {
        int interval = config.downloadInterval();
        if (interval <= 0) {
            getLogger().info(tr.tr("ndpr.log.auto_update_disabled"));
            return;
        }
        if (autoDownloadTask >= 0) {
            return;
        }
        autoDownloadTask = getServer().getScheduler()
                .runTaskTimerAsynchronously(this, this::downloadBanDatabase,
                        interval * 20L, interval * 20L)
                .getTaskId();
        getLogger().info(tr.tr("ndpr.log.auto_update_started", "interval", String.valueOf(interval)));
    }

    // ============ Update check ============

    /** 检查插件更新(GitHub releases) */
    public void checkPluginUpdate() {
        checkPluginUpdate(null);
    }

    public void checkPluginUpdate(CommandSender src) {
        try {
            JsonObject data = api.getPlain("https://api.github.com/repos/NDPReforged/NDPR-MCDR/releases/latest", 30);
            if (data == null || !data.has("tag_name")) {
                return;
            }
            String latestVersion = data.get("tag_name").getAsString().replaceFirst("^[vV]", "");
            boolean hasUpdate = compareVersions(VERSION, latestVersion) < 0;

            if (src != null) {
                src.sendMessage("§a" + tr.tr("ndpr.reply.checking_update"));
            }

            if (hasUpdate) {
                String releaseUrl = data.has("html_url") ? data.get("html_url").getAsString() : "";
                String notes = data.has("body") && !data.get("body").isJsonNull() ? data.get("body").getAsString() : "";
                if (notes.length() > 100) {
                    notes = notes.substring(0, 100) + "...";
                }
                if (src != null) {
                    src.sendMessage("§a" + tr.tr("ndpr.reply.update_found"));
                    src.sendMessage("§a" + tr.tr("ndpr.reply.current_version", "version", VERSION));
                    src.sendMessage("§a" + tr.tr("ndpr.reply.latest_version", "version", latestVersion));
                    if (!notes.isEmpty()) {
                        src.sendMessage("§a" + tr.tr("ndpr.reply.update_notes", "notes", notes));
                    }
                    src.sendMessage("§a" + tr.tr("ndpr.reply.download_url", "url", releaseUrl));
                    // Bukkit 端无 MCDR plg install 命令, 不再提供填充/复制安装命令组件
                }
                getLogger().info(tr.tr("ndpr.log.update_found", "latest", latestVersion, "current", VERSION, "url", releaseUrl));
            } else if (src != null) {
                src.sendMessage("§a" + tr.tr("ndpr.reply.up_to_date", "version", VERSION));
            }
        } catch (Exception e) {
            if (src != null) {
                src.sendMessage("§c" + tr.tr("ndpr.reply.query_failed", "error", String.valueOf(e)));
            }
        }
    }

    /** 版本比较: <0 表示 a 旧, =0 相同, >0 表示 a 新 */
    static int compareVersions(String a, String b) {
        String[] pa = a.replaceFirst("^[vV]", "").split("\\.");
        String[] pb = b.replaceFirst("^[vV]", "").split("\\.");
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int x = i < pa.length ? parsePart(pa[i]) : 0;
            int y = i < pb.length ? parsePart(pb[i]) : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    private static int parsePart(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
