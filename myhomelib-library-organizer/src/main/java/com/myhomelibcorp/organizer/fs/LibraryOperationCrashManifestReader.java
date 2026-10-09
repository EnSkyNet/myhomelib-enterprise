package com.myhomelibcorp.organizer.fs;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.myhomelibcorp.application.util.SafeFilePublication;
import com.myhomelibcorp.organizer.journal.OrganizationItemState;
import com.myhomelibcorp.organizer.journal.OrganizationOperationState;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Strict parser for Organizer crash manifests. Invalid or unknown schemas are never used for automatic recovery. */
@Component
public final class LibraryOperationCrashManifestReader {
    private static final int MIN_SUPPORTED_SCHEMA = 1;
    private static final int MAX_SUPPORTED_SCHEMA = 2;
    private static final int MAX_MANIFEST_BYTES = 4 * 1024 * 1024;
    private final ObjectMapper json = new ObjectMapper();

    public LibraryOperationCrashSnapshot read(Path manifest) throws Exception {
        if (manifest == null) throw new IllegalArgumentException("Crash manifest is missing or unsafe");
        Path file = OrganizerPathSafety.canonicalExistingFile(
                manifest.toAbsolutePath().normalize(), "Organizer crash manifest");
        String expectedHash = SafeFilePublication.sha256(file, "Organizer crash manifest snapshot");
        byte[] bytes = SafeFilePublication.readOwnedBytes(
                file, expectedHash, MAX_MANIFEST_BYTES, "Organizer crash manifest");
        JsonNode root = json.readTree(bytes);
        int schema = requiredInt(root, "schemaVersion");
        if (schema < MIN_SUPPORTED_SCHEMA || schema > MAX_SUPPORTED_SCHEMA)
            throw new IllegalArgumentException("Unsupported crash manifest schema: " + schema);
        UUID operationId = UUID.fromString(requiredText(root, "operationId"));
        OrganizationOperationState state = OrganizationOperationState.valueOf(requiredText(root, "state"));
        List<LibraryOperationCrashSnapshot.Item> items = new ArrayList<>();
        JsonNode array = root.path("items");
        if (!array.isArray()) throw new IllegalArgumentException("Crash manifest items must be an array");
        for (JsonNode node : array) {
            items.add(new LibraryOperationCrashSnapshot.Item(
                    UUID.fromString(requiredText(node, "itemId")),
                    requiredText(node, "action"),
                    requiredPath(node, "sourcePath"),
                    schema >= 2 ? optionalText(node, "sourceEntry") : "",
                    optionalPath(node, "targetPath"),
                    OrganizationItemState.valueOf(requiredText(node, "state")),
                    optionalText(node, "sourceSha256"),
                    optionalText(node, "targetSha256"),
                    optionalPath(node, "backupPath"),
                    optionalText(node, "message")));
        }
        Instant updatedAt = Instant.parse(requiredText(root, "updatedAt"));
        return new LibraryOperationCrashSnapshot(schema, operationId, optionalText(root, "profileId"),
                requiredPath(root, "sourceRoot"), requiredPath(root, "destinationRoot"), state,
                optionalText(root, "message"), updatedAt, items);
    }

    private String requiredText(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value.isBlank()) throw new IllegalArgumentException("Crash manifest field is missing: " + field);
        return value;
    }

    private int requiredInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToInt()) throw new IllegalArgumentException("Crash manifest integer field is missing: " + field);
        return value.intValue();
    }

    private String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asString("").trim();
    }

    private Path requiredPath(JsonNode node, String field) {
        Path value = optionalPath(node, field);
        if (value == null) throw new IllegalArgumentException("Crash manifest path field is missing: " + field);
        return value;
    }

    private Path optionalPath(JsonNode node, String field) {
        String value = optionalText(node, field);
        return value.isBlank() ? null : Path.of(value).toAbsolutePath().normalize();
    }
}
