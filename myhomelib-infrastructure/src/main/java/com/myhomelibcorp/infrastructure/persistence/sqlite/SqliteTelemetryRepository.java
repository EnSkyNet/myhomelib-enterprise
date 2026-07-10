package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.port.out.repository.TelemetryRepository;
import com.myhomelibcorp.application.telemetry.TelemetryData;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.persistence.QueryExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
@RequiredArgsConstructor
@Slf4j
public class SqliteTelemetryRepository implements TelemetryRepository {

    private final QueryExecutor queryExecutor;

    @Override
    public void save(TelemetryData data) {
        String sql = """
            INSERT INTO telemetry (id, event_type, duration_ms, memory_used, heap_max, heap_used, details, timestamp)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;
        queryExecutor.update(sql,
                java.util.UUID.randomUUID().toString(),
                data.getEventType(),
                data.getDurationMs(),
                data.getMemoryUsed(),
                data.getHeapMax(),
                data.getHeapUsed(),
                data.getDetails(),
                data.getTimestamp() != null ? data.getTimestamp() : LocalDateTime.now()
        );
    }

    @Override
    public List<TelemetryData> findLast(int limit) {
        String sql = "SELECT * FROM telemetry ORDER BY timestamp DESC LIMIT ?";
        return queryExecutor.query(sql, telemetryRowMapper, limit);
    }

    @Override
    public void clear() {
        queryExecutor.execute("DELETE FROM telemetry");
    }

    private final RowMapper<TelemetryData> telemetryRowMapper = (rs, rowNum) ->
            TelemetryData.builder()
                    .id(rs.getString("id"))
                    .eventType(rs.getString("event_type"))
                    .durationMs(rs.getLong("duration_ms"))
                    .memoryUsed(rs.getLong("memory_used"))
                    .heapMax(rs.getLong("heap_max"))
                    .heapUsed(rs.getLong("heap_used"))
                    .details(rs.getString("details"))
                    .timestamp(LocalDateTime.parse(rs.getString("timestamp")))
                    .build();
}