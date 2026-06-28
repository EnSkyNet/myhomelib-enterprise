package com.myhomelibcorp.infrastructure.cover;

import javafx.scene.image.Image;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
@RequiredArgsConstructor
@Slf4j
public class ExternalImageFinder {

    private final ImageLoader imageLoader;

    private static final String[] SIDECAR_NAMES = {
            "cover.jpg", "cover.jpeg", "cover.png", "cover.gif",
            "folder.jpg", "folder.png", "preview.jpg"
    };

    public Image findSidecar(Path filePath) {
        Path parent = filePath.getParent();
        if (parent == null) return null;

        for (String name : SIDECAR_NAMES) {
            Path sidecar = parent.resolve(name);
            if (Files.exists(sidecar)) {
                try (InputStream is = Files.newInputStream(sidecar)) {
                    Image img = imageLoader.loadFromStream(is);
                    if (img != null) {
                        log.debug("Found sidecar: {}", sidecar);
                        return img;
                    }
                } catch (Exception e) {
                    log.trace("Failed to load sidecar: {}", name, e);
                }
            }
        }
        return null;
    }
}