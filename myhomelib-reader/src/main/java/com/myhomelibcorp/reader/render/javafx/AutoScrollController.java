package com.myhomelibcorp.reader.render.javafx;

import javafx.animation.AnimationTimer;
import javafx.scene.canvas.Canvas;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class AutoScrollController {

    private final Canvas canvas;
    private AnimationTimer scrollTimer;
    private boolean isRunning = false;
    private double scrollSpeed = 1.0;

    public AutoScrollController(Canvas canvas) {
        this.canvas = canvas;
    }

    public void start() {
        if (isRunning) {
            return;
        }
        isRunning = true;

        scrollTimer = new AnimationTimer() {
            private long lastUpdate = 0;

            @Override
            public void handle(long now) {
                if (now - lastUpdate < 16_000_000) {
                    return;
                }
                lastUpdate = now;

                // TODO: реалізувати скрол
                // double scrollY = canvas.getTranslateY() + scrollSpeed;
                // canvas.setTranslateY(scrollY);
            }
        };
        scrollTimer.start();
        log.info("▶️ Автопрокрутку запущено, швидкість: {}", scrollSpeed);
    }

    public void stop() {
        if (!isRunning || scrollTimer == null) {
            return;
        }
        scrollTimer.stop();
        scrollTimer = null;
        isRunning = false;
        log.info("⏹️ Автопрокрутку зупинено");
    }

    public void toggle() {
        if (isRunning) {
            stop();
        } else {
            start();
        }
    }

    public boolean isRunning() {
        return isRunning;
    }

    public void setSpeed(double speed) {
        this.scrollSpeed = Math.max(0.1, Math.min(5.0, speed));
        log.info("⚡ Швидкість автопрокрутки: {}", this.scrollSpeed);
    }

    public double getSpeed() {
        return scrollSpeed;
    }
}