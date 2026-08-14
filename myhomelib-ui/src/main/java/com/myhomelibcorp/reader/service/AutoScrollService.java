package com.myhomelibcorp.reader.service;

import com.myhomelibcorp.reader.session.ReaderSession;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.util.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
@Slf4j
public class AutoScrollService {

    private final ConcurrentMap<String, Timeline> activeScrolls = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Double> scrollSpeeds = new ConcurrentHashMap<>();

    private static final double MIN_SPEED = 0.5;
    private static final double MAX_SPEED = 5.0;
    private static final double DEFAULT_SPEED = 2.0;

    /**
     * Запускає авто-скрол.
     */
    public void start(ReaderSession session) {
        if (session == null || session.getWebEngine() == null || !session.isActive()) {
            return;
        }

        String sessionId = session.getSessionId();

        // Зупиняємо попередній скрол для цієї сесії
        stop(session);

        double speed = scrollSpeeds.getOrDefault(sessionId, DEFAULT_SPEED);

        Timeline timeline = new Timeline(new KeyFrame(Duration.millis(50), e -> {
            if (!session.isActive() || session.getWebEngine() == null) {
                stop(session);
                return;
            }

            try {
                // ВИПРАВЛЕНО: використовуємо String.format для коректного форматування числа
                String step = String.format(Locale.US, "%.2f", speed);
                String script = """
                    (function() {
                        var step = %s;
                        var current = window.scrollY || document.documentElement.scrollTop || 0;
                        var max = document.documentElement.scrollHeight - window.innerHeight;
                        if (current < max) {
                            window.scrollTo(0, current + step);
                            return true;
                        } else {
                            return false;
                        }
                    })();
                """.formatted(step);

                Object result = session.getWebEngine().executeScript(script);
                if (Boolean.FALSE.equals(result)) {
                    log.info("Auto-scroll reached end of book");
                    stop(session);
                }
            } catch (Exception ex) {
                log.debug("Auto-scroll error: {}", ex.getMessage());
                // Не зупиняємо скрол при тимчасових помилках
            }
        }));

        timeline.setCycleCount(Timeline.INDEFINITE);
        timeline.play();

        activeScrolls.put(sessionId, timeline);
        log.info("Auto-scroll started for session: {}", sessionId);
    }

    /**
     * Зупиняє авто-скрол для конкретної сесії.
     */
    public void stop(ReaderSession session) {
        if (session == null) {
            log.debug("Auto-scroll stop called with null session, ignoring");
            return;
        }

        String sessionId = session.getSessionId();
        Timeline timeline = activeScrolls.remove(sessionId);
        if (timeline != null) {
            timeline.stop();
            log.info("Auto-scroll stopped for session: {}", sessionId);
        } else {
            log.debug("No active auto-scroll found for session: {}", sessionId);
        }
    }

    /**
     * Зупиняє авто-скрол за ID сесії.
     */
    public void stopById(String sessionId) {
        if (sessionId == null) {
            return;
        }
        Timeline timeline = activeScrolls.remove(sessionId);
        if (timeline != null) {
            timeline.stop();
            log.info("Auto-scroll stopped by ID: {}", sessionId);
        }
    }

    /**
     * Зупиняє всі авто-скроли.
     */
    public void stopAll() {
        for (String sessionId : activeScrolls.keySet()) {
            Timeline timeline = activeScrolls.remove(sessionId);
            if (timeline != null) {
                timeline.stop();
                log.info("Auto-scroll stopped for session: {}", sessionId);
            }
        }
        log.info("All auto-scrolls stopped");
    }

    /**
     * Перемикає авто-скрол (вкл/викл).
     */
    public boolean toggle(ReaderSession session) {
        if (session == null) {
            log.warn("Cannot toggle auto-scroll: session is null");
            return false;
        }

        String sessionId = session.getSessionId();
        if (activeScrolls.containsKey(sessionId)) {
            stop(session);
            return false;
        } else {
            start(session);
            return true;
        }
    }

    /**
     * Встановлює швидкість скролу.
     */
    public void setSpeed(ReaderSession session, double speed) {
        if (session == null) {
            return;
        }

        double clampedSpeed = Math.max(MIN_SPEED, Math.min(MAX_SPEED, speed));
        String sessionId = session.getSessionId();
        scrollSpeeds.put(sessionId, clampedSpeed);

        // Перезапускаємо скрол з новою швидкістю
        if (isActive(session)) {
            stop(session);
            start(session);
        }

        log.debug("Auto-scroll speed set to: {}", clampedSpeed);
    }

    /**
     * Перевіряє, чи активний авто-скрол.
     */
    public boolean isActive(ReaderSession session) {
        if (session == null) {
            return false;
        }
        return activeScrolls.containsKey(session.getSessionId());
    }

    /**
     * Отримує поточну швидкість.
     */
    public double getSpeed(ReaderSession session) {
        if (session == null) {
            return DEFAULT_SPEED;
        }
        return scrollSpeeds.getOrDefault(session.getSessionId(), DEFAULT_SPEED);
    }

    /**
     * Очищає всі дані.
     */
    public void clear() {
        stopAll();
        scrollSpeeds.clear();
        log.info("Auto-scroll service cleared");
    }
}