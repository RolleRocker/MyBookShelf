package com.bookshelf;

public class DuplicateGoalException extends RuntimeException {
    public DuplicateGoalException(String message) {
        super(message);
    }
}
