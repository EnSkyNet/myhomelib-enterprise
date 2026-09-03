package com.myhomelibcorp.infrastructure.importer.epub;

import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import com.myhomelibcorp.domain.model.valueobject.BookMetadata;
import com.myhomelibcorp.domain.service.LanguageResolver;
import com.myhomelibcorp.infrastructure.importer.AbstractBookImporter;
import com.myhomelibcorp.infrastructure.util.LimitedInputStream;
import com.myhomelibcorp.shared.archive.ArchiveSafetyLimits;
import com.myhomelibcorp.shared.xml.SecureXmlInputFactory;
import com.myhomelibcorp.infrastructure.parser.author.LocalAuthorNameParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Component
@Slf4j
public class EpubImporter extends AbstractBookImporter {
    private static final int MAX_METADATA_TEXT_CHARS = 64 * 1024;
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
        String language = "und";
        String series = "";
        int sequence = 0;

        try (ZipFile zip = new ZipFile(file.toFile())) {
            validateArchive(zip);
            String opfPath = locatePackageDocument(zip);
            if (opfPath == null || opfPath.isBlank()) {
                throw new IOException("EPUB META-INF/container.xml does not declare a package document");
            }
            ZipEntry opf = findZip(zip, opfPath);
            if (opf == null) throw new IOException("EPUB package document not found: " + opfPath);
            checkEntry(opf, "EPUB package document");

            try (InputStream raw = zip.getInputStream(opf);
                 InputStream in = new LimitedInputStream(raw, ArchiveSafetyLimits.MAX_ENTRY_BYTES)) {
                Metadata metadata = parseMetadata(in);
                if (metadata.title != null && !metadata.title.isBlank()) title = metadata.title.trim();
                for (String creator : metadata.creators) {
                    authors.addAll(LocalAuthorNameParser.parseCreators(creator));
                }
                int gi = 0;
                for (String subject : metadata.subjects) {
                    if (subject != null && !subject.isBlank()) {
                        String code = "epub-" + Integer.toUnsignedString(subject.toLowerCase(Locale.ROOT).hashCode(), 36) + "-" + gi++;
                        genres.add(new Genre(code, subject.trim()));
                    }
                }
                if (metadata.language != null && !metadata.language.isBlank()) language = LanguageResolver.resolveValue(metadata.language);
                if (metadata.series != null) series = metadata.series;
                sequence = metadata.seriesIndex;
            }
        }

        if (authors.isEmpty()) authors.add(new Author("", "", "Невідомий автор"));
        BookMetadata metadata = BookMetadata.builder()
                .annotation("")
                .keywords("")
                .language(LanguageResolver.resolve(language))
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

    private String locatePackageDocument(ZipFile zip) throws Exception {
        ZipEntry container = findZip(zip, "META-INF/container.xml");
        if (container == null) throw new IOException("EPUB META-INF/container.xml not found");
        checkEntry(container, "EPUB container.xml");
        try (InputStream raw = zip.getInputStream(container);
             InputStream in = new LimitedInputStream(raw, ArchiveSafetyLimits.MAX_ENTRY_BYTES)) {
            XMLStreamReader reader = xmlFactory.createXMLStreamReader(in);
            String packagePath = null;
            try {
                while (reader.hasNext()) {
                    if (reader.next() == XMLStreamConstants.START_ELEMENT && "rootfile".equalsIgnoreCase(reader.getLocalName())) {
                        String value = attr(reader, "full-path");
                        if (packagePath == null && value != null && !value.isBlank()) {
                            packagePath = normalizeZipPath(value);
                        }
                    }
                }
            } finally {
                reader.close();
            }
            return packagePath;
        }
    }

    private Metadata parseMetadata(InputStream in) throws Exception {
        Metadata result = new Metadata();
        XMLStreamReader reader = xmlFactory.createXMLStreamReader(in);
        boolean inMetadata = false;
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.END_ELEMENT && "metadata".equalsIgnoreCase(reader.getLocalName())) {
                    inMetadata = false;
                    continue;
                }
                if (event != XMLStreamConstants.START_ELEMENT) continue;
                String local = reader.getLocalName();
                if ("metadata".equalsIgnoreCase(local)) {
                    inMetadata = true;
                    continue;
                }
                if (!inMetadata) continue;

