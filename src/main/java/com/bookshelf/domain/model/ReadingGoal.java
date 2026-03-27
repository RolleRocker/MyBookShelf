package com.bookshelf.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public class ReadingGoal {
    private UUID id;
    private int year;
    private int target;
    private Instant createdAt;
    private transient Instant updatedAt;

    public ReadingGoal() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public int getYear() { return year; }
    public void setYear(int year) { this.year = year; }

    public int getTarget() { return target; }
    public void setTarget(int target) { this.target = target; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ReadingGoal that = (ReadingGoal) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "ReadingGoal{id=" + id + ", year=" + year + ", target=" + target + "}";
    }
}
