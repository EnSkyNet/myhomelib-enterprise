package com.myhomelibcorp.application.port.out;

import com.myhomelibcorp.application.dto.BookDto;
import javafx.scene.image.Image;

public interface CoverExtractor {
    Image extractCover(BookDto book);
}