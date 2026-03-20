package com.bookshelf.adapter.out.persistence;

import com.bookshelf.domain.exception.DuplicateUserException;
import com.bookshelf.domain.model.User;
import com.bookshelf.domain.port.out.UserRepository;

import javax.sql.DataSource;
import java.sql.*;
import java.sql.Types;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public class JdbcUserRepository implements UserRepository {

    private final DataSource dataSource;

    public JdbcUserRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Optional<User> findById(UUID id) {
        String sql = "SELECT * FROM users WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return Optional.of(mapRow(rs));
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find user by id", e);
        }
    }

    @Override
    public Optional<User> findByUsername(String username) {
        String sql = "SELECT * FROM users WHERE username = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, username);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return Optional.of(mapRow(rs));
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find user by username", e);
        }
    }

    @Override
    public Optional<User> findByGoogleId(String googleId) {
        String sql = "SELECT * FROM users WHERE google_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, googleId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return Optional.of(mapRow(rs));
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find user by google_id", e);
        }
    }

    @Override
    public User save(User user) {
        String sql = "INSERT INTO users (id, username, password_hash, salt, google_id, email, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, user.getId());
            stmt.setString(2, user.getUsername());
            if (user.getPasswordHash() != null) {
                stmt.setString(3, user.getPasswordHash());
            } else {
                stmt.setNull(3, Types.VARCHAR);
            }
            if (user.getSalt() != null) {
                stmt.setString(4, user.getSalt());
            } else {
                stmt.setNull(4, Types.VARCHAR);
            }
            if (user.getGoogleId() != null) {
                stmt.setString(5, user.getGoogleId());
            } else {
                stmt.setNull(5, Types.VARCHAR);
            }
            if (user.getEmail() != null) {
                stmt.setString(6, user.getEmail());
            } else {
                stmt.setNull(6, Types.VARCHAR);
            }
            stmt.setTimestamp(7, Timestamp.from(user.getCreatedAt()));
            stmt.executeUpdate();
            return user;
        } catch (SQLException e) {
            if (e.getSQLState() != null && e.getSQLState().startsWith("23")) {
                throw new DuplicateUserException("Username already exists: " + user.getUsername());
            }
            throw new RuntimeException("Failed to save user", e);
        }
    }

    @Override
    public void update(User user) {
        String sql = "UPDATE users SET google_id = ?, email = ? WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            if (user.getGoogleId() != null) {
                stmt.setString(1, user.getGoogleId());
            } else {
                stmt.setNull(1, Types.VARCHAR);
            }
            if (user.getEmail() != null) {
                stmt.setString(2, user.getEmail());
            } else {
                stmt.setNull(2, Types.VARCHAR);
            }
            stmt.setObject(3, user.getId());
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update user", e);
        }
    }

    private User mapRow(ResultSet rs) throws SQLException {
        User user = new User();
        user.setId(rs.getObject("id", UUID.class));
        user.setUsername(rs.getString("username"));
        user.setPasswordHash(rs.getString("password_hash"));
        user.setSalt(rs.getString("salt"));
        user.setGoogleId(rs.getString("google_id"));
        user.setEmail(rs.getString("email"));
        Timestamp ts = rs.getTimestamp("created_at");
        if (ts != null) user.setCreatedAt(ts.toInstant());
        return user;
    }
}
