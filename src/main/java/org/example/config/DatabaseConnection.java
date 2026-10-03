package org.example.config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class DatabaseConnection {

    private static final String host = getRequiredEnvironmentVariable("DB_HOST");
    private static final String port = getRequiredEnvironmentVariable("DB_PORT");
    private static final String  database = getRequiredEnvironmentVariable("DB_NAME");

    private static final String DATABASE_URL =
            "jdbc:postgresql://" +
                    host + ":" +
                    port + "/" +
                    database +
                    "?sslmode=require";

    private static final String DATABASE_USERNAME =
            getRequiredEnvironmentVariable("DB_USERNAME");

    private static final String DATABASE_PASSWORD =
            getRequiredEnvironmentVariable("DB_PASSWORD");

    public static Connection getConnection() throws SQLException {

        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException exception) {
            throw new SQLException(
                    "PostgreSQL JDBC driver is not available.",
                    exception
            );
        }

        return DriverManager.getConnection(
                DATABASE_URL,
                DATABASE_USERNAME,
                DATABASE_PASSWORD
        );
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