package com.myhomelibcorp.application.port.out.event;

/**
 * Порт для публікації подій.
 * Application викликає цей інтерфейс, не знаючи про конкретну реалізацію.
 */
public interface EventPublisher {

    /**
     * Публікує подію.
     * @param event будь-який об'єкт, що представляє подію
     */
    void publish(Object event);
}