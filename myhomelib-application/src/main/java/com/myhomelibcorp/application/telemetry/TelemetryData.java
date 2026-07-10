package com.myhomelibcorp.application.telemetry;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TelemetryData {
    private String id;
    private String eventType;
    private long durationMs;
    private long memoryUsed;
    private long heapMax;
    private long heapUsed;
    private String details;
    private LocalDateTime timestamp;
}