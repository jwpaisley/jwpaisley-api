package com.jwpaisley.services;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;

public class DatabaseService {
    private static volatile DatabaseService instance;
    private final HikariDataSource dataSource;

    private DatabaseService() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(System.getenv("DB_URL"));
        config.setUsername(System.getenv("DB_USER"));
        config.setPassword(System.getenv("DB_PASS"));

        config.setMinimumIdle(0);
        config.setInitializationFailTimeout(0);
        config.setConnectionTimeout(30000);

        this.dataSource = new HikariDataSource(config);
    }

    public static DatabaseService getInstance() {
        if (instance == null) {
            synchronized (DatabaseService.class) {
                if (instance == null) {
                    instance = new DatabaseService();
                }
            }
        }
        
        return instance;
    }

    public DataSource getDataSource() { return this.dataSource; }
}