package com.dbmerge.web;

import java.sql.SQLException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler(SQLException.class)
    ResponseEntity<Map<String, String>> database(SQLException e) {
        String first = e.getMessage() == null ? "Database error" : e.getMessage().lines().findFirst().orElse("Database error");
        log.warn("Database error: {}", first);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", first));
    }

    /** Unknown URL, wrong method, wrong content type...: Spring already knows the right status; no stack trace. */
    @ExceptionHandler(jakarta.servlet.ServletException.class)
    ResponseEntity<Map<String, String>> framework(jakarta.servlet.ServletException e) {
        if (e instanceof org.springframework.web.ErrorResponse r) {
            String reason = r.getBody().getTitle() == null ? "Request failed" : r.getBody().getTitle();
            return ResponseEntity.status(r.getStatusCode()).body(Map.of("error", reason));
        }
        return unexpected(e);
    }

    /** Malformed JSON, or a value of the wrong type (e.g. an unknown conflict action). The detail is not echoed. */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, String>> unreadable(org.springframework.http.converter.HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "The request body is not valid JSON for this call."));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> unexpected(Exception e) {
        log.error("Unexpected error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Unexpected error - see application log."));
    }
}
