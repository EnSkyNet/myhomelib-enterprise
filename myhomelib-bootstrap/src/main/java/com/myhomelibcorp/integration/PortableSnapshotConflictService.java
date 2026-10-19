package com.myhomelibcorp.integration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.myhomelibcorp.application.sync.conflict.SyncConflictReviewItem;
import com.myhomelibcorp.application.sync.conflict.SyncConflictReviewSelection;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Builds a safe, section-level review/merge for portable user-data snapshots.
 * Structural metadata is never user-merged: incompatible schema/format is rejected.
 */
final class PortableSnapshotConflictService {
    private static final Set<String> STRUCTURAL_FIELDS = Set.of("schemaVersion", "format", "exportedAt");
    private static final String KEY_PREFIX = "portable-user-data:";

    private static final Map<String, String> LABEL_KEYS = Map.ofEntries(
            Map.entry("bookState", "ui.sync.conflict.entity.book_state"),
            Map.entry("readingProgress", "ui.sync.conflict.entity.reading_progress"),
            Map.entry("readingHistory", "ui.sync.conflict.entity.reading_history"),
            Map.entry("readingStats", "ui.sync.conflict.entity.reading_stats"),
            Map.entry("bookmarks", "ui.sync.conflict.entity.bookmarks"),
            Map.entry("annotations", "ui.sync.conflict.entity.annotations"),
            Map.entry("annotationTags", "ui.sync.conflict.entity.annotation_tags"),
            Map.entry("groups", "ui.sync.conflict.entity.groups"),
            Map.entry("groupMemberships", "ui.sync.conflict.entity.group_memberships"),
            Map.entry("savedSearches", "ui.sync.conflict.entity.saved_searches"),
            Map.entry("customFieldDefinitions", "ui.sync.conflict.entity.custom_field_definitions"),
            Map.entry("customFieldValues", "ui.sync.conflict.entity.custom_field_values"),
            Map.entry("filterSettings", "ui.sync.conflict.entity.filter_settings"),
            Map.entry("readerSettings", "ui.sync.conflict.entity.reader_settings")
    );

    private final ObjectMapper mapper;

