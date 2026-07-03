package com.myhomelibcorp.infrastructure.event;

import com.myhomelibcorp.application.port.out.event.EventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/**
 * Проста реалізація EventPublisher з підтримкою слухачів.
 * Не залежить від Spring.
 */
@Component
@Slf4j
public class SimpleEventBus implements EventPublisher {

    // Мапа: тип події -> список слухачів
    private final ConcurrentMap<Class<?>, List<Consumer<Object>>> listeners = new ConcurrentHashMap<>();

    /**
     * Реєструє слухача для певного типу подій.
     */
    public <T> void register(Class<T> eventType, Consumer<T> listener) {
        listeners.computeIfAbsent(eventType, k -> new ArrayList<>())
                .add(event -> listener.accept(eventType.cast(event)));
        log.debug("Зареєстровано слухача для {}", eventType.getSimpleName());
    }

    /**
     * Видаляє слухача.
     */
    public <T> void unregister(Class<T> eventType, Consumer<T> listener) {
        List<Consumer<Object>> list = listeners.get(eventType);
        if (list != null) {
            list.removeIf(item -> item == listener);
        }
    }

    @Override
    public void publish(Object event) {
        if (event == null) {
            return;
        }

        Class<?> eventType = event.getClass();
        List<Consumer<Object>> list = listeners.get(eventType);
        if (list == null || list.isEmpty()) {
            log.trace("Немає слухачів для події: {}", eventType.getSimpleName());
            return;
        }

        log.debug("Публікація події: {}", eventType.getSimpleName());
        for (Consumer<Object> listener : list) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                log.error("Помилка обробки події {}: {}", eventType.getSimpleName(), e.getMessage(), e);
            }
        }
    }
}