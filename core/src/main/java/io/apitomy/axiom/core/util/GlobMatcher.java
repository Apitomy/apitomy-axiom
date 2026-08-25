package io.apitomy.axiom.core.util;

import java.util.List;
import java.util.regex.Pattern;

public final class GlobMatcher {

    private GlobMatcher() {
    }

    /**
     * Checks whether a glob pattern matches a value. The only special character
     * is {@code *}, which matches any sequence of characters. Matching is
     * case-insensitive.
     */
    public static boolean matches(String pattern, String value) {
        if (pattern == null || pattern.isEmpty() || value == null || value.isEmpty()) {
            return false;
        }
        String regex = globToRegex(pattern.toLowerCase());
        return Pattern.matches(regex, value.toLowerCase());
    }

    /**
     * Returns {@code true} if any capability pattern in the list matches the
     * given value.
     */
    public static boolean anyMatches(List<String> capabilities, String value) {
        if (capabilities == null || value == null) {
            return false;
        }
        return capabilities.stream().anyMatch(cap -> matches(cap, value));
    }

    private static String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                sb.append(".*");
            } else {
                sb.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return sb.toString();
    }
}