    PortableSnapshotConflictService(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    List<SyncConflictReviewItem> conflicts(byte[] localBytes, byte[] remoteBytes) throws IOException {
        ObjectNode local = parse(localBytes, "локальна");
        ObjectNode remote = parse(remoteBytes, "віддалена");
        validateCompatible(local, remote);

        List<SyncConflictReviewItem> result = new ArrayList<>();
        for (String field : unionUserFields(local, remote)) {
            JsonNode left = local.get(field);
            JsonNode right = remote.get(field);
            if (Objects.equals(left, right)) continue;
            result.add(new SyncConflictReviewItem(
                    KEY_PREFIX + field,
                    label(field),
                    SyncConflictReviewItem.Text.of("ui.sync.conflict.reason.portable_section_changed"),
                    summarize(left),
                    summarize(right)));
        }
        return List.copyOf(result);
    }

    byte[] merge(byte[] localBytes, byte[] remoteBytes,
                 List<SyncConflictReviewSelection> selections) throws IOException {
        ObjectNode local = parse(localBytes, "локальна");
        ObjectNode remote = parse(remoteBytes, "віддалена");
        validateCompatible(local, remote);

        List<String> conflicts = unionUserFields(local, remote).stream()
                .filter(field -> !Objects.equals(local.get(field), remote.get(field)))
                .toList();
        Map<String, SyncConflictReviewSelection.Side> choices = validateSelections(conflicts, selections);

        ObjectNode merged = local.deepCopy();
        for (String field : conflicts) {
            SyncConflictReviewSelection.Side side = choices.get(field);
            JsonNode chosen = side == SyncConflictReviewSelection.Side.LOCAL ? local.get(field) : remote.get(field);
            if (chosen == null || chosen.isMissingNode()) merged.remove(field);
            else merged.set(field, chosen.deepCopy());
        }
        merged.remove("exportedAt");
        return mapper.writeValueAsBytes(merged);
    }

    private Map<String, SyncConflictReviewSelection.Side> validateSelections(
            List<String> conflicts, List<SyncConflictReviewSelection> selections) {
        List<SyncConflictReviewSelection> safe = selections == null ? List.of() : List.copyOf(selections);
        if (safe.size() != conflicts.size()) {
            throw new IllegalArgumentException("Для кожного конфліктного розділу потрібно вибрати локальну або віддалену версію");
        }
        Set<String> expected = new LinkedHashSet<>(conflicts);
        Map<String, SyncConflictReviewSelection.Side> result = new HashMap<>();
        for (SyncConflictReviewSelection selection : safe) {
            if (selection == null) throw new IllegalArgumentException("Порожній вибір конфлікту");
            String key = selection.logicalKey();
            if (!key.startsWith(KEY_PREFIX)) throw new IllegalArgumentException("Невідомий конфлікт: " + key);
            String field = key.substring(KEY_PREFIX.length());
            if (!expected.contains(field)) throw new IllegalArgumentException("Конфлікт уже застарів: " + key);
            if (result.putIfAbsent(field, selection.side()) != null) {
                throw new IllegalArgumentException("Повторний вибір для конфлікту: " + key);
            }
        }
        if (!result.keySet().equals(expected)) {
            throw new IllegalArgumentException("Не для всіх конфліктів вибрано версію");
        }
        return result;
    }

    private ObjectNode parse(byte[] bytes, String side) throws IOException {
        JsonNode root = mapper.readTree(bytes == null ? new byte[0] : bytes);
        if (!(root instanceof ObjectNode object)) {
            throw new IOException("Некоректна " + side + " версія користувацьких даних");
        }
        return object;
    }

    private static void validateCompatible(ObjectNode local, ObjectNode remote) throws IOException {
        JsonNode localSchema = local.get("schemaVersion");
        JsonNode remoteSchema = remote.get("schemaVersion");
        JsonNode localFormat = local.get("format");
        JsonNode remoteFormat = remote.get("format");
        if (localSchema == null || remoteSchema == null || !Objects.equals(localSchema, remoteSchema)) {
            throw new IOException("Версії формату користувацьких даних несумісні; оновіть MyHomeLib на всіх пристроях");
        }
        if (localFormat == null || remoteFormat == null || !Objects.equals(localFormat, remoteFormat)) {
            throw new IOException("Формати користувацьких даних несумісні; автоматичне об’єднання зупинено");
        }
    }

    private static List<String> unionUserFields(ObjectNode local, ObjectNode remote) {
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        fields.addAll(local.propertyNames());
        fields.addAll(remote.propertyNames());
        fields.removeAll(STRUCTURAL_FIELDS);
        return List.copyOf(fields);
    }

    private static SyncConflictReviewItem.Text label(String field) {
        String key = LABEL_KEYS.get(field);
        return key == null
                ? SyncConflictReviewItem.Text.of("ui.sync.conflict.entity.user_data", field)
                : SyncConflictReviewItem.Text.of(key);
    }

    private static SyncConflictReviewItem.Text summarize(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return SyncConflictReviewItem.Text.of("ui.sync.conflict.snapshot.missing");
        }
        if (node.isArray()) {
            return SyncConflictReviewItem.Text.of("ui.sync.conflict.snapshot.records", node.size());
        }
        if (node.isObject()) {
            return SyncConflictReviewItem.Text.of("ui.sync.conflict.snapshot.fields", node.size());
        }
        if (node.isNull()) {
            return SyncConflictReviewItem.Text.of("ui.sync.conflict.snapshot.no_value");
        }
        String text = node.asString();
        if (text.length() > 160) text = text.substring(0, DesktopIntegrationSupport.safeTextCut(text, 160)) + "…";
        return SyncConflictReviewItem.Text.of("ui.sync.conflict.snapshot.value", text);
    }
}
