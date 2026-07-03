package com.myhomelibcorp.infrastructure.service;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.cover.CoverExtractor;
import com.myhomelibcorp.application.port.out.cover.CoverReader;
import com.myhomelibcorp.infrastructure.cache.CoverCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

@Service
@Primary
@RequiredArgsConstructor
@Slf4j
public class CoverService implements CoverExtractor {

    private final CoverReader coverReader;
    private final CoverCache coverCache;

    @Override
    public javafx.scene.image.Image extractCover(BookDto book) {
        if (book == null) return null;

        String cacheKey = book.getId() != null ? book.getId() : book.getTitle() + "_" + book.getFileName();
        javafx.scene.image.Image cached = coverCache.get(cacheKey);
        if (cached != null) {
            log.debug("Обкладинка з кешу: {}", cacheKey);
            return cached;
        }

        javafx.scene.image.Image image = coverReader.readCover(book);
        if (image != null) {
            coverCache.put(cacheKey, image);
        }
        return image;
    }
}