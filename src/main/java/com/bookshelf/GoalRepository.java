package com.bookshelf;

import java.util.List;
import java.util.Optional;

public interface GoalRepository {
    List<ReadingGoal> findAll();
    Optional<ReadingGoal> findByYear(int year);
    ReadingGoal save(ReadingGoal goal);
    Optional<ReadingGoal> update(int year, int newTarget);
    boolean delete(int year);
    void clear();
}
