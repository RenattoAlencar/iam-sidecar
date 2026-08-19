package com.development.iam.sidecar.route;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;


public final class PathNormalizer {

    private static final int MAX_LENGTH = 2048;

    private static final String PATH_SEPARATOR = "/";
    private static final char SEGMENT_PARAM_SEPARATOR = ';';
    private static final String CURRENT_SEGMENT = ".";
    private static final String PARENT_SEGMENT = "..";

    private static final String[] ENCODED_SEPARATORS = {"%2f", "%2F"};

    private static final Pattern PATH_SEPARATOR_PATTERN =
            Pattern.compile(Pattern.quote(PATH_SEPARATOR));

    private PathNormalizer() {
    }

    public static Optional<String> normalize(String rawPath) {

        if (rawPath == null || rawPath.isBlank() || rawPath.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        if (!rawPath.startsWith(PATH_SEPARATOR)) {
            return Optional.empty();
        }
        if (containsEncodedSeparator(rawPath)) {
            return Optional.empty();
        }

        String decoded = decodeOnce(rawPath);
        if (decoded == null) {
            return Optional.empty();
        }
        if (isDoubleEncoded(decoded)) {
            return Optional.empty();
        }
        if (hasUnsafeCharacters(decoded)) {
            return Optional.empty();
        }

        return canonicalSegments(decoded);
    }

    private static boolean containsEncodedSeparator(String rawPath) {

        for (String encoded : ENCODED_SEPARATORS) {
            if (rawPath.contains(encoded)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDoubleEncoded(String decoded) {

        String twiceDecoded = decodeOnce(decoded);
        return twiceDecoded != null && !twiceDecoded.equals(decoded);
    }

    private static String decodeOnce(String value) {

        if (value.indexOf('%') < 0) {
            return value;
        }
        StringBuilder decoded = new StringBuilder(value.length());

        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current != '%') {
                decoded.append(current);
                continue;
            }
            if (i + 2 >= value.length()) {
                return null;
            }
            int high = Character.digit(value.charAt(i + 1), 16);
            int low = Character.digit(value.charAt(i + 2), 16);
            if (high < 0 || low < 0) {
                return null;
            }
            decoded.append((char) ((high << 4) + low));
            i += 2;
        }
        return decoded.toString();
    }

    private static boolean hasUnsafeCharacters(String decoded) {

        for (int i = 0; i < decoded.length(); i++) {
            char current = decoded.charAt(i);
            if (current == '\\' || current < 0x20 || current == 0x7F) {
                return true;
            }
        }
        return false;
    }

    private static Optional<String> canonicalSegments(String decoded) {

        List<String> segments = new ArrayList<>();

        for (String rawSegment : PATH_SEPARATOR_PATTERN.split(decoded, -1)) {
            int paramStart = rawSegment.indexOf(SEGMENT_PARAM_SEPARATOR);
            String segment = paramStart < 0 ? rawSegment : rawSegment.substring(0, paramStart);

            if (segment.isEmpty()) {
                continue;
            }
            if (CURRENT_SEGMENT.equals(segment) || PARENT_SEGMENT.equals(segment)) {
                return Optional.empty();
            }
            segments.add(segment);
        }

        return Optional.of(PATH_SEPARATOR + String.join(PATH_SEPARATOR, segments));
    }
}