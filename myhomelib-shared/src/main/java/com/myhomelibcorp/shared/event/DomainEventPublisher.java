package com.myhomelibcorp.shared.event;

/**
 * Інтерфейс для публікації доменних подій.
 * Реалізується в інфраструктурному шарі.
 */
@FunctionalInterface
public interface DomainEventPublisher {

    /**
     * Публікує доменну подію.
     */
    void publish(DomainEvent event);
}