package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.CoverExtractor;
import javafx.scene.image.Image;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class CoverExtractorService implements CoverExtractor {

    private final CoverExtractor infrastructureCoverExtractor;   // ← інжектимось через @Primary

    @Override
    public Image extractCover(BookDto book) {
        return infrastructureCoverExtractor.extractCover(book);
    }
}