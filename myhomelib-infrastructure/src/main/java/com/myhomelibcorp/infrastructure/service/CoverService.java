package com.myhomelibcorp.infrastructure.service;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.extension.RuntimeExtensionRegistry;
import com.myhomelibcorp.application.port.out.cover.CoverCache;
import com.myhomelibcorp.application.port.out.cover.CoverExtractor;
import com.myhomelibcorp.application.port.out.cover.CoverReader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

@Service
@Primary
@Slf4j
public class CoverService implements CoverExtractor {

    private final CoverReader coverReader;
    private final CoverCache coverCache;
    private final RuntimeExtensionRegistry runtimeExtensions;

    public CoverService(CoverReader coverReader, CoverCache coverCache, RuntimeExtensionRegistry runtimeExtensions) {
        this.coverReader = coverReader;
        this.coverCache = coverCache;
        this.runtimeExtensions = runtimeExtensions;
    }

    @Override
    public byte[] extractCover(BookDto book) {
        if (book == null) {
            return null;
        }

        String cacheKey = book.getId() != null ? book.getId() : book.getTitle() + "_" + book.getFileName();
        byte[] cached = coverCache.get(cacheKey);
        if (cached != null) {
            log.trace("Обкладинка з кешу: {}", cacheKey);
            return cached;
        }

        byte[] imageData = coverReader.readCover(book);
        if (imageData == null || imageData.length == 0) {
            for (CoverExtractor extension : runtimeExtensions.coverExtractors()) {
                try {
                    imageData = extension.extractCover(book);
                    if (imageData != null && imageData.length > 0) break;
                } catch (RuntimeException failure) {
                    log.debug("Плагін обкладинки завершився помилкою: {}", failure.getMessage());
                }
            }
        }
        if (imageData != null && imageData.length > 0) coverCache.put(cacheKey, imageData);
        return imageData;
    }
}