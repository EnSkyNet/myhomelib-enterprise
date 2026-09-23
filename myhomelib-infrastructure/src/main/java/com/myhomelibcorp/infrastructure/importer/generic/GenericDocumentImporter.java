package com.myhomelibcorp.infrastructure.importer.generic;

import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import com.myhomelibcorp.domain.model.valueobject.BookMetadata;
import com.myhomelibcorp.domain.model.valueobject.LanguageCode;
import com.myhomelibcorp.infrastructure.importer.AbstractBookImporter;
import com.myhomelibcorp.shared.format.SupportedFormat;
import com.myhomelibcorp.shared.format.SupportedFormatRegistry;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

/** Catalogues non-FB2 formats exactly like classic MyHomeLib; they are opened by an external reader. */
@Component
@Order(10_000)
public class GenericDocumentImporter extends AbstractBookImporter {
    private static final SupportedFormatRegistry FORMATS = SupportedFormatRegistry.standard();
    @Override public boolean supports(Path file) {
        return FORMATS.detect(file).map(f -> f.importMode() == SupportedFormat.ImportMode.GENERIC).orElse(false);
    }
    @Override public String getFormatName() { return "OTHER (PDF/MP3/M4B/DJVU/MOBI/AZW/DOC/RTF/HTML/...)"; }
    @Override protected Book parseBook(Path file) throws Exception {
        String name=file.getFileName().toString(); int dot=name.lastIndexOf('.'); String title=dot>0?name.substring(0,dot):name;
        BookMetadata metadata=BookMetadata.builder().annotation("").keywords("").language(LanguageCode.of("und")).rate(0).progress(0).build();
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) throw new IOException("Document source is not a regular file: " + file);
        BookFile bf=new BookFile(name,file.getParent()!=null?file.getParent().toString():"","",attributes.size(),null);
        return createBook(title,List.of(),List.of(),"",0,metadata,bf,
                fileUpdateTime(file));
    }
}
