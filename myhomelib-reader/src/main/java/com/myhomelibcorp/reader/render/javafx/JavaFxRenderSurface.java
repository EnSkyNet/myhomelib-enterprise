package com.myhomelibcorp.reader.render.javafx;

import com.myhomelibcorp.reader.render.api.RenderSurface;
import javafx.scene.canvas.Canvas;

/**
 * Реалізація RenderSurface для JavaFX Canvas.
 */
public class JavaFxRenderSurface implements RenderSurface {

    private final Canvas canvas;

    public JavaFxRenderSurface(Canvas canvas) {
        this.canvas = canvas;
    }

    @Override
    public int getWidth() {
        return (int) canvas.getWidth();
    }

    @Override
    public int getHeight() {
        return (int) canvas.getHeight();
    }

    @Override
    public double getScale() {
        return canvas.getGraphicsContext2D().getTransform().getMxx();
    }

    public Canvas getCanvas() {
        return canvas;
    }

    @Override
    public boolean isValid() {
        return canvas.getWidth() > 0 && canvas.getHeight() > 0;
    }
}