                String ns = reader.getNamespaceURI();
                if ("title".equals(local) && isDc(ns)) result.title = readElementText(reader);
                else if ("creator".equals(local) && isDc(ns)) result.creators.add(readElementText(reader));
                else if ("language".equals(local) && isDc(ns)) result.language = readElementText(reader);
                else if ("subject".equals(local) && isDc(ns)) result.subjects.add(readElementText(reader));
                else if ("meta".equals(local)) {
                    String name = attr(reader, "name");
                    String property = attr(reader, "property");
                    String content = attr(reader, "content");
                    if ("calibre:series".equalsIgnoreCase(name) && content != null) result.series = content.trim();
                    if ("calibre:series_index".equalsIgnoreCase(name) && content != null) result.seriesIndex = parseSeriesIndex(content);
                    if (property != null && property.endsWith("belongs-to-collection")) {
                        String text = readElementText(reader);
                        if (result.series == null || result.series.isBlank()) result.series = text;
                    }
                }
            }
        } finally {
            reader.close();
        }
        return result;
    }

    /**
     * Reads text from the current metadata element, including nested markup, while always
     * advancing the StAX reader to the matching END_ELEMENT. EPUB metadata found in the wild
     * is not always limited to the text-only shape required by getElementText(); swallowing that
     * exception used to leave the parser at an ambiguous position and silently drop metadata.
     */
    private String readElementText(XMLStreamReader reader) throws Exception {
        StringBuilder text = new StringBuilder();
        int depth = 1;
        while (depth > 0 && reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                depth++;
                continue;
            }
            if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
                continue;
            }
            if ((event == XMLStreamConstants.CHARACTERS
                    || event == XMLStreamConstants.CDATA
                    || event == XMLStreamConstants.SPACE
                    || event == XMLStreamConstants.ENTITY_REFERENCE)
                    && text.length() < MAX_METADATA_TEXT_CHARS) {
                String chunk = reader.getText();
                if (chunk != null && !chunk.isEmpty()) {
                    int remaining = MAX_METADATA_TEXT_CHARS - text.length();
                    text.append(chunk, 0, Math.min(chunk.length(), remaining));
                }
            }
        }
        return text.toString();
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

    private int parseSeriesIndex(String v) {
        try { return (int) Math.round(Double.parseDouble(v.trim())); }
        catch (NumberFormatException invalidIndex) { return 0; }
    }

    private String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private void validateArchive(ZipFile zip) throws IOException {
        int entries = 0;
        Enumeration<? extends ZipEntry> iterator = zip.entries();
        while (iterator.hasMoreElements()) {
            ZipEntry entry = iterator.nextElement();
            if (++entries > ArchiveSafetyLimits.MAX_ENTRY_COUNT) {
                throw new IOException("EPUB contains too many ZIP entries");
            }
            checkEntry(entry, "EPUB entry");
        }
    }

    private void checkEntry(ZipEntry entry, String role) throws IOException {
        if (entry == null || entry.isDirectory()) return;
        long size = entry.getSize();
        if (ArchiveSafetyLimits.declaredEntryTooLarge(size)) {
            throw new IOException(role + " exceeds archive safety limit: " + entry.getName());
        }
        long compressed = entry.getCompressedSize();
        if (compressed > 0 && size > 0
                && size / Math.max(1, compressed) > ArchiveSafetyLimits.MAX_COMPRESSION_RATIO) {
            throw new IOException(role + " has suspicious compression ratio: " + entry.getName());
        }
    }

    private ZipEntry findZip(ZipFile zip, String wanted) {
        if (wanted == null || wanted.isBlank()) return null;
        String normalized = normalizeZipPath(wanted);
        ZipEntry direct = zip.getEntry(normalized);
        if (direct != null) return direct;
        Enumeration<? extends ZipEntry> iterator = zip.entries();
        while (iterator.hasMoreElements()) {
            ZipEntry entry = iterator.nextElement();
            if (normalizeZipPath(entry.getName()).equalsIgnoreCase(normalized)) return entry;
        }
        return null;
    }

    private String normalizeZipPath(String value) {
        String normalized = value == null ? "" : value.trim().replace('\\', '/');
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        return normalized;
    }

    private static XMLInputFactory createXmlFactory() {
        return SecureXmlInputFactory.create(false, false);
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
