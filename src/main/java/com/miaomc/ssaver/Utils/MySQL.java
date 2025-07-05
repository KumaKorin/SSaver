package com.miaomc.ssaver.utils;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miaomc.ssaver.SSaver;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.file.FileConfiguration;

import java.sql.*;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public class MySQL {
    private final SSaver plugin;
    private HikariDataSource dataSource;
    private final String tablename;
    private final String serverName;

    /**
     * 构造方法
     *
     * @param plugin 插件实例
     */
    public MySQL(SSaver plugin) {
        this.plugin = plugin;
        FileConfiguration config = plugin.getConfig();
        String configTableName = config.getString("database.tablename");
        this.tablename = (configTableName == null || configTableName.isEmpty()) ? "playerStatistics" : configTableName;
        this.serverName = config.getString("settings.serverName", "root");
        setupPool();
    }

    /**
     * 设置数据库连接池
     */
    private void setupPool() {
        FileConfiguration config = plugin.getConfig();
        String host = config.getString("database.host", "localhost");
        int port = config.getInt("database.port", 3306);
        String dbName = config.getString("database.name", "minecraft");
        String username = config.getString("database.username", "root");
        String password = config.getString("database.password", "");

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + dbName +
                "?useSSL=false&autoReconnect=true&useUnicode=true&characterEncoding=utf8");
        hikariConfig.setUsername(username);
        hikariConfig.setPassword(password);
        hikariConfig.setMaximumPoolSize(10);
        hikariConfig.setConnectionTimeout(30000);
        hikariConfig.setPoolName("SSaver-Pool");
        hikariConfig.setMinimumIdle(3);
        hikariConfig.setIdleTimeout(60000);
        hikariConfig.setMaxLifetime(1800000);

        try {
            dataSource = new HikariDataSource(hikariConfig);
        } catch (Exception e) {
            plugin.getLogger().severe("无法建立数据库连接: " + e.getMessage());
        }
    }

    /**
     * 测试数据库连接
     *
     * @return 连接是否成功
     */
    public boolean testConnection() {
        if (dataSource == null) {
            return false;
        }

        try (Connection connection = dataSource.getConnection()) {
            if (!connection.isValid(5)) {
                return false;
            }

            // 检查并初始化表结构
            checkAndInitializeTable(connection);
            return true;
        } catch (SQLException e) {
            plugin.getLogger().severe("数据库连接测试失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 初始化数据库表
     */
    public void initialize() {
        CompletableFuture.runAsync(() -> {
            try (Connection connection = dataSource.getConnection()) {
                checkAndInitializeTable(connection);
            } catch (SQLException e) {
                plugin.getLogger().severe("无法初始化数据库表: " + e.getMessage());
            }
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.SEVERE, "初始化数据库时发生错误", ex);
            return null;
        });
    }

    /**
     * 检查表是否存在，如果不存在则创建，检查表结构
     *
     * @param connection 数据库连接
     * @throws SQLException SQL异常
     */
    private void checkAndInitializeTable(Connection connection) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        boolean tableExists;

        try (ResultSet tables = meta.getTables(null, null, tablename, null)) {
            tableExists = tables.next();
        }

        if (!tableExists) {
            createTable(connection);
        } else {
            verifyAndUpdateColumns(connection);
        }
    }

    /**
     * 创建表
     *
     * @param connection 数据库连接
     * @throws SQLException SQL异常
     */
    private void createTable(Connection connection) throws SQLException {
        String createTableSQL = "CREATE TABLE IF NOT EXISTS `" + tablename + "` ("
                + "uuid VARCHAR(36) NOT NULL, "
                + "serverName VARCHAR(50) NOT NULL, "
                + "data LONGTEXT NOT NULL, "
                + "dataVersion VARCHAR(20) NOT NULL, "
                + "updateDate TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, "
                + "createDate TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                + "PRIMARY KEY (uuid, serverName), "
                + "INDEX idx_uuid (uuid)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(createTableSQL);
            plugin.getLogger().info("成功创建数据表 " + tablename);
        }
    }

    /**
     * 验证并更新表列
     *
     * @param connection 数据库连接
     * @throws SQLException SQL异常
     */
    private void verifyAndUpdateColumns(Connection connection) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        Statement statement = connection.createStatement();

        // 定义需要的列
        Map<String, String> requiredColumns = Map.of(
                "uuid", "VARCHAR(36) NOT NULL",
                "serverName", "VARCHAR(50) NOT NULL",
                "data", "LONGTEXT NOT NULL",
                "dataVersion", "VARCHAR(20) NOT NULL",
                "updateDate", "TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP",
                "createDate", "TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
        );

        // 检查表中的现有列
        Set<String> existingColumns = new HashSet<>();
        try (ResultSet columns = meta.getColumns(null, null, tablename, null)) {
            while (columns.next()) {
                existingColumns.add(columns.getString("COLUMN_NAME").toLowerCase());
            }
        }

        // 添加缺失的列
        for (Map.Entry<String, String> entry : requiredColumns.entrySet()) {
            if (!existingColumns.contains(entry.getKey().toLowerCase())) {
                statement.executeUpdate("ALTER TABLE `" + tablename + "` ADD COLUMN " + entry.getKey() + " " + entry.getValue());
            }
        }

        // 检查唯一索引
        boolean hasUniqueIndex = false;
        try (ResultSet indexInfo = meta.getIndexInfo(null, null, tablename, true, false)) {
            while (indexInfo.next()) {
                String indexName = indexInfo.getString("INDEX_NAME");
                if ("unique_player_server".equalsIgnoreCase(indexName)) {
                    hasUniqueIndex = true;
                    break;
                }
            }
        }

        // 添加缺少的唯一索引
        if (!hasUniqueIndex) {
            try {
                statement.executeUpdate("ALTER TABLE `" + tablename +
                        "` ADD CONSTRAINT unique_player_server UNIQUE (uuid, serverName)");
                plugin.getLogger().info("为表 " + tablename + " 添加唯一索引 unique_player_server");
            } catch (SQLException e) {
                plugin.getLogger().warning("添加唯一索引失败: " + e.getMessage());
            }
        }

        statement.close();
    }

    /**
     * 保存数据到数据库（存在则更新，不存在则插入）
     * 根据配置决定是同步还是异步执行
     *
     * @param uuid        玩家UUID
     * @param data        玩家数据
     * @param dataVersion 数据版本
     * @return 操作结果的Future
     */
    public CompletableFuture<Boolean> saveData(String uuid, JsonObject data, String dataVersion) {
        Gson gson = new Gson();
        if (plugin.getConfig().getBoolean("settings.saveAsync", true)) {
            return saveDataAsync(uuid, data, dataVersion);
        } else {
            return doSaveData(uuid, gson.toJson(data), dataVersion);
        }
    }

    /**
     * 执行实际的数据保存操作（存在则更新，不存在则插入）
     *
     * @param uuid        玩家UUID
     * @param jsonData    JSON字符串数据
     * @param dataVersion 数据版本
     * @return 操作结果的Future
     */
    private CompletableFuture<Boolean> doSaveData(String uuid, String jsonData, String dataVersion) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "INSERT INTO `" + tablename + "` (uuid, serverName, data, dataVersion) VALUES (?, ?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE data = ?, dataVersion = ?, updateDate = CURRENT_TIMESTAMP";

            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {

                statement.setString(1, uuid);
                statement.setString(2, serverName);
                statement.setString(3, jsonData);
                statement.setString(4, dataVersion);
                statement.setString(5, jsonData);
                statement.setString(6, dataVersion);

                int rowsAffected = statement.executeUpdate();

                if (plugin.getConfig().getBoolean("settings.showSaveMessages", true)) {
                    plugin.getLogger().info("已保存玩家 " + uuid + " 在服务器 " + serverName + " 的数据");
                }

                return rowsAffected > 0;
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "保存玩家 " + uuid + " 的数据失败", e);
                return false;
            }
        });
    }

    /**
     * 使用Bukkit调度器异步保存数据
     *
     * @param uuid        玩家UUID
     * @param data        玩家数据
     * @param dataVersion 数据版本
     * @return 操作结果的Future
     */
    private CompletableFuture<Boolean> saveDataAsync(String uuid, JsonObject data, String dataVersion) {
        Gson gson = new Gson();
        return CompletableFuture.supplyAsync(() -> {
            String sql = "INSERT INTO `" + tablename + "` (uuid, serverName, data, dataVersion) VALUES (?, ?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE data = ?, dataVersion = ?, updateDate = CURRENT_TIMESTAMP";
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                String jsonString = gson.toJson(data);
                statement.setString(1, uuid);
                statement.setString(2, serverName);
                statement.setString(3, jsonString);
                statement.setString(4, dataVersion);
                statement.setString(5, jsonString);
                statement.setString(6, dataVersion);
                int rowsAffected = statement.executeUpdate();
                if (plugin.getConfig().getBoolean("settings.showSaveMessages", true)) {
                    plugin.getLogger().info("已异步保存玩家 " + uuid + " 在服务器 " + serverName + " 的数据");
                }
                return rowsAffected > 0;
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "异步保存玩家 " + uuid + " 的数据失败", e);
                return false;
            }
        });
    }

    /**
     * 从数据库获取玩家数据
     *
     * @param uuid 玩家UUID
     * @return 包含玩家数据的JSONObject，如果没有找到则返回null
     */
    @SuppressWarnings("unused")
    public CompletableFuture<JsonObject> getPlayerData(String uuid) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT data FROM `" + tablename + "` WHERE uuid = ? AND serverName = ?";

            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {

                statement.setString(1, uuid);
                statement.setString(2, serverName);

                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        String jsonData = resultSet.getString("data");
                        return JsonParser.parseString(jsonData).getAsJsonObject();
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "获取玩家 " + uuid + " 数据失败", e);
            }

            return null;
        });
    }

    /**
     * 关闭连接池
     */
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            plugin.getLogger().info("数据库连接池已关闭");
        }
    }

    /**
     * 重新初始化连接池
     */
    public void reInitialize() {
        // 如果连接池存在且未关闭，先关闭
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }

        // 重新创建连接池
        setupPool();

        if (testConnection()) {
            initialize();
            plugin.getLogger().info("数据库连接池已重新初始化");
        } else {
            plugin.getLogger().severe("数据库重新初始化失败，无法建立连接");
        }
    }
}