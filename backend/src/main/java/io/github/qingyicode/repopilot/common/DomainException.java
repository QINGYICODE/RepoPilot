package io.github.qingyicode.repopilot.common;

import org.springframework.http.HttpStatus;

public class DomainException extends RuntimeException {
    private final String code;
    private final HttpStatus status;
    private final Integer retryAfterSeconds;
    public DomainException(HttpStatus status, String code) {
        this(status,code,null);
    }
    public DomainException(HttpStatus status, String code, Integer retryAfterSeconds) {
        super(code);
        this.code = code;
        this.status = status;
        this.retryAfterSeconds = retryAfterSeconds;
    }
    public String code() { return code; }
    public HttpStatus status() { return status; }
    public Integer retryAfterSeconds() { return retryAfterSeconds; }
    public static DomainException invalid(String code) { return new DomainException(HttpStatus.BAD_REQUEST, code); }
    public static DomainException missing(String code) { return new DomainException(HttpStatus.NOT_FOUND, code); }
    public static DomainException conflict(String code) { return new DomainException(HttpStatus.CONFLICT, code); }
}
