package org.example.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;

public class DatabaseConnection {
    private static final int MAX_POOL_SIZE = 5;

    private static HikariDataSource dataSource;

    public static Connection getConnection() throws SQLException {
        HikariDataSource pool;
        synchronized (DatabaseConnection.class) {
            if (dataSource == null || dataSource.isClosed()) {
                dataSource = createDataSource();
            }
            pool = dataSource;
        }
        return pool.getConnection();
    }

    private static HikariDataSource createDataSource() {
        String url = "jdbc:postgresql://" + getRequiredEnvironmentVariable("DB_HOST")
                + ":" + getRequiredEnvironmentVariable("DB_PORT") + "/"
                + getRequiredEnvironmentVariable("DB_NAME") + "?sslmode=require";
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setDriverClassName("org.postgresql.Driver");
        config.setUsername(getRequiredEnvironmentVariable("DB_USERNAME"));
        config.setPassword(getRequiredEnvironmentVariable("DB_PASSWORD"));
        config.setMaximumPoolSize(MAX_POOL_SIZE);
        return new HikariDataSource(config);
    }

    public static synchronized void closePool() {
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
        }
    }

    private static String getRequiredEnvironmentVariable(String name) {

        String value = System.getenv(name);

        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Required environment variable is missing: " + name
            );
        }

        return value;
    }
}