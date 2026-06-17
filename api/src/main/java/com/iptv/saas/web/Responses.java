package com.iptv.saas.web;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class Responses {
    private Responses() {
    }

    public static Map<String, Object> ok(Object data) {
        Map<String, Object> body = base(true);
        body.put("data", data);
        return body;
    }

    public static Map<String, Object> message(String message) {
        Map<String, Object> body = base(true);
        body.put("message", message);
        return body;
    }

    public static Map<String, Object> error(String message, String code) {
        Map<String, Object> body = base(false);
        body.put("message", message);
        body.put("code", code);
        return body;
    }

    public static Map<String, Object> map() {
        return new LinkedHashMap<>();
    }

    private static Map<String, Object> base(boolean success) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", success);
        body.put("timestamp", Instant.now().toString());
        return body;
    }
}
