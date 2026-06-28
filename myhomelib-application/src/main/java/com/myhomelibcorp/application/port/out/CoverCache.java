package com.myhomelibcorp.application.port.out;

import javafx.scene.image.Image;

public interface CoverCache {
    Image get(String key);
    void put(String key, Image image);
    void invalidate(String key);
    void clear();
}