package com.mai.deerflow.backend.runtime.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 当请求传入的 run 级运行参数当前不支持或取值非法时抛出。
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class InvalidRunOptionException extends RuntimeException {

    public InvalidRunOptionException(String message) {
        super(message);
    }
}
