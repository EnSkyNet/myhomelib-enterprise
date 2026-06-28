package com.myhomelibcorp.infrastructure.cover;

import javafx.scene.image.Image;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

@Component
@Slf4j
public class ImageLoader {

    private static final int DEFAULT_WIDTH = 180;
    private static final int DEFAULT_HEIGHT = 250;
    private static final int MAX_SIZE = 10 * 1024 * 1024; // 10 MB

    public Image loadFromBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_SIZE) {
            return null;
        }
        try (ByteArrayInputStream bis = new ByteArrayInputStream(bytes)) {
            return new Image(bis, DEFAULT_WIDTH, DEFAULT_HEIGHT, true, true);
        } catch (Exception e) {
            log.trace("Failed to load image from bytes", e);
            return null;
        }
    }

    public Image loadFromStream(InputStream is) {
        try {
            byte[] bytes = is.readAllBytes();
            return loadFromBytes(bytes);
        } catch (Exception e) {
            log.trace("Failed to load image from stream", e);
            return null;
        }
    }
}