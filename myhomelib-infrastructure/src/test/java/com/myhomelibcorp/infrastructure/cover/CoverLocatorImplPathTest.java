package com.myhomelibcorp.infrastructure.cover;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.resource.BookResourcePort;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CoverLocatorImplPathTest {

    @Test
    void emptyFolderStillResolvesAgainstCollectionRoot() {
        BookResourcePort resources = mock(BookResourcePort.class);
        Path expected = Path.of("/library/book.fb2");
        when(resources.isArchive("")).thenReturn(false);
        when(resources.buildFilePath("/library", "", "book.fb2")).thenReturn(expected);
        CoverLocatorImpl locator = new CoverLocatorImpl(mock(ZipArchiveReader.class), resources);
        BookDto book = BookDto.builder().fileName("book.fb2").folder("").collectionRoot("/library").build();

        assertThat(locator.locateCoverFile(book)).contains(expected);
        verify(resources).buildFilePath("/library", "", "book.fb2");
    }

    @Test
    void rejectedResourcePathProducesEmptyResultInsteadOfNullOptionalFailure() {
        BookResourcePort resources = mock(BookResourcePort.class);
        when(resources.isArchive("../outside")).thenReturn(false);
        when(resources.buildFilePath("/library", "../outside", "book.fb2")).thenReturn(null);
        CoverLocatorImpl locator = new CoverLocatorImpl(mock(ZipArchiveReader.class), resources);
        BookDto book = BookDto.builder().fileName("book.fb2").folder("../outside").collectionRoot("/library").build();

        assertThat(locator.locateCoverFile(book)).isEmpty();
    }
}
