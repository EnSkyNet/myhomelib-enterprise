package com.myhomelibcorp.reader.render.javafx;

import javafx.animation.AnimationTimer;
import lombok.extern.slf4j.Slf4j;

/**
 * Легка автопрокрутка без постійного зсуву Canvas. Для e-ink/desktop режиму
 * переходить на наступну сторінку через інтервал, що залежить від швидкості.
 */
@Slf4j
public class AutoScrollController {

    private final Runnable nextPageAction;
    private AnimationTimer scrollTimer;
    private boolean running;
    private double speed = 1.0;

    public AutoScrollController(Runnable nextPageAction) {
        this.nextPageAction = nextPageAction;
    }

    public void start() {
        if (running || nextPageAction == null) {
            return;
        }
        running = true;
        scrollTimer = new AnimationTimer() {
            private long lastPageTime;

            @Override
            public void handle(long now) {
                if (lastPageTime == 0) {
                    lastPageTime = now;
                    return;
                }
                long interval = intervalNanos();
                if (now - lastPageTime >= interval) {
                    lastPageTime = now;
                    nextPageAction.run();
                }
            }
        };
        scrollTimer.start();
        log.info("▶️ Автопрокрутка: speed={}", speed);
    }

    public void stop() {
        if (scrollTimer != null) {
            scrollTimer.stop();
            scrollTimer = null;
        }
        running = false;
    }

    public void toggle() {
        if (running) {
            stop();
        } else {
            start();
        }
    }

    public boolean isRunning() {
        return running;
    }

    public void setSpeed(double speed) {
        this.speed = Math.max(0.1, Math.min(5.0, speed));
    }

    public double getSpeed() {
        return speed;
    }

    private long intervalNanos() {
        // speed=1 -> ~8 сек/сторінку; speed=5 -> ~1.6 сек.
        return (long) ((8.0 / speed) * 1_000_000_000L);
    }
}
