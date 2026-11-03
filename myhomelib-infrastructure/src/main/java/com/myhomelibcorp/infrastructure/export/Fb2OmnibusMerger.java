package com.myhomelibcorp.infrastructure.export;

import com.myhomelibcorp.application.export.SeriesOmnibusPlanner;
import com.myhomelibcorp.application.port.out.resource.BookResourcePort;
import com.myhomelibcorp.domain.model.book.Book;
import org.w3c.dom.*;
import org.xml.sax.InputSource;
import com.myhomelibcorp.shared.xml.SecureXmlInputFactory;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * Preserves native FB2 markup when merging an author's series: each original book
 * is one section, containing its own nested chapter tree. Internal anchors and
 * embedded images are namespaced per source book, so notes and covers cannot
 * accidentally resolve to a different volume. Processes one source DOM at a time.
 */
final class Fb2OmnibusMerger {
    private static final String FB = "http://www.gribuser.ru/xml/fictionbook/2.0";
    private static final String XLINK = "http://www.w3.org/1999/xlink";
    private static final long MAX_SOURCE_BYTES = 48L * 1024 * 1024;
    private static final long MAX_TOTAL_SOURCE_BYTES = 256L * 1024 * 1024;
    private static final int MAX_TOC_LINKS = 4096;
    private static final int MAX_DEPTH = 96;
    private static final int MAX_XML_ELEMENTS = 160_000;
    private static final int MAX_AUXILIARY_BODIES = 8;
    private static final int MAX_OUTPUT_ELEMENTS = 800_000;
    private static final int MAX_OUTPUT_LINKS = 150_000;
    private final BookResourcePort resources;

    Fb2OmnibusMerger(BookResourcePort resources) {
        this.resources = Objects.requireNonNull(resources, "resources");
    }

