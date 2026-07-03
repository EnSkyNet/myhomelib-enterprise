package com.myhomelibcorp.application.port.out.cover;

import com.myhomelibcorp.application.dto.BookDto;
import javafx.scene.image.Image;

public interface CoverReader {
    Image readCover(BookDto book);
}