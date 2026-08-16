# NDPR Bukkit/Paper 客户端插件

NDPR (NotDPR) 云端封禁系统的 **Bukkit/Spigot/Paper** 客户端插件。
由 `../MCDR` 版 MCDR 插件转写而来，功能完全对齐：云端封禁数据库、玩家加入检测、HWID 设备验证。

## 特性

- **兼容性**：基于 Paper API 编译，**只使用 Bukkit API**，一个 jar 同时支持 Bukkit / Spigot / Paper
- **版本支持**：MC 1.20.1+（`api-version: 1.20`，Java 17 字节码，1.20.1~1.21.x 通用）
- **无日志解析**：相比 MCDR 版，IP/UUID 直接从 Bukkit 事件 API 获取（`PlayerJoinEvent`），不再依赖解析 `latest.log`
- **内嵌 SQLite**：封禁数据库驱动（sqlite-jdbc）已打进 jar，无需额外安装
- **可点击验证链接**：HWID 验证链接使用 bungeecord-chat 组件，可点击直达

## 构建

```bat
gradlew.bat build
```

产物：`build/libs/NDPR-Bukkit-2.1.0.jar`（含 sqlite-jdbc，单文件）

## 安装

1. 将 `NDPR-Bukkit-2.1.0.jar` 放入服务端 `plugins/` 目录
2. 启动服务端，插件生成 `plugins/NDPR-Bukkit/config.yml`
3. 填写 `onlinemode`（`true`=正版服 / `false`=离线服，**必填**，否则插件不加载）
4. 如需封禁功能，填写 `token`（带上自动获取的 UUID 去 https://ndpreforged.com 绑定邮箱获取）
5. 重启服务端或 `/reload confirm`

## 配置 (config.yml)

| 字段 | 说明 | 默认 |
|---|---|---|
| `api-url` | API 地址 | `https://api.ndpreforged.com` |
| `language` | 语言 zh_CN / en_us | `zh_CN` |
| `token` | 官网发放的 Token | 空 |
| `uuid` | 服务器 UUID（自动获取） | 空 |
| `onlinemode` | **必填**：true=正版 / false=离线 | 空 |
| `check-hwid` | 启用 HWID 机器验证 | `false` |
| `check-interval` | 验证通过后免检天数 | `3` |
| `download-interval` | 封禁库自动更新间隔（秒），0=禁用 | `900` |
| `fail-closed` | 库缺失时踢出玩家（安全优先） | `false` |
| `verify-timeout` | 验证超时（秒，30~600） | `60` |
| `freeze-interval` | 验证期间钉回坐标间隔（秒，1~60） | `1` |

## 命令

| 命令 | 说明 | 权限 |
|---|---|---|
| `/ndpr` `/ndpr help` | 帮助 | 所有人 |
| `/ndpr d` `/ndpr download` | 手动下载封禁库 | `ndpr.admin` |
| `/ndpr ban <玩家> <原因>` | 提交封禁审核 | `ndpr.admin` |
| `/ndpr check <ID/IP/UUID>` | 检查封禁状态（未命中时模糊建议） | 所有人 |
| `/ndpr reload` | 重载配置并重新下载封禁库 | `ndpr.admin` |
| `/ndpr cu` `/ndpr checkupdate` | 检查插件更新 | `ndpr.admin` |
| `/ndpr auth <玩家>` | 强制触发玩家 HWID 验证 | `ndpr.admin` |

权限 `ndpr.admin` 默认授予 OP。

## 数据文件

- `plugins/NDPR-Bukkit/config.yml` — 配置
- `plugins/NDPR-Bukkit/data/ban_database.db` — 封禁数据库（SQLite，自动更新）
- `plugins/NDPR-Bukkit/data/player_info.json` — 玩家 IP/UUID 缓存（供 ban 命令使用）
- `plugins/NDPR-Bukkit/data/hwid_temp.json` — HWID 验证通过记录

## 与后端 API 的交互

`POST /uuid/getuuid` · `GET /bans/download`（+`/done`）· `POST /check/uploader` · `POST /stats/a` · `POST /hwid/upd`（+`/check` `/cancel`）· `POST /hwid/has`

---

© 2026 NDPR Team · 官网 https://ndpreforged.com · QQ群 232760327
