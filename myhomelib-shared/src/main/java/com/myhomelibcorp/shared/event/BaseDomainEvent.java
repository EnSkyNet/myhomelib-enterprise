package com.myhomelibcorp.shared.event;

import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

@Getter
public abstract class BaseDomainEvent implements DomainEvent {
    private final String eventId;
    private final Instant timestamp;
    private final String eventType;

    protected BaseDomainEvent(String eventType) {
        this.eventId = UUID.randomUUID().toString();
        this.timestamp = Instant.now();
        this.eventType = eventType;
    }

    @Override
    public String getEventId() {
        return eventId;
    }

    @Override
    public Instant getTimestamp() {
        return timestamp;
    }

    @Override
    public String getEventType() {
        return eventType;
    }
}