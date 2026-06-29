package com.myhomelibcorp.infrastructure.image;

import com.myhomelibcorp.domain.model.cover.Cover;
import javafx.scene.image.Image;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class CoverCache {

    private final Map<String, Image> imageCache = new ConcurrentHashMap<>();
    private final Map<String, Cover> coverCache = new ConcurrentHashMap<>();

    public Image getImage(String key) {
        return imageCache.get(key);
    }

    public void putImage(String key, Image image) {
        if (image != null) {
            imageCache.put(key, image);
        }
    }

    public Cover getCover(String key) {
        return coverCache.get(key);
    }

    public void putCover(String key, Cover cover) {
        if (cover != null && !cover.isEmpty()) {
            coverCache.put(key, cover);
        }
    }

    public void clear() {
        imageCache.clear();
        coverCache.clear();
    }
}