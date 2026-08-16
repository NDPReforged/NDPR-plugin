package org.ndpr.bukkit;

import com.google.gson.JsonObject;

/**
 * HWID 验证记录 (hwid_temp.json): 记录玩家通过验证的时间, check-interval 天内免验证
 */
public class HwidStore extends JsonStore {

    public HwidStore(NDPRPlugin plugin) {
        super(plugin, "hwid_temp.json");
    }

    /** 读取玩家验证记录, 返回 {ip, time} */
    public JsonObject get(String player) {
        JsonObject rec = getRecord(player);
        return rec != null ? rec : null;
    }

    public void save(String player, String ip) {
        JsonObject rec = json();
        rec.addProperty("ip", ip == null ? "" : ip);
        rec.addProperty("time", System.currentTimeMillis() / 1000.0);
        putRecord(player, rec);
    }
}
