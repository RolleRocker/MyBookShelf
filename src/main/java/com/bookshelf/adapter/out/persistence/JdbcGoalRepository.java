package com.bookshelf.adapter.out.persistence;

import com.bookshelf.domain.exception.DuplicateGoalException;
import com.bookshelf.domain.model.ReadingGoal;
import com.bookshelf.domain.port.out.GoalRepository;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class JdbcGoalRepository implements GoalRepository {

    private final DataSource dataSource;

    public JdbcGoalRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public List<ReadingGoal> findAll() {
        String sql = "SELECT * FROM reading_goals ORDER BY year ASC";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            List<ReadingGoal> goals = new ArrayList<>();
            while (rs.next()) {
                goals.add(mapRow(rs));
            }
            return goals;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find all goals", e);
        }
    }

    @Override
    public Optional<ReadingGoal> findByYear(int year) {
        String sql = "SELECT * FROM reading_goals WHERE year = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, year);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find goal by year", e);
        }
    }

    @Override
    public ReadingGoal save(ReadingGoal goal) {
        String sql = "INSERT INTO reading_goals (id, year, target, created_at, updated_at) VALUES (?, ?, ?, ?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, goal.getId());
            stmt.setInt(2, goal.getYear());
            stmt.setInt(3, goal.getTarget());
            stmt.setTimestamp(4, Timestamp.from(goal.getCreatedAt()));
            stmt.setTimestamp(5, Timestamp.from(goal.getUpdatedAt()));
            stmt.executeUpdate();
            return goal;
        } catch (SQLException e) {
            if (e.getSQLState() != null && e.getSQLState().equals("23505")) {
                throw new DuplicateGoalException("Goal for year " + goal.getYear() + " already exists");
            }
            throw new RuntimeException("Failed to save goal", e);
        }
    }

    @Override
    public Optional<ReadingGoal> update(int year, int newTarget) {
        String sql = "UPDATE reading_goals SET target = ?, updated_at = ? WHERE year = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            Instant now = Instant.now();
            stmt.setInt(1, newTarget);
            stmt.setTimestamp(2, Timestamp.from(now));
            stmt.setInt(3, year);
            int rows = stmt.executeUpdate();
            if (rows == 0) return Optional.empty();
            return findByYear(year);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update goal", e);
        }
    }

    @Override
    public boolean delete(int year) {
        String sql = "DELETE FROM reading_goals WHERE year = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, year);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete goal", e);
        }
    }

    @Override
    public void clear() {
        String sql = "DELETE FROM reading_goals";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to clear goals", e);
        }
    }

    private ReadingGoal mapRow(ResultSet rs) throws SQLException {
        ReadingGoal goal = new ReadingGoal();
        goal.setId(rs.getObject("id", UUID.class));
        goal.setYear(rs.getInt("year"));
        goal.setTarget(rs.getInt("target"));
        Timestamp createdAt = rs.getTimestamp("created_at");
        if (createdAt != null) goal.setCreatedAt(createdAt.toInstant());
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        if (updatedAt != null) goal.setUpdatedAt(updatedAt.toInstant());
        return goal;
    }
}
