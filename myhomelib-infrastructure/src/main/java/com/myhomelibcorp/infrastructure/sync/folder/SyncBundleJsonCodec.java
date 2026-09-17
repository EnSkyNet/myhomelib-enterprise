package com.myhomelibcorp.infrastructure.sync.folder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myhomelibcorp.domain.model.sync.ChangeSet;
import com.myhomelibcorp.domain.model.sync.SyncEntityType;
import com.myhomelibcorp.domain.model.sync.SyncRecord;
import com.myhomelibcorp.domain.model.sync.SyncSchema;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded JSON codec for portable sync bundles. */
public final class SyncBundleJsonCodec {
    public static final int MAX_BUNDLE_BYTES = 16 * 1024 * 1024;
    private static final int MAX_RECORDS = 10_000;
    private static final int MAX_PAYLOAD_FIELDS = 256;
    private static final int MAX_TEXT = 1_000_000;

    private final ObjectMapper mapper;

    public SyncBundleJsonCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public byte[] encode(ChangeSet value) {
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("schemaVersion", value.schemaVersion());
            root.put("changeSetId", value.changeSetId());
            root.put("sourceDeviceId", value.sourceDeviceId());
            root.put("sequence", value.sequence());
            root.put("previousCursor", value.previousCursor());
            root.put("createdAt", value.createdAt().toString());
            ArrayNode records = root.putArray("records");
            for (SyncRecord record : value.records()) {
                ObjectNode item = records.addObject();
                item.put("syncId", record.syncId());
                item.put("entityType", record.entityType().name());
                item.put("localKey", record.localKey());
                item.put("baseVersion", record.baseVersion());
                item.put("version", record.version());
                item.put("updatedAt", record.updatedAt().toString());
                item.put("deviceId", record.deviceId());
                item.put("schemaVersion", record.schemaVersion());
                item.put("tombstone", record.tombstone());
                ObjectNode payload = item.putObject("payload");
                record.payload().forEach(payload::put);
            }
            byte[] bytes = mapper.writeValueAsBytes(root);
            requireBundleSize(bytes.length);
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Could not encode sync bundle", e);
        }
    }

    public ChangeSet decode(byte[] bytes) {
        if (bytes == null) throw new IllegalArgumentException("bundle bytes are required");
        requireBundleSize(bytes.length);
        try {
            JsonNode root = mapper.readTree(bytes);
            if (root == null || !root.isObject()) throw new IllegalArgumentException("Sync bundle root must be an object");
            int schemaVersion = intValue(root, "schemaVersion");
            SyncSchema.requireSupported(schemaVersion);
            JsonNode recordNodes = root.get("records");
            if (recordNodes == null || !recordNodes.isArray()) throw new IllegalArgumentException("records must be an array");
            if (recordNodes.size() > MAX_RECORDS) throw new IllegalArgumentException("Too many sync records");

            List<SyncRecord> records = new ArrayList<>(recordNodes.size());
            for (JsonNode item : recordNodes) {
                JsonNode payloadNode = item.get("payload");
                if (payloadNode == null || !payloadNode.isObject()) throw new IllegalArgumentException("payload must be an object");
                if (payloadNode.size() > MAX_PAYLOAD_FIELDS) throw new IllegalArgumentException("Too many payload fields");
                Map<String, String> payload = new LinkedHashMap<>();
                for (Map.Entry<String, JsonNode> field : payloadNode.properties()) {
                    if (!field.getValue().isTextual()) throw new IllegalArgumentException("payload values must be strings");
                    payload.put(limited(field.getKey(), "payload key"), limited(field.getValue().asText(), "payload value"));
                }
                records.add(new SyncRecord(
                        text(item, "syncId"),
                        SyncEntityType.valueOf(text(item, "entityType")),
                        text(item, "localKey"),
                        longValue(item, "baseVersion"),
                        longValue(item, "version"),
                        Instant.parse(text(item, "updatedAt")),
                        text(item, "deviceId"),
                        intValue(item, "schemaVersion"),
                        booleanValue(item, "tombstone"),
                        payload));
            }
            return new ChangeSet(text(root, "changeSetId"), text(root, "sourceDeviceId"),
                    longValue(root, "sequence"), optionalText(root, "previousCursor"),
                    Instant.parse(text(root, "createdAt")), schemaVersion, records);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid sync bundle", e);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) throw new IllegalArgumentException(field + " must be text");
        return limited(value.asText(), field);
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return "";
        if (!value.isTextual()) throw new IllegalArgumentException(field + " must be text");
        return limited(value.asText(), field);
    }

    private static int intValue(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToInt()) throw new IllegalArgumentException(field + " must be int");
        return value.intValue();
    }

    private static long longValue(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToLong()) throw new IllegalArgumentException(field + " must be long");
        return value.longValue();
    }

    private static boolean booleanValue(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isBoolean()) throw new IllegalArgumentException(field + " must be boolean");
        return value.booleanValue();
    }

    private static String limited(String value, String field) {
        if (value == null) throw new IllegalArgumentException(field + " is required");
        if (value.length() > MAX_TEXT) throw new IllegalArgumentException(field + " exceeds size limit");
        return value;
    }

    private static void requireBundleSize(int size) {
        if (size < 2 || size > MAX_BUNDLE_BYTES) {
            throw new IllegalArgumentException("Sync bundle size is outside allowed range: " + size);
        }
    }
}
