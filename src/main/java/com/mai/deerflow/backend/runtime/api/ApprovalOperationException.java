package com.mai.deerflow.backend.runtime.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class ApprovalOperationException extends RuntimeException {

    public ApprovalOperationException(String message) {
        super(message);
    }
}
