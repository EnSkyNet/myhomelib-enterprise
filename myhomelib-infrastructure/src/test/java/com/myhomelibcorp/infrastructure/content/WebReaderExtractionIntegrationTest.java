package com.myhomelibcorp.infrastructure.content;

import com.myhomelibcorp.application.content.ContentExtractionService;
import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.repository.ReadingProgressRepository;
import com.myhomelibcorp.application.usecase.book.LoadBookByIdUseCase;
import com.myhomelibcorp.application.usecase.book.ResolveBookContentUseCase;
import com.myhomelibcorp.application.usecase.book.ResolvedBookContent;
import com.myhomelibcorp.application.webreader.WebReaderDocument;
import com.myhomelibcorp.application.webreader.WebReaderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebReaderExtractionIntegrationTest {
    private static final String BOOK_ID = "22222222-2222-2222-2222-222222222222";
    @TempDir Path temp;

    @Test void realFb2ExtractorFeedsWebReaderChapters() throws Exception {
        Path file = temp.resolve("book.fb2");
        Files.writeString(file, """
                <?xml version="1.0" encoding="UTF-8"?>
                <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0"><body>
                  <section><title><p>Перший</p></title><p>Alpha</p></section>
                  <section><title><p>Другий</p></title><p>Beta</p></section>
                </body></FictionBook>
                """);
        WebReaderDocument doc = service(file, "book.fb2").open(BOOK_ID, 0).orElseThrow();
        assertThat(doc.supported()).isTrue();
        assertThat(doc.format()).isEqualTo("fb2");
        assertThat(doc.chapters()).hasSize(2);
        assertThat(doc.chapters().get(0).text()).contains("Alpha");
        assertThat(doc.chapters().get(1).text()).contains("Beta");
    }

    @Test void realEpubExtractorFollowsSpineAndFeedsWebReaderToc() throws Exception {
        Path file = temp.resolve("book.epub");
        Files.write(file, epub());
        WebReaderDocument doc = service(file, "book.epub").open(BOOK_ID, 1).orElseThrow();
        assertThat(doc.supported()).isTrue();
        assertThat(doc.format()).isEqualTo("epub");
        assertThat(doc.chapters()).extracting(c -> c.title()).containsExactly("One", "Two");
        assertThat(doc.currentChapter().text()).contains("Beta");
    }

    private WebReaderService service(Path file, String fileName) throws Exception {
        LoadBookByIdUseCase load = mock(LoadBookByIdUseCase.class);
        ResolveBookContentUseCase resolve = mock(ResolveBookContentUseCase.class);
        ReadingProgressRepository progress = mock(ReadingProgressRepository.class);
        when(load.execute(any())).thenReturn(Optional.of(BookDto.builder().id(BOOK_ID).title("Book")
                .fileName(fileName).folder("").collectionRoot(temp.toString()).local(true).build()));
        when(resolve.execute(any(), anySet())).thenReturn(new ResolvedBookContent(file, false));
        when(progress.findByBookId(BOOK_ID)).thenReturn(Optional.empty());
        return new WebReaderService(load, resolve,
                new ContentExtractionService(List.of(new Fb2ContentExtractor(), new EpubContentExtractor())), progress);
    }

    private static byte[] epub() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            put(zip, "META-INF/container.xml", """
                    <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                    </rootfiles></container>""");
            put(zip, "OEBPS/content.opf", """
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0"><manifest>
                    <item id="a" href="one.xhtml" media-type="application/xhtml+xml"/>
                    <item id="b" href="two.xhtml" media-type="application/xhtml+xml"/>
                    </manifest><spine><itemref idref="a"/><itemref idref="b"/></spine></package>""");
            put(zip, "OEBPS/one.xhtml", "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><h1>One</h1><p>Alpha</p></body></html>");
            put(zip, "OEBPS/two.xhtml", "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><h1>Two</h1><p>Beta</p></body></html>");
        }
        return bytes.toByteArray();
    }

    private static void put(ZipOutputStream zip, String name, String text) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
