package com.codeclassic.grubby.exception;

import com.codeclassic.grubby.api.dto.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex,
                                                          HttpServletRequest req) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .orElse("Validation failed");
        log.warn("Validation error on {}: {}", req.getRequestURI(), message);
        return ResponseEntity.badRequest().body(
                new ErrorResponse(Instant.now(), HttpStatus.BAD_REQUEST.value(),
                        "Bad Request", message, req.getRequestURI()));
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ErrorResponse> handleInvalid(InvalidRequestException ex,
                                                       HttpServletRequest req) {
        log.warn("Invalid request on {}: {}", req.getRequestURI(), ex.getMessage());
        return ResponseEntity.badRequest().body(
                new ErrorResponse(Instant.now(), HttpStatus.BAD_REQUEST.value(),
                        "Bad Request", ex.getMessage(), req.getRequestURI()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(IllegalArgumentException ex,
                                                        HttpServletRequest req) {
        log.warn("Resource not found on {}: {}", req.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                new ErrorResponse(Instant.now(), HttpStatus.NOT_FOUND.value(),
                        "Not Found", ex.getMessage(), req.getRequestURI()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatus(ResponseStatusException ex,
                                                              HttpServletRequest req) {
        log.warn("ResponseStatus error on {}: {}", req.getRequestURI(), ex.getMessage());
        // ex.getMessage() includes the status code prefix (e.g. "404 NOT_FOUND \"reason\"").
        // Use getReason() for a clean human-readable message in the response body.
        String message = ex.getReason() != null ? ex.getReason() : ex.getMessage();
        return ResponseEntity.status(ex.getStatusCode()).body(
                new ErrorResponse(Instant.now(), ex.getStatusCode().value(),
                        ex.getReason() != null ? ex.getReason() : "Error",
                        message, req.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex, HttpServletRequest req) {
        log.error("Unhandled exception on {}", req.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                new ErrorResponse(Instant.now(), HttpStatus.INTERNAL_SERVER_ERROR.value(),
                        "Internal Server Error",
                        "An unexpected error occurred. Please try again later.",
                        req.getRequestURI()));
    }
}
