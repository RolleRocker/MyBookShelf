package com.bookshelf;

import java.io.IOException;

public class RequestTooLargeException extends IOException {
    public RequestTooLargeException(String message) {
        super(message);
    }
}
