package org.ndpr.bukkit;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 调度工具: 同时兼容 Bukkit/Spigot/Paper 与 Folia
 *
 * Folia 采用分区多线程, 没有全局主线程, BukkitScheduler 不可用;
 * 通过反射调用 io.papermc.paper.threadedregions.scheduler API,
 * 运行时检测 Folia 并自动切换, 编译期无 Folia 依赖。
 *
 * 线程模型约定:
 * - runEntity: 实体所在区域线程 (Folia EntityScheduler) / 主线程 (Paper)
 * - runGlobal: 全局区域线程 (Folia GlobalRegionScheduler) / 主线程 (Paper)
 * - runAsync:  异步线程 (两平台语义一致)
 */
public final class Scheduler {

    private static final boolean FOLIA;
    private static Method getGlobalRegionScheduler;
    private static Method getAsyncScheduler;

    static {
        boolean folia = false;
        try {
            Class.forName("io.papermc.paper.threadedregions.scheduler.RegionScheduler");
            folia = true;
        } catch (ClassNotFoundException ignored) {
        }
        FOLIA = folia;
        if (FOLIA) {
            try {
                getGlobalRegionScheduler = Bukkit.class.getMethod("getGlobalRegionScheduler");
                getAsyncScheduler = Bukkit.class.getMethod("getAsyncScheduler");
            } catch (NoSuchMethodException ignored) {
            }
        }
    }

    private Scheduler() {
    }

    public static boolean isFolia() {
        return FOLIA;
    }

    /** 全局区域线程 (Folia) / 主线程 (Paper) 执行 */
    public static void runGlobal(Plugin plugin, Runnable task) {
        if (FOLIA && getGlobalRegionScheduler != null) {
            try {
                Object scheduler = getGlobalRegionScheduler.invoke(null);
                scheduler.getClass().getMethod("run", Plugin.class, Runnable.class)
                        .invoke(scheduler, plugin, task);
                return;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        try {
            Bukkit.getScheduler().runTask(plugin, task);
        } catch (RuntimeException ignored) {
            // 插件已禁用时任务无法注册 (关服场景)
        }
    }

    /** 实体所在区域线程 (Folia) / 主线程 (Paper) 执行 */
    public static void runEntity(Plugin plugin, Player player, Runnable task) {
        if (FOLIA) {
            try {
                Method getScheduler = player.getClass().getMethod("getScheduler");
                Object entityScheduler = getScheduler.invoke(player);
                entityScheduler.getClass().getMethod("run", Plugin.class, Runnable.class, Runnable.class)
                        .invoke(entityScheduler, plugin, task, null);
                return;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        try {
            Bukkit.getScheduler().runTask(plugin, task);
        } catch (RuntimeException ignored) {
        }
    }

    /** 异步执行 */
    public static void runAsync(Plugin plugin, Runnable task) {
        if (FOLIA && getAsyncScheduler != null) {
            try {
                Object scheduler = getAsyncScheduler.invoke(null);
                scheduler.getClass().getMethod("runNow", Plugin.class, Consumer.class)
                        .invoke(scheduler, plugin, (Consumer<Object>) ignored -> task.run());
                return;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        } catch (RuntimeException ignored) {
        }
    }

    /** 异步定时任务, 返回句柄可取消 */
    public static TaskHandle runTimerAsync(Plugin plugin, Runnable task, long delayTicks, long periodTicks) {
        if (FOLIA && getAsyncScheduler != null) {
            try {
                Object scheduler = getAsyncScheduler.invoke(null);
                Object scheduled = scheduler.getClass()
                        .getMethod("runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class, TimeUnit.class)
                        .invoke(scheduler, plugin, (Consumer<Object>) ignored -> task.run(),
                                delayTicks * 50, periodTicks * 50, TimeUnit.MILLISECONDS);
                return new TaskHandle(scheduled, -1);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        try {
            int id = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, delayTicks, periodTicks).getTaskId();
            return new TaskHandle(null, id);
        } catch (RuntimeException ignored) {
            return new TaskHandle(null, -1);
        }
    }

    /** 定时任务句柄 (Folia ScheduledTask / Paper task id) */
    public static final class TaskHandle {
        private final Object foliaScheduledTask;
        private final int bukkitTaskId;

        TaskHandle(Object foliaScheduledTask, int bukkitTaskId) {
            this.foliaScheduledTask = foliaScheduledTask;
            this.bukkitTaskId = bukkitTaskId;
        }

        public void cancel() {
            if (foliaScheduledTask != null) {
                try {
                    foliaScheduledTask.getClass().getMethod("cancel").invoke(foliaScheduledTask);
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                }
                return;
            }
            if (bukkitTaskId >= 0) {
                try {
                    Bukkit.getScheduler().cancelTask(bukkitTaskId);
                } catch (RuntimeException ignored) {
                }
            }
        }
    }
}
