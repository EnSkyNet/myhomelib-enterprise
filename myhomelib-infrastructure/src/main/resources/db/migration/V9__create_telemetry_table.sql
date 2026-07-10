CREATE TABLE IF NOT EXISTS telemetry (
                                         id TEXT PRIMARY KEY,
                                         event_type TEXT NOT NULL,
                                         duration_ms INTEGER,
                                         memory_used INTEGER,
                                         heap_max INTEGER,
                                         heap_used INTEGER,
                                         details TEXT,
                                         timestamp TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_telemetry_timestamp ON telemetry(timestamp);