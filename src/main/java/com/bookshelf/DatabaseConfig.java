package com.bookshelf;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;

public class DatabaseConfig {

    private final HikariDataSource dataSource;

    public DatabaseConfig() {
        String host = envOrDefault("DB_HOST", "localhost");
        String port = envOrDefault("DB_PORT", "5432");
        String dbName = envOrDefault("DB_NAME", "bookshelf");
        String user = envOrDefault("DB_USER", "bookshelf");
        String pass = envOrDefault("DB_PASS", "bookshelf");

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(
            "jdbc:postgresql://" + host + ":" + port + "/" + dbName
        );
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
                cover_url VARCHAR(512),
                created_at TIMESTAMP NOT NULL DEFAULT NOW(),
                updated_at TIMESTAMP NOT NULL DEFAULT NOW()
            );
            CREATE INDEX IF NOT EXISTS idx_books_isbn ON books(isbn);
            CREATE INDEX IF NOT EXISTS idx_books_genre ON books(LOWER(genre));
            CREATE INDEX IF NOT EXISTS idx_books_read_status ON books(read_status);
            ALTER TABLE books ADD COLUMN IF NOT EXISTS cover_data BYTEA;
            """;

        try (
            Connection conn = dataSource.getConnection();
            Statement stmt = conn.createStatement()
        ) {
            stmt.execute(sql);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to run migrations", e);
        }

        try (Connection conn = dataSource.getConnection()) {
            conn
                .createStatement()
                .execute(
                    "ALTER TABLE books ADD COLUMN IF NOT EXISTS reading_progress INTEGER DEFAULT NULL"
                );
        } catch (SQLException e) {
            throw new RuntimeException("Failed to run migrations", e);
        }

        try (Connection conn = dataSource.getConnection()) {
            conn
                .createStatement()
                .execute(
                    "ALTER TABLE books ADD COLUMN IF NOT EXISTS review TEXT DEFAULT NULL"
                );
        } catch (SQLException e) {
            throw new RuntimeException("Failed to run migrations", e);
        }

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE books ADD COLUMN IF NOT EXISTS started_at DATE DEFAULT NULL");
            stmt.execute("ALTER TABLE books ADD COLUMN IF NOT EXISTS finished_at DATE DEFAULT NULL");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to run migrations", e);
        }

        // Shelves tables
        try (
            Connection conn = dataSource.getConnection();
            Statement stmt = conn.createStatement()
        ) {
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS shelves (
                    id UUID PRIMARY KEY,
                    name VARCHAR(100) NOT NULL,
                    description TEXT,
                    notes TEXT,
                    color VARCHAR(7) NOT NULL DEFAULT '#C4975A',
                    sort_field VARCHAR(20) NOT NULL DEFAULT 'custom',
                    sort_direction VARCHAR(4) NOT NULL DEFAULT 'asc',
                    position INTEGER NOT NULL DEFAULT 0,
                    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
                    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
                );
                CREATE UNIQUE INDEX IF NOT EXISTS idx_shelves_name_lower ON shelves(LOWER(name));
                CREATE TABLE IF NOT EXISTS shelf_books (
                    shelf_id UUID NOT NULL REFERENCES shelves(id) ON DELETE CASCADE,
                    book_id UUID NOT NULL REFERENCES books(id) ON DELETE CASCADE,
                    position INTEGER NOT NULL DEFAULT 0,
                    added_at TIMESTAMP NOT NULL DEFAULT NOW(),
                    PRIMARY KEY (shelf_id, book_id)
                );
                """
            );
        } catch (SQLException e) {
            throw new RuntimeException("Failed to run shelves migration", e);
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
