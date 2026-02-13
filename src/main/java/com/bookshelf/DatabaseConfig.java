package com.bookshelf;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class DatabaseConfig {

    private final HikariDataSource dataSource;

    public DatabaseConfig() {
        String host = envOrDefault("DB_HOST", "localhost");
        String port = envOrDefault("DB_PORT", "5432");
        String dbName = envOrDefault("DB_NAME", "bookshelf");
        String user = envOrDefault("DB_USER", "bookshelf");
        String pass = envOrDefault("DB_PASS", "bookshelf");

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:postgresql://" + host + ":" + port + "/" + dbName);
        config.setUsername(user);
        config.setPassword(pass);
        config.setMaximumPoolSize(10);

        this.dataSource = new HikariDataSource(config);
    }

    public DataSource getDataSource() {
        return dataSource;
    }

    public void runMigrations() {
        String sql = """
                CREATE TABLE IF NOT EXISTS books (
                    id UUID PRIMARY KEY,
                    title VARCHAR(255),
                    author VARCHAR(255),
                    genre VARCHAR(255),
                    rating INTEGER DEFAULT 0,
                    isbn VARCHAR(13),
                    publisher VARCHAR(255),
                    publish_date VARCHAR(255),
                    page_count INTEGER,
                    subjects TEXT,
                    read_status VARCHAR(20) NOT NULL,
                    cover_path VARCHAR(512),
                    cover_url VARCHAR(512),
                    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
                    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
                );
                CREATE INDEX IF NOT EXISTS idx_books_isbn ON books(isbn);
                CREATE INDEX IF NOT EXISTS idx_books_genre ON books(LOWER(genre));
                CREATE INDEX IF NOT EXISTS idx_books_read_status ON books(read_status);
                ALTER TABLE books ADD COLUMN IF NOT EXISTS cover_data BYTEA;
                """;

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to run migrations", e);
        }
    }

    public void close() {
        dataSource.close();
    }

    private static String envOrDefault(String key, String defaultValue) {
        String value = System.getenv(key);
        return value != null ? value : defaultValue;
    }
}
