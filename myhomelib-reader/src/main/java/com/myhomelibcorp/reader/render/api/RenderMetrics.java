package com.myhomelibcorp.reader.render.api;

/**
 * Метрики рендерингу.
 */
public record RenderMetrics(
        long framesRendered,
        long totalRenderTimeMs,
        long lastRenderTimeMs
) {
    public static RenderMetrics empty() {
        return new RenderMetrics(0, 0, 0);
    }

    public RenderMetrics increment() {
        return new RenderMetrics(framesRendered + 1, totalRenderTimeMs, lastRenderTimeMs);
    }

    public RenderMetrics withRenderTime(long timeMs) {
        return new RenderMetrics(framesRendered + 1, totalRenderTimeMs + timeMs, timeMs);
    }

    public double getAverageRenderTimeMs() {
        return framesRendered > 0 ? (double) totalRenderTimeMs / framesRendered : 0;
    }

    public double getFps() {
        return framesRendered > 0 && totalRenderTimeMs > 0 ?
                (double) framesRendered / (totalRenderTimeMs / 1000.0) : 0;
    }
}