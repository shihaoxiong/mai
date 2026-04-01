package com.mai.deerflow.backend.runtime.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
/**
 * 当同一线程被绑定到不同 userId 时抛出，避免后续长期记忆串线。
 */
public class ThreadContextConflictException extends RuntimeException {

    public ThreadContextConflictException(String threadId, String existingUserId, String requestedUserId) {
        super("Thread %s is already bound to userId %s, but received %s"
                .formatted(threadId, existingUserId, requestedUserId));
    }
}
