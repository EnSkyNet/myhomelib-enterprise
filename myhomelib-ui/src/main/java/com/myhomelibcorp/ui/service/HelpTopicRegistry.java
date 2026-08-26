package com.myhomelibcorp.ui.service;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Central mapping from UI workspaces/dialog contexts to bundled help topics.
 * Controllers identify their context; they do not know resource paths.
 */
@Component
public class HelpTopicRegistry {
    private static final Map<String, String> WORKSPACES = Map.ofEntries(
            Map.entry("dashboard", "index"),
            Map.entry("author", "navigation"), Map.entry("series", "navigation"),
            Map.entry("genre", "navigation"), Map.entry("year", "navigation"),
            Map.entry("language", "navigation"), Map.entry("archive", "navigation"),
            Map.entry("keyword", "navigation"), Map.entry("group-nav", "navigation"),
            Map.entry("groups", "navigation"), Map.entry("reviews", "navigation"),
            Map.entry("all-books", "navigation"), Map.entry("already-read", "navigation"),
            Map.entry("history", "navigation"), Map.entry("updates", "updates"),
            Map.entry("search", "search"), Map.entry("book", "details"),
            Map.entry("reader", "reader"), Map.entry("new-reader", "reader"),
            Map.entry("collection", "collections"), Map.entry("import", "import")
    );

    private static final Map<String, String> CONTEXTS = Map.ofEntries(
            Map.entry("settings", "settings"), Map.entry("dialog:settings", "settings"),
            Map.entry("dialog:inpx", "inpx"), Map.entry("dialog:export", "export"),
            Map.entry("dialog:device", "device"), Map.entry("dialog:opds", "opds"),
            Map.entry("dialog:maintenance", "maintenance"), Map.entry("dialog:actions", "actions"),
            Map.entry("dialog:filters", "filters"), Map.entry("dialog:backup", "backup")
    );

    private static final Set<String> TOPICS = Set.of(
            "index", "collections", "inpx", "import", "search", "navigation", "updates",
            "filters", "details", "reader", "export", "device", "settings", "portable",
            "hlc2", "archives", "mcp", "maintenance", "actions", "opds", "backup"
    );

    public String topicForWorkspace(String workspaceType) {
        return WORKSPACES.getOrDefault(normalize(workspaceType), "index");
    }

    public String topicForContext(String contextId) {
        String normalized = normalize(contextId);
        if (TOPICS.contains(normalized)) return normalized;
        return CONTEXTS.getOrDefault(normalized, WORKSPACES.getOrDefault(normalized, "index"));
    }

    public boolean isKnownTopic(String topic) {
        return TOPICS.contains(normalize(topic));
    }

    public Set<String> topics() {
        return TOPICS;
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT).trim().replaceAll("[^a-z0-9:_-]", "");
    }
}
