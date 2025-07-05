package com.miaomc.ssaver;

import com.miaomc.ssaver.commands.SsaverCommand;
import com.miaomc.ssaver.listener.SavePlayerData;
import com.miaomc.ssaver.utils.MySQL;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class SSaver extends JavaPlugin {

    private MySQL mySQL;

    @Override
    public void onEnable() {
        // 保存默认配置
        saveDefaultConfig();

        // 初始化MySQL
        this.mySQL = new MySQL(this);
        if (!mySQL.testConnection()) {
            getLogger().severe("数据库连接失败，插件将被禁用！");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        mySQL.initialize();

        // 注册事件监听器
        getServer().getPluginManager().registerEvents(new SavePlayerData(this), this);

        // 创建命令处理器实例
        SsaverCommand commandHandler = new SsaverCommand(this);

        // Folia 兼容调度命令注册
        try {
            Class<?> foliaSchedulerClass = Class.forName("io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler");
            Object scheduler = getServer().getClass().getMethod("getGlobalRegionScheduler").invoke(getServer());
            foliaSchedulerClass.getMethod("execute", JavaPlugin.class, Runnable.class)
                    .invoke(scheduler, this, (Runnable) () -> {
                        commandHandler.register();
                        getLogger().info("命令已注册 (Folia)");
                    });
        } catch (ClassNotFoundException e) {
            // 非 Folia 环境，使用 Bukkit Scheduler
            getServer().getScheduler().runTask(this, () -> {
                commandHandler.register();
                getLogger().info("命令已注册 (Bukkit)");
            });
        } catch (Exception e) {
            getLogger().severe("命令注册调度失败: " + e.getMessage());
        }

        getLogger().info("SSaver 插件已启用！");
    }

    @Override
    public void onDisable() {

        // 卸载前，保存全部玩家数据
        SavePlayerData savePlayerData = new SavePlayerData(this);

        // 遍历所有在线玩家并保存数据
        Bukkit.getOnlinePlayers().forEach(player -> {
            try {
                savePlayerData.savePlayerStatistics(player);
            } catch (Exception e) {
                getLogger().severe("保存玩家 " + player.getName() + " 的数据时发生错误: " + e.getMessage());
            }
        });

        // 关闭MySQL连接
        if (mySQL != null) {
            mySQL.close();
        }

        getLogger().info("SSaver 插件已禁用，所有玩家数据已保存！");
    }

    /**
     * 获取MySQL实例
     *
     * @return MySQL实例
     */
    public MySQL getMySQL() {
        return mySQL;
    }
}