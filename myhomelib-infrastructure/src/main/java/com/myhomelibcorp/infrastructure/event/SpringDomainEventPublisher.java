package com.myhomelibcorp.infrastructure.event;

import com.myhomelibcorp.shared.event.DomainEvent;
import com.myhomelibcorp.shared.event.DomainEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class SpringDomainEventPublisher implements DomainEventPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;

    @Override
    public void publish(DomainEvent event) {
        if (event == null) {
            return;
        }
        log.debug("Публікація доменної події: {}", event.getEventType());
        applicationEventPublisher.publishEvent(event);
    }
}