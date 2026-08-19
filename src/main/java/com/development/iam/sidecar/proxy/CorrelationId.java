package com.development.iam.sidecar.proxy;

import java.security.SecureRandom;
import java.util.Base64;

public final class CorrelationId {

    public static final String HEADER = "X-Correlation-Id";


    public static final String MDC_KEY = "correlationId";

    private static final int MAX_LENGTH = 64;

    private static final int GENERATED_BYTES = 12;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private CorrelationId() {
    }

    public static String resolve(String received) {
        return isUsable(received) ? received : generate();
    }

    private static boolean isUsable(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_LENGTH) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            boolean allowed = Character.isLetterOrDigit(current) || current == '-' || current == '_';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    private static String generate() {
        byte[] bytes = new byte[GENERATED_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }
}