package com.myhomelibcorp.infrastructure.importer.generic;

import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import com.myhomelibcorp.domain.model.valueobject.BookMetadata;
import com.myhomelibcorp.domain.model.valueobject.LanguageCode;
import com.myhomelibcorp.infrastructure.importer.AbstractBookImporter;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Catalogues non-FB2 formats exactly like classic MyHomeLib; they are opened by an external reader. */
@Component
@Order(10_000)
public class GenericDocumentImporter extends AbstractBookImporter {
    private static final Set<String> EXT = Set.of(
            "pdf","djvu","djv","mobi","azw","azw3","odt","doc","docx","rtf","html","htm","xhtml","md","chm","cbz","cbr");
    @Override public boolean supports(Path file) { return file != null && EXT.contains(ext(file)); }
    @Override public String getFormatName() { return "OTHER (PDF/DJVU/MOBI/AZW/DOC/RTF/HTML/...)"; }
    @Override protected Book parseBook(Path file) throws Exception {
        String name=file.getFileName().toString(); int dot=name.lastIndexOf('.'); String title=dot>0?name.substring(0,dot):name;
        BookMetadata metadata=BookMetadata.builder().annotation("").keywords("").language(LanguageCode.of("und")).rate(0).progress(0).build();
        BookFile bf=new BookFile(name,file.getParent()!=null?file.getParent().toString():"","",Files.size(file),null);
        return createBook(title,List.of(new Author("","","Невідомий автор")),List.of(),"",0,metadata,bf,
                LocalDateTime.ofInstant(Files.getLastModifiedTime(file).toInstant(),java.time.ZoneId.systemDefault()));
    }
    private static String ext(Path p){String n=p.getFileName().toString().toLowerCase(Locale.ROOT);int i=n.lastIndexOf('.');return i<0?"":n.substring(i+1);}
}
