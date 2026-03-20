package com.bookshelf.adapter.out.persistence;

import com.bookshelf.domain.exception.DuplicateGoalException;
import com.bookshelf.domain.model.ReadingGoal;
import com.bookshelf.domain.port.out.GoalRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryGoalRepository implements GoalRepository {

    private final ConcurrentHashMap<Integer, ReadingGoal> goals = new ConcurrentHashMap<>();

    @Override
    public List<ReadingGoal> findAll() {
        return new ArrayList<>(goals.values());
    }

    @Override
    public Optional<ReadingGoal> findByYear(int year) {
        return Optional.ofNullable(goals.get(year));
    }

    @Override
    public ReadingGoal save(ReadingGoal goal) {
        if (goals.containsKey(goal.getYear())) {
            throw new DuplicateGoalException("Goal for year " + goal.getYear() + " already exists");
        }
        goals.put(goal.getYear(), goal);
        return goal;
    }

    @Override
    public Optional<ReadingGoal> update(int year, int newTarget) {
        ReadingGoal goal = goals.get(year);
        if (goal == null) return Optional.empty();
        goal.setTarget(newTarget);
        goal.setUpdatedAt(Instant.now());
        return Optional.of(goal);
    }

    @Override
    public boolean delete(int year) {
        return goals.remove(year) != null;
    }

    @Override
    public void clear() {
        goals.clear();
    }
}
