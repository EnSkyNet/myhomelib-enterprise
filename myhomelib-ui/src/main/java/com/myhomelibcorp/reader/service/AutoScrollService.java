package com.myhomelibcorp.reader.service;

import com.myhomelibcorp.reader.session.ReaderSession;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.util.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
@Slf4j
public class AutoScrollService {

    private Timeline timeline;
    private String currentSessionId;
    private double currentSpeed = DEFAULT_SPEED;

    // ВИДАЛЕНО: private final ConcurrentMap<String, Timeline> activeScrolls = new ConcurrentHashMap<>();
    // ВИДАЛЕНО: private final ConcurrentMap<String, Double> scrollSpeeds = new ConcurrentHashMap<>();

    private static final double MIN_SPEED = 0.5;
    private static final double MAX_SPEED = 5.0;
    private static final double DEFAULT_SPEED = 2.0;

    public void start(ReaderSession session) {
        if (session == null || session.getWebEngine() == null || !session.isActive()) {
            return;
        }

        String sessionId = session.getSessionId();
        stop(session);

        double speed = currentSpeed;

        // Плавний автоскрол з Timeline
        timeline = new Timeline(new KeyFrame(Duration.millis(16), e -> { // ~60 FPS
            if (!session.isActive() || session.getWebEngine() == null) {
                stop(session);
                return;
            }

            try {
                double step = speed;
                String script = String.format(Locale.US, """
                    (function() {
                        var step = %.3f;
                        var current = window.scrollY || document.documentElement.scrollTop || 0;
                        var max = document.documentElement.scrollHeight - window.innerHeight;
                        
                        if (current < max) {
                            var remaining = max - current;
                            var newStep = Math.min(step, remaining);
                            
                            // Плавне прискорення на початку
                            if (remaining > step * 10) {
                                newStep = step * (1 + 0.1 * Math.sin(Date.now() / 1000));
                            }
                            
                            window.scrollTo(0, current + newStep);
                            return true;
                        } else {
                            return false;
                        }
                    })();
                """, step);

                Object result = session.getWebEngine().executeScript(script);
                if (Boolean.FALSE.equals(result)) {
                    log.info("Auto-scroll reached end of book");
                    stop(session);
                }
            } catch (Exception ex) {
                if (ex.getMessage() != null && !ex.getMessage().contains("Unexpected")) {
                    log.debug("Auto-scroll error: {}", ex.getMessage());
                }
                if (ex instanceof netscape.javascript.JSException) {
                    log.warn("JS error in auto-scroll, stopping");
                    stop(session);
                }
            }
        }));

        timeline.setCycleCount(Timeline.INDEFINITE);
        timeline.play();

        currentSessionId = sessionId;
        log.info("Auto-scroll started for session: {} with speed: {}", sessionId, currentSpeed);
    }

    public void stop(ReaderSession session) {
        if (session == null) {
            return;
        }

        String sessionId = session.getSessionId();
        if (timeline != null && sessionId.equals(currentSessionId)) {
            timeline.stop();
            timeline = null;
            currentSessionId = null;
            log.info("Auto-scroll stopped for session: {}", sessionId);
        }
    }

    public boolean toggle(ReaderSession session) {
        if (session == null) {
            log.warn("Cannot toggle auto-scroll: session is null");
            return false;
        }

        String sessionId = session.getSessionId();
        if (timeline != null && sessionId.equals(currentSessionId)) {
            stop(session);
            return false;
        } else {
            start(session);
            return true;
        }
    }

    public void setSpeed(ReaderSession session, double speed) {
        if (session == null) {
            return;
        }

        double clampedSpeed = Math.max(MIN_SPEED, Math.min(MAX_SPEED, speed));
        currentSpeed = clampedSpeed;

        // Якщо автоскрол активний, оновлюємо швидкість
        if (timeline != null && session.getSessionId().equals(currentSessionId)) {
            log.debug("Auto-scroll speed updated to: {}", clampedSpeed);
        }

        log.debug("Auto-scroll speed set to: {}", clampedSpeed);
    }

    public boolean isActive(ReaderSession session) {
        if (session == null) {
            return false;
        }
        return timeline != null && session.getSessionId().equals(currentSessionId);
    }

    public double getSpeed(ReaderSession session) {
        return currentSpeed;
    }

    public void clear() {
        if (timeline != null) {
            timeline.stop();
            timeline = null;
        }
        currentSessionId = null;
        log.info("Auto-scroll service cleared");
    }
}