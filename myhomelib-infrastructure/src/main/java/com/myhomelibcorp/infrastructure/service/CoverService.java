package com.myhomelibcorp.infrastructure.service;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.cover.CoverCache;
import com.myhomelibcorp.application.port.out.cover.CoverExtractor;
import com.myhomelibcorp.application.port.out.cover.CoverReader;
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
        if (imageData != null && imageData.length > 0) {
            coverCache.put(cacheKey, imageData);
        }
        return imageData;
    }
}