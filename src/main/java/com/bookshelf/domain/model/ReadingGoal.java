package com.bookshelf.domain.model;

import java.time.Instant;
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
}
