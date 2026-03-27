package com.bookshelf.adapter.out.persistence;

import com.bookshelf.domain.model.Book;
import com.bookshelf.domain.model.Shelf;
import com.bookshelf.domain.port.out.ShelfRepository;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;

public class JdbcShelfRepository implements ShelfRepository {

    private final DataSource dataSource;

    public JdbcShelfRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public List<Shelf> findAll() {
        String sql = "SELECT * FROM shelves ORDER BY position ASC";
        try (
            Connection conn = dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql);
            ResultSet rs = stmt.executeQuery()
        ) {
            List<Shelf> shelves = new ArrayList<>();
            while (rs.next()) {
                Shelf shelf = mapRow(rs);
                enrichForList(conn, shelf);
                shelves.add(shelf);
            }
            return shelves;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find all shelves", e);
        }
    }

    @Override
    public Optional<Shelf> findById(UUID id) {
        String sql = "SELECT * FROM shelves WHERE id = ?";
        try (
            Connection conn = dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)
        ) {
            stmt.setObject(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    Shelf shelf = mapRow(rs);
                    enrichForDetail(conn, shelf);
                    return Optional.of(shelf);
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find shelf by id", e);
        }
    }

    @Override
    public Shelf save(Shelf shelf) {
        String sql = """
            INSERT INTO shelves (id, name, description, notes, color, sort_field, sort_direction, position, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        try (
            Connection conn = dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)
        ) {
            stmt.setObject(1, shelf.getId());
            stmt.setString(2, shelf.getName());
            stmt.setString(3, shelf.getDescription());
            stmt.setString(4, shelf.getNotes());
            stmt.setString(5, shelf.getColor());
            stmt.setString(6, shelf.getSortField());
            stmt.setString(7, shelf.getSortDirection());
            stmt.setInt(8, shelf.getPosition());
            stmt.setTimestamp(9, Timestamp.from(shelf.getCreatedAt()));
            stmt.setTimestamp(10, Timestamp.from(shelf.getUpdatedAt()));
            stmt.executeUpdate();
            enrichForList(conn, shelf);
            return shelf;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save shelf", e);
        }
    }

    @Override
    public Optional<Shelf> update(UUID id, Shelf shelf) {
        String sql = """
            UPDATE shelves SET name = ?, description = ?, notes = ?, color = ?,
                sort_field = ?, sort_direction = ?, position = ?, updated_at = ?
            WHERE id = ?
            """;
        try (
            Connection conn = dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)
        ) {
            stmt.setString(1, shelf.getName());
            stmt.setString(2, shelf.getDescription());
            stmt.setString(3, shelf.getNotes());
            stmt.setString(4, shelf.getColor());
            stmt.setString(5, shelf.getSortField());
            stmt.setString(6, shelf.getSortDirection());
            stmt.setInt(7, shelf.getPosition());
            stmt.setTimestamp(8, Timestamp.from(shelf.getUpdatedAt()));
            stmt.setObject(9, id);
            int rows = stmt.executeUpdate();
            if (rows > 0) {
                enrichForList(conn, shelf);
                return Optional.of(shelf);
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update shelf", e);
        }
    }

    @Override
    public boolean delete(UUID id) {
        String sql = "DELETE FROM shelves WHERE id = ?";
        try (
            Connection conn = dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)
        ) {
            stmt.setObject(1, id);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete shelf", e);
        }
    }

    @Override
    public void addBook(UUID shelfId, UUID bookId) {
        int maxPos = getMaxBookPosition(shelfId);
        String sql =
            "INSERT INTO shelf_books (shelf_id, book_id, position, added_at) VALUES (?, ?, ?, ?)";
        try (
            Connection conn = dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)
        ) {
            stmt.setObject(1, shelfId);
            stmt.setObject(2, bookId);
            stmt.setInt(3, maxPos + 1);
            stmt.setTimestamp(4, Timestamp.from(Instant.now()));
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to add book to shelf", e);
        }
    }

    @Override
    public void removeBook(UUID shelfId, UUID bookId) {
        String sql =
            "DELETE FROM shelf_books WHERE shelf_id = ? AND book_id = ?";
        try (
            Connection conn = dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)
        ) {
            stmt.setObject(1, shelfId);
            stmt.setObject(2, bookId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to remove book from shelf", e);
        }
        // Renumber positions
        renumberBookPositions(shelfId);
    }

    @Override
    public boolean hasBook(UUID shelfId, UUID bookId) {
        String sql =
            "SELECT 1 FROM shelf_books WHERE shelf_id = ? AND book_id = ?";
        try (
            Connection conn = dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)
        ) {
            stmt.setObject(1, shelfId);
            stmt.setObject(2, bookId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check shelf membership", e);
        }
    }

    @Override
    public List<Shelf> findShelvesForBook(UUID bookId) {
        String sql = """
            SELECT s.id, s.name, s.color FROM shelves s
            JOIN shelf_books sb ON s.id = sb.shelf_id
            WHERE sb.book_id = ?
            ORDER BY s.position
            """;
        try (
            Connection conn = dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)
        ) {
            stmt.setObject(1, bookId);
            try (ResultSet rs = stmt.executeQuery()) {
                List<Shelf> shelves = new ArrayList<>();
                while (rs.next()) {
                    Shelf shelf = new Shelf();
                    shelf.setId(rs.getObject("id", UUID.class));
                    shelf.setName(rs.getString("name"));
                    shelf.setColor(rs.getString("color"));
                    shelves.add(shelf);
                }
                return shelves;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find shelves for book", e);
        }
    }

    @Override
    public void reorderShelves(List<UUID> shelfIds) {
        String sql =
            "UPDATE shelves SET position = ?, updated_at = ? WHERE id = ?";
        try (
            Connection conn = dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)
        ) {
            Timestamp now = Timestamp.from(Instant.now());
            for (int i = 0; i < shelfIds.size(); i++) {
                stmt.setInt(1, i);
                stmt.setTimestamp(2, now);
                stmt.setObject(3, shelfIds.get(i));
                stmt.addBatch();
            }
            stmt.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to reorder shelves", e);
        }
    }

    @Override
    public void reorderBooks(UUID shelfId, List<UUID> bookIds) {
        String sql =
            "UPDATE shelf_books SET position = ? WHERE shelf_id = ? AND book_id = ?";
        try (
            Connection conn = dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)
        ) {
            for (int i = 0; i < bookIds.size(); i++) {
                stmt.setInt(1, i);
                stmt.setObject(2, shelfId);
                stmt.setObject(3, bookIds.get(i));
                stmt.addBatch();
            }
            stmt.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to reorder books in shelf", e);
        }
    }

    @Override
    public int getMaxShelfPosition() {
        String sql = "SELECT COALESCE(MAX(position), -1) FROM shelves";
        try (
            Connection conn = dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql);
            ResultSet rs = stmt.executeQuery()
        ) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get max shelf position", e);
        }
    }

    @Override
    public int getMaxBookPosition(UUID shelfId) {
        String sql =
            "SELECT COALESCE(MAX(position), -1) FROM shelf_books WHERE shelf_id = ?";
        try (
            Connection conn = dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)
        ) {
            stmt.setObject(1, shelfId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get max book position", e);
        }
    }

    @Override
    public void renumberShelfPositions() {
        String sql = "SELECT id FROM shelves ORDER BY position ASC";
        try (
            Connection conn = dataSource.getConnection();
            PreparedStatement selectStmt = conn.prepareStatement(sql);
            ResultSet rs = selectStmt.executeQuery()
        ) {
            String updateSql = "UPDATE shelves SET position = ? WHERE id = ?";
            try (
                PreparedStatement updateStmt = conn.prepareStatement(updateSql)
            ) {
                int pos = 0;
                while (rs.next()) {
                    updateStmt.setInt(1, pos++);
                    updateStmt.setObject(2, rs.getObject("id", UUID.class));
                    updateStmt.addBatch();
                }
                updateStmt.executeBatch();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to renumber shelf positions", e);
        }
    }

    @Override
    public void removeBookFromAllShelves(UUID bookId) {
        String sql = "DELETE FROM shelf_books WHERE book_id = ?";
        try (
            Connection conn = dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)
        ) {
            stmt.setObject(1, bookId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(
                "Failed to remove book from all shelves",
                e
            );
        }
    }

    @Override
    public void clear() {
        try (
            Connection conn = dataSource.getConnection();
            Statement stmt = conn.createStatement()
        ) {
            stmt.executeUpdate("DELETE FROM shelf_books");
            stmt.executeUpdate("DELETE FROM shelves");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to clear shelves", e);
        }
    }

    private Shelf mapRow(ResultSet rs) throws SQLException {
        Shelf shelf = new Shelf();
        shelf.setId(rs.getObject("id", UUID.class));
        shelf.setName(rs.getString("name"));
        shelf.setDescription(rs.getString("description"));
        shelf.setNotes(rs.getString("notes"));
        shelf.setColor(rs.getString("color"));
        shelf.setSortField(rs.getString("sort_field"));
        shelf.setSortDirection(rs.getString("sort_direction"));
        shelf.setPosition(rs.getInt("position"));
        Timestamp createdAt = rs.getTimestamp("created_at");
        if (createdAt != null) shelf.setCreatedAt(createdAt.toInstant());
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        if (updatedAt != null) shelf.setUpdatedAt(updatedAt.toInstant());
        return shelf;
    }

    private void enrichForList(Connection conn, Shelf shelf)
        throws SQLException {
        // Book count
        String countSql = "SELECT COUNT(*) FROM shelf_books WHERE shelf_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(countSql)) {
            stmt.setObject(1, shelf.getId());
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                shelf.setBookCount(rs.getInt(1));
            }
        }

        // Cover book IDs (first 4 by position)
        String coverSql =
            "SELECT book_id FROM shelf_books WHERE shelf_id = ? ORDER BY position ASC LIMIT 4";
        try (PreparedStatement stmt = conn.prepareStatement(coverSql)) {
            stmt.setObject(1, shelf.getId());
            try (ResultSet rs = stmt.executeQuery()) {
                List<UUID> ids = new ArrayList<>();
                while (rs.next()) {
                    ids.add(rs.getObject("book_id", UUID.class));
                }
                shelf.setCoverBookIds(ids);
            }
        }
    }

    private void enrichForDetail(Connection conn, Shelf shelf)
        throws SQLException {
        enrichForList(conn, shelf);

        // Fetch books
        String sortField =
            shelf.getSortField() != null ? shelf.getSortField() : "custom";
        String booksSql;
        if ("custom".equals(sortField)) {
            booksSql = """
                SELECT b.* FROM books b
                JOIN shelf_books sb ON b.id = sb.book_id
                WHERE sb.shelf_id = ?
                ORDER BY sb.position ASC
                """;
        } else {
            booksSql = """
                SELECT b.* FROM books b
                JOIN shelf_books sb ON b.id = sb.book_id
                WHERE sb.shelf_id = ?
                """;
        }

        try (PreparedStatement stmt = conn.prepareStatement(booksSql)) {
            stmt.setObject(1, shelf.getId());
            try (ResultSet rs = stmt.executeQuery()) {
                List<Book> books = new ArrayList<>();
                while (rs.next()) {
                    books.add(BookRowMapper.mapRow(rs));
                }

                // Apply in-memory sort for non-custom
                if (!"custom".equals(sortField)) {
                    boolean desc = "desc".equalsIgnoreCase(
                        shelf.getSortDirection()
                    );
                    Comparator<Book> comparator;
                    if ("rating".equals(sortField)) {
                        // Unrated (0) always sorts last regardless of direction
                        comparator = (x, y) -> {
                            int rx = x.getRating() != null ? x.getRating() : 0;
                            int ry = y.getRating() != null ? y.getRating() : 0;
                            if (rx == 0 && ry == 0) return 0;
                            if (rx == 0) return 1;
                            if (ry == 0) return -1;
                            return desc
                                ? Integer.compare(ry, rx)
                                : Integer.compare(rx, ry);
                        };
                    } else {
                        comparator = switch (sortField) {
                            case "title" -> Comparator.comparing(
                                (Book b) ->
                                    b.getTitle() != null
                                        ? b.getTitle().toLowerCase()
                                        : null,
                                Comparator.nullsLast(Comparator.naturalOrder())
                            );
                            case "author" -> Comparator.comparing(
                                (Book b) ->
                                    b.getAuthor() != null
                                        ? b.getAuthor().toLowerCase()
                                        : null,
                                Comparator.nullsLast(Comparator.naturalOrder())
                            );
                            case "created" -> Comparator.comparing((Book b) ->
                                b.getCreatedAt() != null
                                    ? b.getCreatedAt()
                                    : Instant.EPOCH
                            );
                            default -> null;
                        };
                        if (comparator != null && desc) {
                            comparator = comparator.reversed();
                        }
                    }
                    if (comparator != null) {
                        books.sort(comparator);
                    }
                }

                shelf.setBooks(books);

                // Compute stats
                Map<String, Object> stats = new LinkedHashMap<>();
                stats.put("bookCount", books.size());

                List<Integer> rated = books
                    .stream()
                    .map(Book::getRating)
                    .filter(r -> r != null && r > 0)
                    .toList();
                double avgRating = rated.isEmpty()
                    ? 0.0
                    : rated
                          .stream()
                          .mapToInt(Integer::intValue)
                          .average()
                          .orElse(0.0) / 2.0;
                stats.put("averageRating", avgRating);

                int totalPages = books
                    .stream()
                    .map(Book::getPageCount)
                    .filter(Objects::nonNull)
                    .mapToInt(Integer::intValue)
                    .sum();
                stats.put("totalPages", totalPages);

                Map<String, Integer> genreBreakdown = new LinkedHashMap<>();
                for (Book book : books) {
                    if (book.getGenre() != null && !book.getGenre().isBlank()) {
                        genreBreakdown.merge(book.getGenre(), 1, Integer::sum);
                    }
                }
                stats.put("genreBreakdown", genreBreakdown);

                shelf.setStats(stats);
            }
        }
    }

    private void renumberBookPositions(UUID shelfId) {
        String sql =
            "SELECT book_id FROM shelf_books WHERE shelf_id = ? ORDER BY position ASC";
        try (
            Connection conn = dataSource.getConnection();
            PreparedStatement selectStmt = conn.prepareStatement(sql);
        ) {
            selectStmt.setObject(1, shelfId);
            try (ResultSet rs = selectStmt.executeQuery()) {
                String updateSql =
                    "UPDATE shelf_books SET position = ? WHERE shelf_id = ? AND book_id = ?";
                try (
                    PreparedStatement updateStmt = conn.prepareStatement(
                        updateSql
                    )
                ) {
                    int pos = 0;
                    while (rs.next()) {
                        updateStmt.setInt(1, pos++);
                        updateStmt.setObject(2, shelfId);
                        updateStmt.setObject(
                            3,
                            rs.getObject("book_id", UUID.class)
                        );
                        updateStmt.addBatch();
                    }
                    updateStmt.executeBatch();
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to renumber book positions", e);
        }
    }
}
