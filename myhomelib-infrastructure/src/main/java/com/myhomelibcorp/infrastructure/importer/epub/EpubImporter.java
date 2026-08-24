package com.myhomelibcorp.infrastructure.importer.epub;

import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import com.myhomelibcorp.domain.model.valueobject.BookMetadata;
import com.myhomelibcorp.domain.model.valueobject.LanguageCode;
import com.myhomelibcorp.infrastructure.importer.AbstractBookImporter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Component
@Slf4j
public class EpubImporter extends AbstractBookImporter {
    private final XMLInputFactory xmlFactory = createXmlFactory();

    @Override
    public boolean supports(Path file) {
        return file != null && file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".epub");
    }

    @Override public String getFormatName() { return "EPUB"; }

    @Override
    protected Book parseBook(Path file) throws Exception {
        String title = stripExtension(file.getFileName().toString());
        List<Author> authors = new ArrayList<>();
        List<Genre> genres = new ArrayList<>();
        String language = "uk";
        String series = "";
        int sequence = 0;

        try (ZipFile zip = new ZipFile(file.toFile())) {
            String opfPath = locatePackageDocument(zip);
            if (opfPath != null) {
                ZipEntry opf = zip.getEntry(opfPath);
                if (opf != null) {
                    try (InputStream in = zip.getInputStream(opf)) {
                        Metadata metadata = parseMetadata(in);
                        if (metadata.title != null && !metadata.title.isBlank()) title = metadata.title.trim();
                        for (String creator : metadata.creators) {
                            Author author = toAuthor(creator);
                            if (author != null) authors.add(author);
                        }
                        int gi = 0;
                        for (String subject : metadata.subjects) {
                            if (subject != null && !subject.isBlank()) {
                                String code = "epub-" + Integer.toUnsignedString(subject.toLowerCase(Locale.ROOT).hashCode(), 36) + "-" + gi++;
                                genres.add(new Genre(code, subject.trim()));
                            }
                        }
                        if (metadata.language != null && !metadata.language.isBlank()) language = normalizeLanguage(metadata.language);
                        if (metadata.series != null) series = metadata.series;
                        sequence = metadata.seriesIndex;
                    }
                }
            }
        }

        if (authors.isEmpty()) authors.add(new Author("", "", "Невідомий автор"));
        BookMetadata metadata = BookMetadata.builder()
                .annotation("")
                .keywords("")
                .language(LanguageCode.of(language))
                .rate(0)
                .progress(0)
                .build();
        BookFile bookFile = new BookFile(
                file.getFileName().toString(),
                file.getParent() != null ? file.getParent().toString() : "",
                "",
                Files.size(file),
                null
        );
        return createBook(title, authors, genres, series, sequence, metadata, bookFile, LocalDateTime.now());
    }

    private String locatePackageDocument(ZipFile zip) {
        ZipEntry container = zip.getEntry("META-INF/container.xml");
        if (container == null) return null;
        try (InputStream in = zip.getInputStream(container)) {
            XMLStreamReader reader = xmlFactory.createXMLStreamReader(in);
            try {
                while (reader.hasNext()) {
                    if (reader.next() == XMLStreamConstants.START_ELEMENT && "rootfile".equals(reader.getLocalName())) {
                        String value = reader.getAttributeValue(null, "full-path");
                        if (value != null && !value.isBlank()) return value;
                    }
                }
            } finally {
                reader.close();
            }
        } catch (Exception e) {
            log.debug("Не вдалося прочитати container.xml: {}", e.getMessage());
        }
        return null;
    }

    private Metadata parseMetadata(InputStream in) throws Exception {
        Metadata result = new Metadata();
        XMLStreamReader reader = xmlFactory.createXMLStreamReader(in);
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event != XMLStreamConstants.START_ELEMENT) continue;
                String local = reader.getLocalName();
                String ns = reader.getNamespaceURI();
                if ("title".equals(local) && isDc(ns)) result.title = safeElementText(reader);
                else if ("creator".equals(local) && isDc(ns)) result.creators.add(safeElementText(reader));
                else if ("language".equals(local) && isDc(ns)) result.language = safeElementText(reader);
                else if ("subject".equals(local) && isDc(ns)) result.subjects.add(safeElementText(reader));
                else if ("meta".equals(local)) {
                    String name = attr(reader, "name");
                    String property = attr(reader, "property");
                    String content = attr(reader, "content");
                    if ("calibre:series".equalsIgnoreCase(name) && content != null) result.series = content.trim();
                    if ("calibre:series_index".equalsIgnoreCase(name) && content != null) result.seriesIndex = parseSeriesIndex(content);
                    if (property != null && property.endsWith("belongs-to-collection")) {
                        String text = safeElementText(reader);
                        if (result.series == null || result.series.isBlank()) result.series = text;
                    }
                }
            }
        } finally {
            reader.close();
        }
        return result;
    }

    private String safeElementText(XMLStreamReader reader) {
        try { return reader.getElementText(); } catch (Exception e) { return ""; }
    }

    private String attr(XMLStreamReader reader, String name) {
        String v = reader.getAttributeValue(null, name);
        if (v != null) return v;
        for (int i = 0; i < reader.getAttributeCount(); i++) {
            if (name.equals(reader.getAttributeLocalName(i))) return reader.getAttributeValue(i);
        }
        return null;
    }

    private boolean isDc(String ns) {
        return ns == null || ns.isBlank() || ns.contains("purl.org/dc/elements");
    }

    private Author toAuthor(String value) {
        if (value == null || value.isBlank()) return null;
        String name = value.trim();
        if (name.contains(",")) {
            String[] p = name.split(",", 2);
            return new Author(p.length > 1 ? p[1].trim() : "", "", p[0].trim());
        }
        String[] p = name.split("\\s+");
        if (p.length == 1) return new Author("", "", p[0]);
        String first = p[0];
        String last = p[p.length - 1];
        String middle = p.length > 2 ? String.join(" ", java.util.Arrays.copyOfRange(p, 1, p.length - 1)) : "";
        return new Author(first, middle, last);
    }

    private String normalizeLanguage(String value) {
        String lang = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        int dash = lang.indexOf('-');
        if (dash > 0) lang = lang.substring(0, dash);
        try { return LanguageCode.of(lang).value(); }
        catch (Exception e) { return "uk"; }
    }

    private int parseSeriesIndex(String v) {
        try { return (int) Math.round(Double.parseDouble(v.trim())); }
        catch (Exception e) { return 0; }
    }

    private String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static XMLInputFactory createXmlFactory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        try { factory.setProperty(XMLInputFactory.SUPPORT_DTD, false); } catch (Exception ignored) { }
        try { factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false); } catch (Exception ignored) { }
        try { factory.setProperty(XMLInputFactory.IS_COALESCING, false); } catch (Exception ignored) { }
        return factory;
    }

    private static final class Metadata {
        String title;
        String language;
        String series;
        int seriesIndex;
        final List<String> creators = new ArrayList<>();
        final List<String> subjects = new ArrayList<>();
    }
}
