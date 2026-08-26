package com.myhomelibcorp.reader.render.api;

import com.myhomelibcorp.reader.api.ReaderTheme;
import com.myhomelibcorp.reader.model.PageLayout;

/**
 * Інтерфейс рендерера Reader.
 * Абстрагує платформозалежне малювання.
 */
public interface ReaderRenderer {

    /** Рендерить сторінку з поточною темою. */
    void renderPage(PageLayout page, ReaderTheme theme);

    /** Отримує метрики рендерингу. */
    RenderMetrics getMetrics();

    /** Очищує поверхню рендерера. */
    void clear();
}
