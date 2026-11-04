package io.github.qingyicode.repopilot.common;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(DomainException.class)
    ResponseEntity<Map<String, Object>> domain(DomainException error) {
        var response=ResponseEntity.status(error.status());
        if(error.retryAfterSeconds()!=null) return response.header("Retry-After",error.retryAfterSeconds().toString())
                .body(Map.of("code",error.code(),"retryAfterSeconds",error.retryAfterSeconds()));
        return response.body(Map.of("code", error.code()));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<Map<String, String>> invalid(Exception error) {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_REQUEST"));
    }
}
