package com.myhomelibcorp.infrastructure.event;

import com.myhomelibcorp.application.port.out.event.EventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Потокобезпечна in-process реалізація EventPublisher.
 *
 * <p>Registration зберігає оригінальний listener окремо від type-safe invoker,
 * тому {@link #unregister(Class, Consumer)} працює за identity саме того
 * listener, який передавався в {@link #register(Class, Consumer)}.</p>
 */
@Component
@Slf4j
public class SimpleEventBus implements EventPublisher {

    private final ConcurrentMap<Class<?>, CopyOnWriteArrayList<Registration<?>>> listeners =
            new ConcurrentHashMap<>();

    /** Реєструє слухача для точного типу події. */
    public <T> void register(Class<T> eventType, Consumer<T> listener) {
        if (eventType == null) throw new IllegalArgumentException("Тип події не задано");
        if (listener == null) throw new IllegalArgumentException("Listener не задано");

        listeners.computeIfAbsent(eventType, ignored -> new CopyOnWriteArrayList<>())
                .add(new Registration<>(listener, event -> listener.accept(eventType.cast(event))));
        log.debug("Зареєстровано слухача для {}", eventType.getSimpleName());
    }

    /**
     * Ідемпотентно видаляє всі реєстрації переданого listener для eventType.
     * Identity використовується навмисно: дві різні lambda з однаковою логікою
     * не повинні взаємно відписувати одна одну.
     */
    public <T> void unregister(Class<T> eventType, Consumer<T> listener) {
        if (eventType == null || listener == null) return;
        CopyOnWriteArrayList<Registration<?>> list = listeners.get(eventType);
        if (list == null) return;
        list.removeIf(registration -> registration.original() == listener);
    }

    @Override
    public void publish(Object event) {
        if (event == null) return;

        Class<?> eventType = event.getClass();
        CopyOnWriteArrayList<Registration<?>> list = listeners.get(eventType);
        if (list == null || list.isEmpty()) {
            log.trace("Немає слухачів для події: {}", eventType.getSimpleName());
            return;
        }

        log.debug("Публікація події: {}", eventType.getSimpleName());
        for (Registration<?> registration : list) {
            try {
                registration.invoker().accept(event);
            } catch (Exception e) {
                log.error("Помилка обробки події {}: {}", eventType.getSimpleName(), e.getMessage(), e);
            }
        }
    }

    int registrationCount(Class<?> eventType) {
        var list = listeners.get(eventType);
        return list == null ? 0 : list.size();
    }

    private record Registration<T>(Consumer<T> original, Consumer<Object> invoker) { }
}
