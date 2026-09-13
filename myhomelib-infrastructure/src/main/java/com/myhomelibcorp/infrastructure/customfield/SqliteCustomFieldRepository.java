package com.myhomelibcorp.infrastructure.customfield;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myhomelibcorp.application.port.out.customfield.CustomFieldRepository;
import com.myhomelibcorp.domain.model.customfield.CustomFieldDefinition;
import com.myhomelibcorp.domain.model.customfield.CustomFieldDeletePolicy;
import com.myhomelibcorp.domain.model.customfield.CustomFieldType;
import com.myhomelibcorp.domain.model.customfield.CustomFieldValue;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class SqliteCustomFieldRepository implements CustomFieldRepository {
    private final CollectionManager collections;
    private final ObjectMapper mapper;

    public SqliteCustomFieldRepository(CollectionManager collections, ObjectMapper mapper) {
        this.collections = collections;
        this.mapper = mapper;
    }

    private JdbcTemplate jdbc() { return collections.getCurrentJdbcTemplate(); }

    @Override
    public List<CustomFieldDefinition> findDefinitions() {
        return jdbc().query("SELECT id,name,field_type,enum_options_json FROM custom_field_definitions ORDER BY name COLLATE NOCASE,id",
                (rs, rowNum) -> definition(rs.getLong("id"), rs.getString("name"), rs.getString("field_type"), rs.getString("enum_options_json")));
    }

    @Override
    public Optional<CustomFieldDefinition> findDefinition(long id) {
        return jdbc().query("SELECT id,name,field_type,enum_options_json FROM custom_field_definitions WHERE id=?",
                (rs, rowNum) -> definition(rs.getLong("id"), rs.getString("name"), rs.getString("field_type"), rs.getString("enum_options_json")), id)
                .stream().findFirst();
    }

    @Override
    @Transactional
    public CustomFieldDefinition saveDefinition(CustomFieldDefinition definition) {
        if (definition == null) throw new IllegalArgumentException("Custom field definition is required");
        String options = encode(definition.enumOptions());
        try {
            if (definition.id() == null) {
                jdbc().update("INSERT INTO custom_field_definitions(name,field_type,enum_options_json) VALUES(?,?,?)",
                        definition.name(), definition.type().name(), options);
                Long id = jdbc().queryForObject("SELECT id FROM custom_field_definitions WHERE name=? COLLATE NOCASE", Long.class, definition.name());
                if (id == null) throw new IllegalStateException("Custom field definition was not created");
                return new CustomFieldDefinition(id, definition.name(), definition.type(), definition.enumOptions());
            }
            CustomFieldDefinition previous = findDefinition(definition.id())
                    .orElseThrow(() -> new IllegalArgumentException("Unknown custom field definition: " + definition.id()));
            int valueCount = valueCount(definition.id());
            if (valueCount > 0 && previous.type() != definition.type()) {
                throw new IllegalStateException("Cannot change custom field type while values exist");
            }
            if (valueCount > 0 && definition.type() == CustomFieldType.ENUM) {
                Integer invalid = jdbc().queryForObject("SELECT COUNT(*) FROM custom_field_values WHERE definition_id=? AND value_text NOT IN (" +
                        placeholders(definition.enumOptions().size()) + ")", Integer.class,
                        concat(definition.id(), definition.enumOptions()));
                if (invalid != null && invalid > 0) throw new IllegalStateException("Cannot remove ENUM options that are still in use");
            }
            int updated = jdbc().update("UPDATE custom_field_definitions SET name=?,field_type=?,enum_options_json=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                    definition.name(), definition.type().name(), options, definition.id());
            if (updated != 1) throw new IllegalStateException("Custom field definition was not updated: " + definition.id());
            return definition;
        } catch (DuplicateKeyException duplicate) {
            throw new IllegalArgumentException("Custom field name already exists: " + definition.name(), duplicate);
        }
    }

    @Override
    @Transactional
    public void deleteDefinition(long id, CustomFieldDeletePolicy policy) {
        CustomFieldDefinition existing = findDefinition(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown custom field definition: " + id));
        int count = valueCount(id);
        CustomFieldDeletePolicy effective = policy == null ? CustomFieldDeletePolicy.REJECT_IF_VALUES : policy;
        if (count > 0 && effective == CustomFieldDeletePolicy.REJECT_IF_VALUES) {
            throw new IllegalStateException("Custom field '" + existing.name() + "' has " + count + " values");
        }
        if (effective == CustomFieldDeletePolicy.CASCADE_VALUES) {
            jdbc().update("DELETE FROM custom_field_values WHERE definition_id=?", id);
        }
        jdbc().update("DELETE FROM custom_field_definitions WHERE id=?", id);
    }

    @Override
    public Map<Long, CustomFieldValue> findValues(BookId bookId) {
        if (bookId == null) return Map.of();
        Map<Long, CustomFieldValue> result = new LinkedHashMap<>();
        jdbc().query("""
                SELECT v.definition_id,d.field_type,v.value_text
                  FROM custom_field_values v
                  JOIN custom_field_definitions d ON d.id=v.definition_id
                 WHERE v.book_id=? ORDER BY d.name COLLATE NOCASE,d.id
                """, rs -> {
            long id = rs.getLong("definition_id");
            CustomFieldType type = CustomFieldType.valueOf(rs.getString("field_type"));
            result.put(id, new CustomFieldValue(id, type, rs.getString("value_text")));
        }, bookId.asString());
        return Map.copyOf(result);
    }

    @Override
    @Transactional
    public void setValue(BookId bookId, long definitionId, String value) {
        if (bookId == null) throw new IllegalArgumentException("bookId is required");
        CustomFieldDefinition definition = findDefinition(definitionId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown custom field definition: " + definitionId));
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) { clearValue(bookId, definitionId); return; }
        CustomFieldValue.validate(definition.type(), normalized);
        if (definition.type() == CustomFieldType.ENUM && !definition.enumOptions().contains(normalized)) {
            throw new IllegalArgumentException("Value is not an allowed ENUM option: " + normalized);
        }
        jdbc().update("""
                INSERT INTO custom_field_values(book_id,definition_id,value_text,updated_at)
                VALUES(?,?,?,CURRENT_TIMESTAMP)
                ON CONFLICT(book_id,definition_id) DO UPDATE SET value_text=excluded.value_text,updated_at=CURRENT_TIMESTAMP
                """, bookId.asString(), definitionId, canonical(definition.type(), normalized));
    }

    @Override
    public void clearValue(BookId bookId, long definitionId) {
        if (bookId == null) return;
        jdbc().update("DELETE FROM custom_field_values WHERE book_id=? AND definition_id=?", bookId.asString(), definitionId);
    }

    private int valueCount(long id) {
        Integer count = jdbc().queryForObject("SELECT COUNT(*) FROM custom_field_values WHERE definition_id=?", Integer.class, id);
        return count == null ? 0 : count;
    }

    private CustomFieldDefinition definition(long id, String name, String type, String json) {
        try {
            List<String> options = json == null || json.isBlank() ? List.of() : mapper.readValue(json, new TypeReference<List<String>>() { });
            return new CustomFieldDefinition(id, name, CustomFieldType.valueOf(type), options);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid custom field enum_options_json for id=" + id, e);
        }
    }

    private String encode(List<String> values) {
        try { return mapper.writeValueAsString(values == null ? List.of() : values); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot serialize custom field options", e); }
    }

    private static String canonical(CustomFieldType type, String value) {
        return switch (type) {
            case BOOL -> Boolean.toString(Boolean.parseBoolean(value));
            case NUMBER -> new java.math.BigDecimal(value).stripTrailingZeros().toPlainString();
            default -> value;
        };
    }

    private static String placeholders(int size) {
        if (size <= 0) return "NULL";
        return String.join(",", java.util.Collections.nCopies(size, "?"));
    }

    private static Object[] concat(long id, List<String> values) {
        Object[] out = new Object[1 + values.size()]; out[0] = id;
        for (int i=0;i<values.size();i++) out[i+1] = values.get(i);
        return out;
    }
}