    void merge(SeriesOmnibusPlanner.SeriesPlan plan, Path target, BooleanSupplier cancelled)
            throws IOException {
        if (plan.books().isEmpty()) throw new IOException("Cannot merge an empty series");
        List<Volume> volumes = new ArrayList<>();
        long scannedBytes = 0L;
        int links = 0;
        // Preflight before writing output: all sources must be well-formed FB2 and
        // all internal references must resolve in the original source document.
        for (int i = 0; i < plan.books().size(); i++) {
            check(cancelled);
            Book book = plan.books().get(i);
            Loaded loaded = readPrepared(book, i + 1, cancelled);
            if (loaded.byteCount() > MAX_TOTAL_SOURCE_BYTES - scannedBytes) {
                throw new IOException("FB2 series exceeds source-size budget");
            }
            scannedBytes += loaded.byteCount();
            List<Chapter> chapters = new ArrayList<>();
            Element body = mainBody(loaded.document());
            for (Node node = body.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (is(node, "section")) collectChapters((Element) node, 1, chapters, cancelled);
            }
            links += chapters.size() + 1;
            if (links > MAX_TOC_LINKS) throw new IOException("Too many series TOC links");
            volumes.add(new Volume(book, i + 1, "mhl_volume_" + (i + 1),
                    book.getTitle(), List.copyOf(chapters),
                    namedBodies(loaded.document()), loaded.sha256()));
        }

        try (OutputStream raw = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            XMLOutputFactory outputFactory = XMLOutputFactory.newFactory();
            outputFactory.setProperty(XMLOutputFactory.IS_REPAIRING_NAMESPACES, true);
            XMLStreamWriter xml = outputFactory.createXMLStreamWriter(raw, StandardCharsets.UTF_8.name());
            try {
                String title = plan.series() + " — повне зібрання (" + plan.sizeLabel() + ")";
                xml.writeStartDocument("UTF-8", "1.0");
                xml.writeStartElement("FictionBook");
                xml.writeDefaultNamespace(FB);
                xml.writeNamespace("l", XLINK);
                writeDescription(xml, readUnchanged(volumes.getFirst(), cancelled).document(),
                        plan, title);
                xml.writeStartElement("body");
                title(xml, title);
                writeContents(xml, volumes, plan, cancelled);
                for (Volume volume : volumes) {
                    check(cancelled);
                    Loaded loaded = readUnchanged(volume, cancelled);
                    Element body = mainBody(loaded.document());
                    xml.writeStartElement("section");
                    xml.writeAttribute("id", volume.anchor());
                    title(xml, volume.title());
                    for (Node node = body.getFirstChild(); node != null; node = node.getNextSibling()) {
                        if (is(node, "title")) continue; // volume title already provided
                        if (node.getNodeType() == Node.ELEMENT_NODE) writeNode(xml, node, 0, cancelled);
                    }
                    xml.writeEndElement();
                }
                xml.writeEndElement(); // main body

                // Preserve every named auxiliary body, not only "notes". Otherwise
                // links to comments/appendices would resolve during preflight but
                // point at missing IDs in the newly published omnibus.
                LinkedHashSet<String> extraBodies = new LinkedHashSet<>();
                for (Volume volume : volumes) extraBodies.addAll(volume.auxiliaryBodyNames());
                if (extraBodies.size() > MAX_AUXILIARY_BODIES) {
                    throw new IOException("Too many named FB2 bodies in source series");
                }
                int bodyIndex = 0;
                for (String name : extraBodies) {
                    bodyIndex++;
                    xml.writeStartElement("body");
                    xml.writeAttribute("name", name);
                    for (Volume volume : volumes) {
                        if (!volume.auxiliaryBodyNames().contains(name)) continue;
                        check(cancelled);
                        Loaded loaded = readUnchanged(volume, cancelled);
                        xml.writeStartElement("section");
                        xml.writeAttribute("id", "mhl_aux_" + bodyIndex + "_" + volume.ordinal());
                        title(xml, ("notes".equals(name) ? "Примітки: " : name + ": ") + volume.title());
                        for (Element body : bodyElements(loaded.document())) {
                            if (!name.equalsIgnoreCase(body.getAttribute("name").trim())) continue;
                            for (Node node = body.getFirstChild(); node != null; node = node.getNextSibling()) {
                                if (node.getNodeType() != Node.ELEMENT_NODE || is(node, "title")) continue;
                                writeNode(xml, node, 0, cancelled);
                            }
                        }
                        xml.writeEndElement();
                    }
                    xml.writeEndElement();
                }

                for (Volume volume : volumes) {
                    check(cancelled);
                    Loaded loaded = readUnchanged(volume, cancelled);
                    for (Element binary : children(loaded.document().getDocumentElement(), "binary")) {
                        writeNode(xml, binary, 0, cancelled);
                    }
                }
                xml.writeEndElement();
                xml.writeEndDocument();
                xml.flush();
            } finally {
                try { xml.close(); } catch (XMLStreamException ignored) { }
            }
        } catch (XMLStreamException failure) {
            throw new IOException("Unable to write combined FB2", failure);
        }
        // Final pass is streaming rather than another DOM: verify the produced
        // file really is a single parseable FB2 and all local links resolve.
        verifyOutput(target, plan.books().size());
    }

    private static void verifyOutput(Path output, int expectedVolumes) throws IOException {
        if (Files.size(output) > MAX_TOTAL_SOURCE_BYTES * 2L) {
            throw new IOException("Merged FB2 exceeds output-size limit");
        }
        Set<String> ids = new HashSet<>();
        List<String> references = new ArrayList<>();
        int elements = 0, volumes = 0;
        boolean rootSeen = false;
        try (InputStream bytes = Files.newInputStream(output)) {
            XMLStreamReader xml = SecureXmlInputFactory.create(false, true)
                    .createXMLStreamReader(bytes, StandardCharsets.UTF_8.name());
            try {
                while (xml.hasNext()) {
                    int event = xml.next();
                    if (event == XMLStreamConstants.DTD) {
                        throw new IOException("Merged FB2 unexpectedly contains a DTD");
                    }
                    if (event != XMLStreamConstants.START_ELEMENT) continue;
                    if (++elements > MAX_OUTPUT_ELEMENTS) {
                        throw new IOException("Merged FB2 has too many XML elements");
                    }
                    if (!rootSeen) {
                        rootSeen = true;
                        if (!"FictionBook".equals(xml.getLocalName())
                                || !FB.equals(xml.getNamespaceURI())) {
                            throw new IOException("Merged FB2 has an invalid root");
                        }
                    }
                    String id = xml.getAttributeValue(null, "id");
                    if (id != null && !id.isBlank()) {
                        if (!ids.add(id)) throw new IOException("Duplicate merged FB2 anchor");
                        if (id.startsWith("mhl_volume_")) volumes++;
                    }
                    for (int i = 0; i < xml.getAttributeCount(); i++) {
                        if (!"href".equals(xml.getAttributeLocalName(i))) continue;
                        String value = xml.getAttributeValue(i);
                        if (value != null && value.startsWith("#")) {
                            if (references.size() >= MAX_OUTPUT_LINKS) {
                                throw new IOException("Merged FB2 has too many internal links");
                            }
                            references.add(value.substring(1));
                        }
                    }
                }
            } finally {
                xml.close();
            }
        } catch (XMLStreamException invalid) {
            throw new IOException("Produced FB2 is not well-formed XML", invalid);
        }
        if (!rootSeen || volumes != expectedVolumes || !ids.contains("mhl_contents")) {
            throw new IOException("Produced FB2 has missing series or TOC sections");
        }
        for (String ref : references) {
            if (!ids.contains(ref)) {
                throw new IOException("Produced FB2 has an unresolved internal link");
            }
        }
    }

