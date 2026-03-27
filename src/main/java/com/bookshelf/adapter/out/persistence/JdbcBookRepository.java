package com.bookshelf.adapter.out.persistence;

import com.bookshelf.domain.model.Book;
import com.bookshelf.domain.model.BookMetadata;
import com.bookshelf.domain.model.ReadStatus;
import com.bookshelf.domain.port.out.BookRepository;
import com.google.gson.Gson;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class JdbcBookRepository implements BookRepository {

    private final DataSource dataSource;
    private final Gson gson = com.bookshelf.adapter.in.http.GsonFactory.create();

    public JdbcBookRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public List<Book> findAll() {
        String sql = "SELECT * FROM books ORDER BY created_at ASC";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            List<Book> books = new ArrayList<>();
            while (rs.next()) {
                books.add(BookRowMapper.mapRow(rs));
            }
            return books;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find all books", e);
        }
    }

    @Override
    public List<Book> findByGenre(String genre) {
        String sql = "SELECT * FROM books WHERE LOWER(genre) = LOWER(?) ORDER BY created_at ASC";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, genre);
            try (ResultSet rs = stmt.executeQuery()) {
                List<Book> books = new ArrayList<>();
                while (rs.next()) {
                    books.add(BookRowMapper.mapRow(rs));
                }
                return books;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find books by genre", e);
        }
    }

    @Override
    public List<Book> findByReadStatus(ReadStatus readStatus) {
        String sql = "SELECT * FROM books WHERE read_status = ? ORDER BY created_at ASC";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, readStatus.name());
            try (ResultSet rs = stmt.executeQuery()) {
                List<Book> books = new ArrayList<>();
                while (rs.next()) {
                    books.add(BookRowMapper.mapRow(rs));
                }
                return books;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find books by read status", e);
        }
    }

    private String escapeLike(String input) {
        return input.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    @Override
    public List<Book> findBySearch(String query) {
        String sql = "SELECT * FROM books WHERE LOWER(title) LIKE LOWER(?) ESCAPE '\\' OR LOWER(author) LIKE LOWER(?) ESCAPE '\\' ORDER BY created_at ASC";
        String pattern = "%" + escapeLike(query) + "%";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, pattern);
            stmt.setString(2, pattern);
            try (ResultSet rs = stmt.executeQuery()) {
                List<Book> books = new ArrayList<>();
                while (rs.next()) books.add(BookRowMapper.mapRow(rs));
                return books;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to search books", e);
        }
    }

    @Override
    public List<Book> findBySubject(String subject) {
        String sql = "SELECT * FROM books WHERE LOWER(subjects) LIKE LOWER(?) ESCAPE '\\' ORDER BY created_at ASC";
        String pattern = "%" + escapeLike(subject) + "%";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, pattern);
            try (ResultSet rs = stmt.executeQuery()) {
                List<Book> books = new ArrayList<>();
                while (rs.next()) books.add(BookRowMapper.mapRow(rs));
                return books;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find books by subject", e);
        }
    }

    @Override
    public Optional<Book> findById(UUID id) {
        String sql = "SELECT * FROM books WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(BookRowMapper.mapRow(rs));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find book by id", e);
        }
    }

    @Override
    public Optional<Book> findByIsbn(String isbn) {
        if (isbn == null) return Optional.empty();
        String sql = "SELECT * FROM books WHERE isbn = ? ORDER BY created_at ASC LIMIT 1";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, isbn);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(BookRowMapper.mapRow(rs));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find book by isbn", e);
        }
    }

    @Override
    public Book save(Book book) {
        String sql = """
                INSERT INTO books (id, title, author, genre, rating, isbn, publisher, publish_date,
                    page_count, subjects, read_status, cover_data, cover_url, reading_progress,
                    review, started_at, finished_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, book.getId());
            stmt.setString(2, book.getTitle());
            stmt.setString(3, book.getAuthor());
            stmt.setString(4, book.getGenre());
            setNullableInt(stmt, 5, book.getRating());
            stmt.setString(6, book.getIsbn());
            stmt.setString(7, book.getPublisher());
            stmt.setString(8, book.getPublishDate());
            setNullableInt(stmt, 9, book.getPageCount());
            stmt.setString(10, book.getSubjects() != null ? gson.toJson(book.getSubjects()) : null);
            stmt.setString(11, book.getReadStatus().name());
            stmt.setBytes(12, book.getCoverData());
            stmt.setString(13, book.getCoverUrl());
            setNullableInt(stmt, 14, book.getReadingProgress());
            stmt.setString(15, book.getReview());
            setNullableDate(stmt, 16, book.getStartedAt());
            setNullableDate(stmt, 17, book.getFinishedAt());
            stmt.setTimestamp(18, Timestamp.from(book.getCreatedAt()));
            stmt.setTimestamp(19, Timestamp.from(book.getUpdatedAt()));
            stmt.executeUpdate();
            return book;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save book", e);
        }
    }

    @Override
    public Optional<Book> update(UUID id, Book book) {
        String sql = """
                UPDATE books SET title = ?, author = ?, genre = ?, rating = ?, isbn = ?,
                    publisher = ?, publish_date = ?, page_count = ?, subjects = ?,
                    read_status = ?, cover_data = ?, cover_url = ?, reading_progress = ?,
                    review = ?, started_at = ?, finished_at = ?, updated_at = ?
                WHERE id = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, book.getTitle());
            stmt.setString(2, book.getAuthor());
            stmt.setString(3, book.getGenre());
            setNullableInt(stmt, 4, book.getRating());
            stmt.setString(5, book.getIsbn());
            stmt.setString(6, book.getPublisher());
            stmt.setString(7, book.getPublishDate());
            setNullableInt(stmt, 8, book.getPageCount());
            stmt.setString(9, book.getSubjects() != null ? gson.toJson(book.getSubjects()) : null);
            stmt.setString(10, book.getReadStatus().name());
            stmt.setBytes(11, book.getCoverData());
            stmt.setString(12, book.getCoverUrl());
            setNullableInt(stmt, 13, book.getReadingProgress());
            stmt.setString(14, book.getReview());
            setNullableDate(stmt, 15, book.getStartedAt());
            setNullableDate(stmt, 16, book.getFinishedAt());
            stmt.setTimestamp(17, Timestamp.from(book.getUpdatedAt()));
            stmt.setObject(18, id);
            int rows = stmt.executeUpdate();
            return rows > 0 ? Optional.of(book) : Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update book", e);
        }
    }

    @Override
    public boolean delete(UUID id) {
        String sql = "DELETE FROM books WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, id);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete book", e);
        }
    }

    @Override
    public void updateFromOpenLibrary(UUID bookId, BookMetadata metadata, byte[] coverData) {
        String sql = """
                UPDATE books SET
                    title = COALESCE(title, ?),
                    author = COALESCE(author, ?),
                    publisher = COALESCE(publisher, ?),
                    publish_date = COALESCE(publish_date, ?),
                    page_count = COALESCE(page_count, ?),
                    subjects = COALESCE(subjects, ?),
                    cover_url = COALESCE(cover_url, ?),
                    genre = COALESCE(genre, ?),
                    cover_data = COALESCE(cover_data, ?),
                    updated_at = ?
                WHERE id = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            if (metadata != null) {
                stmt.setString(1, metadata.getTitle());
                stmt.setString(2, metadata.getAuthor());
                stmt.setString(3, metadata.getPublisher());
                stmt.setString(4, metadata.getPublishDate());
                setNullableInt(stmt, 5, metadata.getPageCount());
                stmt.setString(6, metadata.getSubjects() != null ? gson.toJson(metadata.getSubjects()) : null);
                stmt.setString(7, metadata.getCoverUrl());
                stmt.setString(8, metadata.getGenre());
            } else {
                stmt.setString(1, null);
                stmt.setString(2, null);
                stmt.setString(3, null);
                stmt.setString(4, null);
                stmt.setNull(5, Types.INTEGER);
                stmt.setString(6, null);
                stmt.setString(7, null);
                stmt.setString(8, null);
            }
            stmt.setBytes(9, coverData);
            stmt.setTimestamp(10, Timestamp.from(Instant.now()));
            stmt.setObject(11, bookId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update book from Open Library", e);
        }
    }

    @Override
    public void clear() {
        String sql = "DELETE FROM books";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to clear books", e);
        }
    }

    private void setNullableDate(PreparedStatement stmt, int index, String value) throws SQLException {
        if (value != null) {
            stmt.setDate(index, java.sql.Date.valueOf(value));
        } else {
            stmt.setNull(index, Types.DATE);
        }
    }

    private void setNullableInt(PreparedStatement stmt, int index, Integer value) throws SQLException {
        if (value != null) {
            stmt.setInt(index, value);
        } else {
            stmt.setNull(index, Types.INTEGER);
        }
    }
}
