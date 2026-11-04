package dev.stockflow.warehouse.observability;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Redacts headers, span attributes, and structured log fields that could hold credentials or signatures.
 */
public final class SensitiveDataRedactor {

    public static final String REDACTED = "[REDACTED]";

    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "authorization",
            "proxy-authorization",
            "cookie",
            "set-cookie",
            "x-webhook-signature",
            "x-api-key",
            "password",
            "secret",
            "token",
            "accesstoken",
            "access_token",
            "refreshtoken",
            "refresh_token",
            "client_secret"
    );

    private static final Pattern JSON_SECRET_FIELD = Pattern.compile(
            "(?i)(\"(?:password|secret|token|accessToken|access_token|refreshToken|refresh_token|authorization|x-webhook-signature)\"\\s*:\\s*\")([^\"]*)(\")"
    );

    private static final Pattern BEARER_OR_HMAC = Pattern.compile(
            "(?i)\\b(Bearer\\s+[A-Za-z0-9._~+/=-]+|sha256=[0-9a-f]{16,128})"
    );

    private static final Pattern KV_SECRET = Pattern.compile(
            "(?i)\\b(password|secret|token|accessToken|access_token|refreshToken|refresh_token|authorization|x-webhook-signature)=([^\\s,&;]+)"
    );

    private SensitiveDataRedactor() {
    }

    public static boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        if (SENSITIVE_KEYS.contains(normalized)) {
            return true;
        }
        return normalized.contains("password")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("authorization")
                || normalized.contains("signature")
                || normalized.contains("cookie");
    }

    public static String redactKeyValue(String key, String value) {
        if (value == null) {
            return null;
        }
        if (isSensitiveKey(key)) {
            return REDACTED;
        }
        return redactText(value);
    }

    public static String redactText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String step1 = JSON_SECRET_FIELD.matcher(text).replaceAll("$1" + REDACTED + "$3");
        String step2 = KV_SECRET.matcher(step1).replaceAll("$1=" + REDACTED);
        return BEARER_OR_HMAC.matcher(step2).replaceAll(REDACTED);
    }

    public static Map<String, String> redactMap(Map<String, String> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>(input.size());
        input.forEach((k, v) -> out.put(k, redactKeyValue(k, v)));
        return out;
    }
}
