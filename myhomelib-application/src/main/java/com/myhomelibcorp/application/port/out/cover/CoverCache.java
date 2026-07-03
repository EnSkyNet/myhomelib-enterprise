package com.myhomelibcorp.application.port.out.cover;

import javafx.scene.image.Image;

public interface CoverCache {
    Image get(String key);
    void put(String key, Image image);
    void invalidate(String key);
    void clear();
}