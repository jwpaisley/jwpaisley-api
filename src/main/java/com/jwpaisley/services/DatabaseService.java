package com.jwpaisley.services;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;

public class DatabaseService {
    private final HikariDataSource dataSource;

    private DatabaseService() {
        HikariConfig config = new HikariConfig();

        config.setJdbcUrl(System.getenv().get("DB_URL"));
        config.setUsername(System.getenv().get("DB_USER"));
        config.setPassword(System.getenv().get("DB_PASS"));
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(30000);

        this.dataSource = new HikariDataSource(config);
    }

    private static class Holder {
        private static final DatabaseService INSTANCE = new DatabaseService();
    }

    public static DatabaseService getInstance() {
        return Holder.INSTANCE;
    }

    public DataSource getDataSource() {
        return this.dataSource;
    }
}