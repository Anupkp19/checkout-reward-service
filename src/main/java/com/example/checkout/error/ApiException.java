package com.example.checkout.error;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final Object extras;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiException(HttpStatus status, String code, String message, Object extras) {
        super(message);
        this.status = status;
        this.code = code;
        this.extras = extras;
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
    public Object getExtras() { return extras; }
}
