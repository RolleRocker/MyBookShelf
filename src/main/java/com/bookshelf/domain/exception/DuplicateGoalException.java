package com.bookshelf.domain.exception;

public class DuplicateGoalException extends RuntimeException {
    public DuplicateGoalException(String message) {
        super(message);
    }
}
