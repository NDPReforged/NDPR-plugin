package org.ndpr.bukkit;

import com.google.gson.JsonObject;

/**
 * 玩家信息缓存 (player_info.json): 记录最近一次加入的 ip / uuid / ipv6
 */
public class PlayerStore extends JsonStore {

    public PlayerStore(NDPRPlugin plugin) {
        super(plugin, "player_info.json");
    }

    /** 读取玩家缓存信息, 返回 {ip, uuid, ipv6, timestamp} 的 JsonObject */
    public JsonObject get(String player) {
        JsonObject rec = getRecord(player);
        return rec != null ? rec : json();
    }

    public void save(String player, String ip, String uuid, String ipv6) {
        JsonObject rec = json();
        rec.addProperty("ip", ip);
        rec.addProperty("uuid", uuid);
        rec.addProperty("ipv6", ipv6);
        rec.addProperty("timestamp", System.currentTimeMillis() / 1000.0);
        putRecord(player, rec);
    }

    public String ip(String player) {
        return optString(get(player), "ip");
    }

    public String uuid(String player) {
        return optString(get(player), "uuid");
    }

    public String ipv6(String player) {
        return optString(get(player), "ipv6");
    }
}
