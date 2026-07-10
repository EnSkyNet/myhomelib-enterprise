package com.myhomelibcorp.application.port.out.repository;

import com.myhomelibcorp.application.telemetry.TelemetryData;

import java.util.List;

public interface TelemetryRepository {
    void save(TelemetryData data);
    List<TelemetryData> findLast(int limit);
    void clear();
}