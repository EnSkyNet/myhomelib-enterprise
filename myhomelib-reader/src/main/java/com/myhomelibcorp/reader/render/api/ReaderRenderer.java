package com.myhomelibcorp.reader.render.api;

import com.myhomelibcorp.reader.api.ReaderPosition;
import com.myhomelibcorp.reader.api.ReaderTheme;
import com.myhomelibcorp.reader.model.PageLayout;

/**
 * Інтерфейс рендерера Reader.
 * Абстрагує платформозалежне малювання.
 */
public interface ReaderRenderer {

    /**
     * Рендерить сторінку на поверхні.
     */
    void renderPage(PageLayout page, RenderSurface surface, ReaderTheme theme);

    /**
     * Рендерить індикатор позиції на сторінці.
     */
    default void renderPosition(PageLayout page, ReaderPosition position, RenderSurface surface) {
        // За замовчуванням нічого не робимо
    }

    /**
     * Отримує метрики рендерингу.
     */
    RenderMetrics getMetrics();

    /**
     * Очищує поверхню.
     */
    void clear();

    /**
     * Перевіряє, чи рендерер готовий до роботи.
     */
    default boolean isReady() {
        return true;
    }

    /**
     * Отримує розмір поверхні.
     */
    default RenderSurface getSurface() {
        return null;
    }
}