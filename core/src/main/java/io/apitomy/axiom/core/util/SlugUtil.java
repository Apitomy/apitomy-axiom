package io.apitomy.axiom.core.util;

public final class SlugUtil {

    private SlugUtil() {
    }

    /**
     * Converts a name into a URL-safe slug: lowercase, spaces to hyphens,
     * non-alphanumeric characters stripped, consecutive hyphens collapsed.
     */
    public static String slugify(String name) {
        if (name == null) {
            return null;
        }
        return name.trim()
                .toLowerCase()
                .replace(' ', '-')
                .replaceAll("[^a-z0-9-]", "")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-|-$", "");
    }
}
