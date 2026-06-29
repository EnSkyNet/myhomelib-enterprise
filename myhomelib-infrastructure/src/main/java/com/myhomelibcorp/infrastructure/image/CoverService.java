package com.myhomelibcorp.infrastructure.image;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.CoverExtractor;
import com.myhomelibcorp.domain.model.cover.Cover;
import javafx.scene.image.Image;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

@Service
@Primary
@RequiredArgsConstructor
@Slf4j
public class CoverService implements CoverExtractor {

    private final ArchiveCoverReader archiveCoverReader;
    private final CoverCache coverCache;

    @Override
    public Image extractCover(BookDto book) {
        if (book == null) return null;

        String cacheKey = generateCacheKey(book);
        Image cached = coverCache.getImage(cacheKey);
        if (cached != null) {
            log.debug("Обкладинка з кешу: {}", cacheKey);
            return cached;
        }

        Image image = archiveCoverReader.extractImage(book);
        if (image != null) {
            coverCache.putImage(cacheKey, image);
        }
        return image;
    }

    private String generateCacheKey(BookDto book) {
        return book.getId() != null ? book.getId() : book.getTitle() + "_" + book.getFileName();
    }
}