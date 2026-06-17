package com.iptv.saas.web;

import jakarta.validation.ConstraintViolationException;
import org.apache.catalina.connector.ClientAbortException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> handleApi(ApiException exception) {
        return ResponseEntity.status(exception.status()).body(Responses.error(exception.getMessage(), exception.code()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException exception) {
        List<Map<String, String>> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of("field", error.getField(), "message", String.valueOf(error.getDefaultMessage())))
                .toList();
        Map<String, Object> body = Responses.error("Validation failed", "validation_error");
        body.put("errors", errors);
        return ResponseEntity.unprocessableEntity().body(body);
    }

    @ExceptionHandler({ConstraintViolationException.class, DataIntegrityViolationException.class})
    ResponseEntity<Map<String, Object>> handleData(RuntimeException exception) {
        return ResponseEntity.unprocessableEntity().body(Responses.error(exception.getMessage(), "validation_error"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Map<String, Object>> handleDenied() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Responses.error("Permission insuffisante", "forbidden"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Map<String, Object>> handleMissingResource() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Responses.error("Ressource introuvable", "not_found"));
    }

    @ExceptionHandler({ClientAbortException.class, AsyncRequestNotUsableException.class})
    void handleClientAbort() {
        // The browser closed the response after a route change, seek, or player shutdown.
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> handleGeneric(Exception exception) {
        Map<String, Object> body = Responses.error("Erreur interne", "internal_error");
        body.put("detail", exception.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
