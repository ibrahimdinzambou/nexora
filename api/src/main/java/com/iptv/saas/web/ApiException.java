package com.iptv.saas.web;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String message, String code) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message, "not_found");
    }

    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, message, "forbidden");
    }

    public static ApiException paymentRequired(String message) {
        return new ApiException(HttpStatus.PAYMENT_REQUIRED, message, "payment_required");
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, message, "unauthorized");
    }

    public static ApiException validation(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, message, "validation_error");
    }

    public static ApiException serviceUnavailable(String message) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, message, "service_unavailable");
    }
}
