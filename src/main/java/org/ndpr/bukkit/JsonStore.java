package org.ndpr.bukkit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * JSON 文件存储基类 (带锁 + 内存缓存, 用于 player_info.json / hwid_temp.json)
 * 性能: 文件只在首次读取时解析一次, 之后读写内存缓存, 写时落盘
 */
public abstract class JsonStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, JsonObject>>() {}.getType();

    protected final File file;
    private final Object lock = new Object();
    private Map<String, JsonObject> cache = null;

    protected JsonStore(NDPRPlugin plugin, String fileName) {
        this.file = new File(new File(plugin.getDataFolder(), "data"), fileName);
    }

    /** 读取全部记录 (首次从磁盘加载, 之后走内存缓存) */
    protected Map<String, JsonObject> readAll() {
        synchronized (lock) {
            if (cache == null) {
                cache = loadFromDisk();
            }
            return cache;
        }
    }

    private Map<String, JsonObject> loadFromDisk() {
        if (!file.exists()) {
            return new HashMap<>();
        }
        try (Reader r = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            Map<String, JsonObject> data = GSON.fromJson(r, MAP_TYPE);
            return data != null ? data : new HashMap<>();
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    /** 写回全部记录 (更新内存缓存并落盘) */
    protected void writeAll(Map<String, JsonObject> records) {
        synchronized (lock) {
            cache = records;
            try {
                file.getParentFile().mkdirs();
                try (Writer w = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
                    GSON.toJson(records, w);
                }
            } catch (IOException e) {
                // 存储失败静默, 不影响主流程
            }
        }
    }

    /** 读单条记录 */
    protected JsonObject getRecord(String key) {
        return readAll().get(key);
    }

    /** 写单条记录 */
    protected void putRecord(String key, JsonObject record) {
        synchronized (lock) {
            Map<String, JsonObject> records = readAll();
            records.put(key, record);
            writeAll(records);
        }
    }

    public static JsonObject json() {
        return new JsonObject();
    }

    public static String optString(JsonObject obj, String key) {
        return (obj != null && obj.has(key) && !obj.get(key).isJsonNull()) ? obj.get(key).getAsString() : null;
    }

    public static long optLong(JsonObject obj, String key, long def) {
        return (obj != null && obj.has(key) && obj.get(key).isJsonPrimitive()) ? obj.get(key).getAsLong() : def;
    }
}
