package com.mai.deerflow.backend.runtime.upload;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class UploadNotFoundException extends RuntimeException {

    public UploadNotFoundException(String threadId, String filename) {
        super("Upload not found for thread %s: %s".formatted(threadId, filename));
    }
}
