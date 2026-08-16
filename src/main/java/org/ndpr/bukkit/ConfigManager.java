package org.ndpr.bukkit;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * 配置文件管理: 读取/校验/写入
 * 对应 MCDR 版 config.toml 的全部字段
 * 每次 getter 实时读取 plugin.getConfig(), reload 后自动生效
 */
public class ConfigManager {

    private final NDPRPlugin plugin;
    private String error = null;

    public ConfigManager(NDPRPlugin plugin) {
        this.plugin = plugin;
        validate();
    }

    private FileConfiguration cfg() {
        return plugin.getConfig();
    }

    private void validate() {
        String apiUrl = cfg().getString("api-url", "");
        if (apiUrl.isEmpty()) {
            error = "配置文件错误:字段api-url";
            return;
        }
        if (!apiUrl.startsWith("http://") && !apiUrl.startsWith("https://")) {
            error = "配置文件错误:字段api-url (api_url需以 http:// 或 https:// 开头)";
            return;
        }

        String onlineMode = cfg().getString("onlinemode", "");
        if (onlineMode.isEmpty()) {
            error = "请在ndpr配置文件里填写服务器类型(正版或离线),否则插件不会正常加载";
            return;
        }
        if (!onlineMode.equalsIgnoreCase("true") && !onlineMode.equalsIgnoreCase("false")) {
            error = "配置文件错误:字段onlinemode";
            return;
        }

        String language = cfg().getString("language", "");
        if (language.isEmpty()) {
            error = "配置文件错误:字段language";
            return;
        }

        if (!cfg().isInt("download-interval") || cfg().getInt("download-interval") < 0) {
            error = "配置文件错误:字段download-interval (需为非负整数, 0表示禁用自动更新)";
            return;
        }
        if (!cfg().isBoolean("check-hwid")) {
            error = "配置文件错误:字段check-hwid (请填写 true 或 false)";
            return;
        }
        if (!cfg().isInt("check-interval") || cfg().getInt("check-interval") < 0) {
            error = "配置文件错误:字段check-interval";
            return;
        }
        if (!cfg().isInt("verify-timeout") || cfg().getInt("verify-timeout") < 30 || cfg().getInt("verify-timeout") > 600) {
            error = "配置文件错误:字段verify-timeout (需为30-600的整数,单位秒)";
            return;
        }
        if (!cfg().isInt("freeze-interval") || cfg().getInt("freeze-interval") < 1 || cfg().getInt("freeze-interval") > 60) {
            error = "配置文件错误:字段freeze-interval (需为1-60的整数,单位秒)";
            return;
        }
    }

    public boolean isValid() {
        return error == null;
    }

    public String getError() {
        return error;
    }

    // ============ Getters ============

    public String apiUrl() {
        return cfg().getString("api-url", "https://api.ndpreforged.com");
    }

    public String language() {
        return cfg().getString("language", NDPRPlugin.DEFAULT_LANGUAGE);
    }

    public String token() {
        return cfg().getString("token", "");
    }

    public String uuid() {
        return cfg().getString("uuid", "");
    }

    public boolean onlineMode() {
        return cfg().getString("onlinemode", "false").equalsIgnoreCase("true");
    }

    public boolean checkHwid() {
        return cfg().getBoolean("check-hwid", false);
    }

    public int checkInterval() {
        return cfg().getInt("check-interval", 3);
    }

    public int downloadInterval() {
        return cfg().getInt("download-interval", 900);
    }

    public boolean failClosed() {
        return cfg().getBoolean("fail-closed", false);
    }

    public int verifyTimeout() {
        // 防御钳制: 30-600 秒 (对应 MCDR 版 _verify_timeout)
        int v = cfg().getInt("verify-timeout", 60);
        return Math.max(30, Math.min(600, v));
    }

    public int freezeInterval() {
        // 防御钳制: 1-60 秒 (对应 MCDR 版 _freeze_interval)
        int v = cfg().getInt("freeze-interval", 1);
        return Math.max(1, Math.min(60, v));
    }

    public String serverTypeLabel() {
        return onlineMode() ? plugin.tr().tr("ndpr.word.online") : plugin.tr().tr("ndpr.word.offline");
    }

    // ============ Write ============

    public void setUuid(String uuid) {
        cfg().set("uuid", uuid);
        plugin.saveConfig();
    }

    /** 重新加载配置并重新校验 (!!ndpr reload) */
    public void reload() {
        plugin.reloadConfig();
        error = null;
        validate();
    }
}