    private void writeDescription(XMLStreamWriter out, Document source,
                                  SeriesOmnibusPlanner.SeriesPlan plan, String title)
            throws XMLStreamException, IOException {
        Element description = firstChild(source.getDocumentElement(), "description");
        Element info = description == null ? null : firstChild(description, "title-info");
        if (info == null) throw new IOException("FB2 is missing title-info");
        out.writeStartElement("description");
        out.writeStartElement("title-info");
        boolean seenTitle = false;
        for (Node node = info.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            if (is(node, "book-title")) {
                out.writeStartElement("book-title");
                out.writeCharacters(title);
                out.writeEndElement();
                out.writeStartElement("annotation");
                out.writeStartElement("p");
                out.writeCharacters("Об'єднано " + plan.sizeLabel()
                        + ". Зміст має переходи до кожної книги та її глав.");
                out.writeEndElement();
                out.writeEndElement();
                seenTitle = true;
            } else if (!is(node, "annotation") && !is(node, "sequence")) {
                writeNode(out, node, 0, () -> false);
            }
        }
        if (!seenTitle) throw new IOException("FB2 is missing original book-title");
        out.writeEndElement(); // title-info
        out.writeStartElement("document-info");
        out.writeStartElement("author");
        out.writeStartElement("nickname");
        out.writeCharacters("MyHomeLib");
        out.writeEndElement();
        out.writeEndElement();
        out.writeStartElement("date");
        String date = LocalDate.now().toString();
        out.writeAttribute("value", date);
        out.writeCharacters(date);
        out.writeEndElement();
        out.writeStartElement("id");
        out.writeCharacters(UUID.randomUUID().toString());
        out.writeEndElement();
        out.writeStartElement("version");
        out.writeCharacters("1.0");
        out.writeEndElement();
        out.writeEndElement(); // document-info
        out.writeEndElement(); // description
    }

    private static void writeContents(XMLStreamWriter xml, List<Volume> volumes,
                                      SeriesOmnibusPlanner.SeriesPlan plan, BooleanSupplier cancelled)
            throws XMLStreamException, IOException {
        xml.writeStartElement("section");
        xml.writeAttribute("id", "mhl_contents");
        title(xml, "Загальний зміст");
        paragraph(xml, "Зібрано: " + plan.sizeLabel()
                + ". Усі книги послідовно об'єднані в одному файлі.");
        for (Volume volume : volumes) {
            check(cancelled);
            linkParagraph(xml, volume.anchor(), volume.title());
            // Include every titled nested section, not only the first three levels.
            // Some FB2 sources place chapter titles deeper in the hierarchy.
            for (Chapter chapter : volume.chapters()) {
                String indentation = "  ".repeat(Math.min(chapter.depth(), 12));
                linkParagraph(xml, chapter.anchor(), indentation + chapter.title());
            }
        }
        xml.writeEndElement();
    }

    private static void title(XMLStreamWriter xml, String value) throws XMLStreamException {
        xml.writeStartElement("title");
        paragraph(xml, value == null ? "" : value);
        xml.writeEndElement();
    }

    private static void paragraph(XMLStreamWriter xml, String value) throws XMLStreamException {
        xml.writeStartElement("p");
        xml.writeCharacters(value);
        xml.writeEndElement();
    }

