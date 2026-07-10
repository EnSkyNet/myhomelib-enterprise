package com.myhomelibcorp.infrastructure.event;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Component
public class EventBus {

    private final ConcurrentHashMap<Class<?>, CopyOnWriteArrayList<Consumer<?>>> subscribers = new ConcurrentHashMap<>();

    public <T> void subscribe(Class<T> eventType, Consumer<T> listener) {
        subscribers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    public <T> void unsubscribe(Class<T> eventType, Consumer<T> listener) {
        CopyOnWriteArrayList<Consumer<?>> list = subscribers.get(eventType);
        if (list != null) list.remove(listener);
    }

    public <T> void publish(T event) {
        CopyOnWriteArrayList<Consumer<?>> list = subscribers.get(event.getClass());
        if (list != null) {
            for (Consumer<?> consumer : list) {
                ((Consumer<T>) consumer).accept(event);
            }
        }
    }
}