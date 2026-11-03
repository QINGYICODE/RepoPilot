package io.github.qingyicode.repopilot.common;

import org.springframework.http.HttpStatus;

public class DomainException extends RuntimeException {
    private final String code;
    private final HttpStatus status;
    public DomainException(HttpStatus status, String code) {
        super(code);
        this.code = code;
        this.status = status;
    }
    public String code() { return code; }
    public HttpStatus status() { return status; }
    public static DomainException invalid(String code) { return new DomainException(HttpStatus.BAD_REQUEST, code); }
    public static DomainException missing(String code) { return new DomainException(HttpStatus.NOT_FOUND, code); }
    public static DomainException conflict(String code) { return new DomainException(HttpStatus.CONFLICT, code); }
}
