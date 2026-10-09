package com.myhomelibcorp.reader.render.javafx;

import com.myhomelibcorp.reader.render.api.ArgbRenderedPage;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

import java.util.Objects;

/** Converts validated ARGB render output into a JavaFX image on the FX side. */
public final class ArgbFxImageSupport {
    private ArgbFxImageSupport() {
    }

    public static WritableImage toWritableImage(ArgbRenderedPage rendered) {
        Objects.requireNonNull(rendered, "rendered");
        WritableImage image = new WritableImage(rendered.width(), rendered.height());
        image.getPixelWriter().setPixels(
                0, 0, rendered.width(), rendered.height(),
                PixelFormat.getIntArgbInstance(), rendered.argb(), 0, rendered.width());
        return image;
    }
}
