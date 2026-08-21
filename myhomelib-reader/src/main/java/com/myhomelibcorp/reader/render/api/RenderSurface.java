package com.myhomelibcorp.reader.render.api;

/**
 * Абстракція поверхні для рендерингу.
 * Може бути Canvas, View, Bitmap тощо.
 */
public interface RenderSurface {

    /**
     * Ширина поверхні в пікселях.
     */
    int getWidth();

    /**
     * Висота поверхні в пікселях.
     */
    int getHeight();

    /**
     * Масштаб поверхні (для HiDPI).
     */
    default double getScale() {
        return 1.0;
    }

    /**
     * Перевіряє, чи поверхня готова до малювання.
     */
    default boolean isValid() {
        return getWidth() > 0 && getHeight() > 0;
    }
}