    private static void linkParagraph(XMLStreamWriter xml, String id, String name)
            throws XMLStreamException {
        xml.writeStartElement("p");
        xml.writeStartElement("a");
        xml.writeAttribute("l", XLINK, "href", "#" + id);
        xml.writeCharacters(name == null ? "" : name);
        xml.writeEndElement();
        xml.writeEndElement();
    }

    private static void collectChapters(Element section, int depth, List<Chapter> result,
                                        BooleanSupplier cancelled) throws IOException {
        check(cancelled);
        if (depth > MAX_DEPTH) throw new IOException("FB2 section hierarchy is too deep");
        Element heading = firstChild(section, "title");
        if (heading != null) {
            String label = heading.getTextContent().replaceAll("\\s+", " ").trim();
            if (!label.isBlank()) result.add(new Chapter(section.getAttribute("id"),
                    label.length() > 160 ? label.substring(0, 160) : label, depth));
        }
        for (Node child = section.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (is(child, "section")) collectChapters((Element) child, depth + 1, result, cancelled);
        }
    }

    /** Prevent changing book bytes from invalidating the TOC or cross-book links mid-export. */
    private Loaded readUnchanged(Volume volume, BooleanSupplier cancelled) throws IOException {
        Loaded next = readPrepared(volume.book(), volume.ordinal(), cancelled);
        if (!volume.sha256().equals(next.sha256())) {
            throw new IOException("Source book changed while assembling series: " + volume.book().getId());
        }
        return next;
    }

    private Loaded readPrepared(Book book, int ordinal, BooleanSupplier cancelled) throws IOException {
        check(cancelled);
        try (InputStream original = resources.readBookData(book)
                .orElseThrow(() -> new IOException("Source FB2 unavailable: " + book.getId()));
             LimitedInputStream stream = new LimitedInputStream(original, MAX_SOURCE_BYTES)) {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            Document document = f.newDocumentBuilder().parse(new InputSource(stream));
            // Include trailing bytes in the snapshot even if the parser stopped after
            // the closing root element. The bounded stream prevents unlimited reads.
            stream.transferTo(OutputStream.nullOutputStream());
            check(cancelled);
            if (document.getDocumentElement() == null
                    || !"FictionBook".equals(document.getDocumentElement().getLocalName())
                    || !FB.equals(document.getDocumentElement().getNamespaceURI())) {
                throw new IOException("The selected book is not a FictionBook document: " + book.getId());
            }
            rewriteIdentifiers(document, ordinal, cancelled);
            mainBody(document);
            return new Loaded(document, stream.bytes(), stream.sha256());
        } catch (ParserConfigurationException | SAXException failure) {
            throw new IOException("Cannot safely parse source FB2: " + book.getId(), failure);
        }
    }

    private static void rewriteIdentifiers(Document document, int ordinal, BooleanSupplier cancelled)
            throws IOException {
        String prefix = "mhl_v" + ordinal + "_";
        Map<String, String> mapped = new HashMap<>();
        List<Element> elements = new ArrayList<>();
        NodeList all = document.getElementsByTagName("*");
        if (all.getLength() > MAX_XML_ELEMENTS) {
            throw new IOException("FB2 source contains too many XML elements");
        }
        for (int i = 0; i < all.getLength(); i++) {
            check(cancelled);
            Element element = (Element) all.item(i);
            elements.add(element);
            String old = element.getAttribute("id");
            if (!old.isBlank()) {
                if (mapped.putIfAbsent(old, prefix + "id" + (i + 1)) != null) {
                    throw new IOException("Duplicate source FB2 anchor ID");
                }
            }
        }
        for (int i = 0; i < elements.size(); i++) {
            Element e = elements.get(i);
            String old = e.getAttribute("id");
            if (!old.isBlank()) e.setAttribute("id", mapped.get(old));
            else if (is(e, "section")) e.setAttribute("id", prefix + "section" + (i + 1));
            NamedNodeMap attrs = e.getAttributes();
            for (int a = 0; a < attrs.getLength(); a++) {
                Node attribute = attrs.item(a);
                if (!"href".equals(attribute.getLocalName())) continue;
                String value = attribute.getNodeValue();
                if (value != null && value.startsWith("#")) {
                    String resolved = mapped.get(value.substring(1));
                    if (resolved == null) throw new IOException("Unresolved FB2 internal link in " + ordinal);
                    attribute.setNodeValue("#" + resolved);
                }
            }
        }
    }

