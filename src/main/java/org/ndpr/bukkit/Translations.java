package org.ndpr.bukkit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 翻译系统: 从 resources/lang/*.json 加载, 外部语言文件覆盖内置表
 * 查找链: 当前语言 -> zh_CN -> 返回 key 原文
 */
public class Translations {

    private final Map<String, Map<String, String>> tables = new HashMap<>();
    private final String lang;

    public Translations(NDPRPlugin plugin) {
        // 扫描 lang 目录下全部 json
        try (InputStream is = plugin.getResource("lang/zh_CN.json")) {
            if (is != null) {
                loadTable("zh_CN", is);
            }
        } catch (Exception ignored) {
        }
        try (InputStream is = plugin.getResource("lang/en_us.json")) {
            if (is != null) {
                loadTable("en_us", is);
            }
        } catch (Exception ignored) {
        }

        String raw = plugin.ndprConfig().language().trim().toLowerCase().replace('-', '_');
        this.lang = tables.containsKey(raw) ? raw : (tables.containsKey(NDPRPlugin.DEFAULT_LANGUAGE) ? NDPRPlugin.DEFAULT_LANGUAGE : raw);
    }

    private void loadTable(String lang, InputStream is) {
        JsonObject root = JsonParser.parseReader(new InputStreamReader(is, StandardCharsets.UTF_8)).getAsJsonObject();
        Map<String, String> table = tables.computeIfAbsent(lang, k -> new HashMap<>());
        for (Map.Entry<String, JsonElement> e : root.entrySet()) {
            if (e.getValue().isJsonPrimitive()) {
                table.put(e.getKey(), e.getValue().getAsString());
            }
        }
    }

    /** 取翻译, 支持 {key} 占位符替换; kv 形如 "player", name, "ip", ip */
    public String tr(String key, String... kv) {
        Map<String, String> table = tables.get(lang);
        String text = table != null ? table.get(key) : null;
        if (text == null) {
            Map<String, String> fallback = tables.get(NDPRPlugin.DEFAULT_LANGUAGE);
            text = fallback != null ? fallback.get(key) : null;
        }
        if (text == null) {
            return key;
        }
        if (kv.length >= 2) {
            for (int i = 0; i + 1 < kv.length; i += 2) {
                if (kv[i + 1] != null) {
                    text = text.replace("{" + kv[i] + "}", kv[i + 1]);
                }
            }
        }
        return text;
    }
}
