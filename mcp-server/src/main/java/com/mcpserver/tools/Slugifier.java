package com.mcpserver.tools;

import java.util.Locale;

/**
 * Turns request/app titles into tool-id fragments: "Get Space List!" → "get_space_list",
 * camelCase operationIds too: "getPetById" → "get_pet_by_id".
 */
public final class Slugifier {

    private Slugifier() {
    }

    public static String slug(String raw) {
        if (raw == null || raw.isBlank()) return "unnamed";
        String s = raw.trim()
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "")
                .replaceAll("_{2,}", "_");
        return s.isEmpty() ? "unnamed" : s;
    }
}
