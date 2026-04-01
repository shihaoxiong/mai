package com.mai.deerflow.backend.runtime.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class ThreadNotFoundException extends RuntimeException {

    public ThreadNotFoundException(String threadId) {
        super("Thread not found: " + threadId);
    }
}
