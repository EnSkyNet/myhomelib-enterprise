package com.myhomelibcorp.shared.event;

import java.time.Instant;

public interface DomainEvent {
    String getEventId();
    Instant getTimestamp();
    String getEventType();
}