    private static Element mainBody(Document document) throws IOException {
        for (Element body : bodyElements(document)) {
            if (body.getAttribute("name").isBlank()) return body;
        }
        throw new IOException("Source FB2 has no main body");
    }

    private static List<String> namedBodies(Document document) throws IOException {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (Element body : bodyElements(document)) {
            String value = body.getAttribute("name").trim().toLowerCase(Locale.ROOT);
            if (value.isBlank()) continue;
            if (value.length() > 64 || !value.matches("[a-z][a-z0-9_-]*")) {
                throw new IOException("Unsafe FB2 auxiliary body name");
            }
            names.add(value);
            if (names.size() > MAX_AUXILIARY_BODIES) {
                throw new IOException("Too many named FB2 bodies");
            }
        }
        return List.copyOf(names);
    }

    private static List<Element> bodyElements(Document document) {
        return children(document.getDocumentElement(), "body");
    }

    private static List<Element> children(Element parent, String localName) {
        List<Element> result = new ArrayList<>();
        if (parent == null) return result;
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
            if (is(node, localName)) result.add((Element) node);
        return result;
    }

    private static Element firstChild(Element parent, String name) {
        for (Element child : children(parent, name)) return child;
        return null;
    }

    private static boolean is(Node node, String localName) {
        return node instanceof Element element
                && FB.equals(element.getNamespaceURI())
                && localName.equals(element.getLocalName());
    }

    private static void writeNode(XMLStreamWriter out, Node node, int depth,
                                  BooleanSupplier cancelled) throws XMLStreamException, IOException {
        check(cancelled);
        if (depth > MAX_DEPTH) throw new IOException("FB2 XML hierarchy is too deep");
        if (node instanceof Element e) {
            String namespace = e.getNamespaceURI();
            if (namespace == null || namespace.isBlank()) out.writeStartElement(e.getNodeName());
            else out.writeStartElement(e.getPrefix() == null ? "" : e.getPrefix(),
                    e.getLocalName(), namespace);
            NamedNodeMap attrs = e.getAttributes();
            for (int i = 0; i < attrs.getLength(); i++) {
                Node a = attrs.item(i);
                if (XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(a.getNamespaceURI())) continue;
                if (a.getNamespaceURI() == null) out.writeAttribute(a.getNodeName(), a.getNodeValue());
                else out.writeAttribute(a.getPrefix() == null ? "" : a.getPrefix(),
                        a.getNamespaceURI(), a.getLocalName(), a.getNodeValue());
            }
            for (Node child = e.getFirstChild(); child != null; child = child.getNextSibling())
                writeNode(out, child, depth + 1, cancelled);
            out.writeEndElement();
        } else if (node.getNodeType() == Node.TEXT_NODE) {
            out.writeCharacters(node.getNodeValue());
        } else if (node.getNodeType() == Node.CDATA_SECTION_NODE) {
            out.writeCData(node.getNodeValue());
        }
    }

    private static void check(BooleanSupplier cancelled) throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()
                || cancelled != null && cancelled.getAsBoolean())
            throw new InterruptedIOException("FB2 series merge cancelled");
    }

    private record Chapter(String anchor, String title, int depth) { }
    private record Volume(Book book, int ordinal, String anchor, String title,
                          List<Chapter> chapters, List<String> auxiliaryBodyNames, String sha256) { }
    private record Loaded(Document document, long byteCount, String sha256) { }

    private static final class LimitedInputStream extends FilterInputStream {
        private final long max;
        private long count;
        private final MessageDigest sha256;
        private LimitedInputStream(InputStream in, long max) {
            super(in);
            this.max = max;
            try { this.sha256 = MessageDigest.getInstance("SHA-256"); }
            catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("SHA-256 algorithm unavailable", impossible);
            }
        }
        @Override public int read() throws IOException {
            int value = in.read();
            if (value >= 0) {
                if (++count > max) throw new IOException("FB2 source too large");
                sha256.update((byte) value);
            }
            return value;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            if (n > 0 && (count > max - n)) throw new IOException("FB2 source too large");
            if (n > 0) {
                count += n;
                sha256.update(b, off, n);
            }
            return n;
        }
        long bytes() { return count; }
        String sha256() { return HexFormat.of().formatHex(sha256.digest()); }
    }
}
