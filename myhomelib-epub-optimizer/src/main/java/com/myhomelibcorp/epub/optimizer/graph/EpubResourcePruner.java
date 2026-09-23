package com.myhomelibcorp.epub.optimizer.graph;

import com.myhomelibcorp.epub.optimizer.fs.EpubPathSafety;
import com.myhomelibcorp.shared.xml.SecureXmlInputFactory;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Removes only graph-proven orphan leaf resources and their OPF manifest items. */
@Component
public final class EpubResourcePruner {
    private static final byte[] MIMETYPE = "application/epub+zip".getBytes(StandardCharsets.US_ASCII);
    private static final LocalDateTime ZIP_TIME = LocalDateTime.of(1980, 1, 1, 0, 0);

    public int prune(Path source, Path target, EpubResourceGraph graph, Set<String> requested) throws Exception {
        Objects.requireNonNull(graph, "graph");
        source = EpubPathSafety.requireSafeRegularFile(source, "EPUB prune source");
        target = EpubPathSafety.prepareTarget(target, "EPUB prune target");
        Set<String> safe = new LinkedHashSet<>(graph.safelyPrunableOrphans());
        safe.retainAll(requested == null ? Set.of() : requested);
        if (safe.isEmpty()) {
            EpubPathSafety.copyFreshFile(source, target, "EPUB prune passthrough");
            return 0;
        }
        try (ZipFile zip = new ZipFile(source.toFile(), StandardCharsets.UTF_8);
             ZipOutputStream out = new ZipOutputStream(EpubPathSafety.openFreshOutputStream(target, "EPUB prune target"), StandardCharsets.UTF_8)) {
            out.setLevel(9);
            writeMimetype(out);
            List<? extends ZipEntry> entries = Collections.list(zip.entries()).stream()
                    .filter(entry -> !entry.isDirectory())
                    .filter(entry -> !"mimetype".equals(entry.getName()))
                    .filter(entry -> !safe.contains(entry.getName()))
                    .sorted(Comparator.comparingInt((ZipEntry entry) -> "META-INF/container.xml".equals(entry.getName()) ? 0 : 1)
                            .thenComparing(ZipEntry::getName))
                    .toList();
            byte[] buffer = new byte[64 * 1024];
            for (ZipEntry entry : entries) {
                checkCancelled();
                byte[] replacement = null;
                if (entry.getName().equals(graph.packagePath())) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        replacement = rewritePackage(in, parent(graph.packagePath()), safe);
                    }
                }
                ZipEntry copy = new ZipEntry(entry.getName());
                copy.setTimeLocal(ZIP_TIME);
                copy.setExtra(null);
                copy.setMethod(ZipEntry.DEFLATED);
                out.putNextEntry(copy);
                if (replacement != null) out.write(replacement);
                else try (InputStream in = zip.getInputStream(entry)) {
                    for (int n; (n = in.read(buffer)) >= 0;) { checkCancelled(); if (n > 0) out.write(buffer, 0, n); }
                }
                out.closeEntry();
            }
        }
        return safe.size();
    }

    private byte[] rewritePackage(InputStream input, String base, Set<String> removedPaths) throws Exception {
        XMLInputFactory xml = SecureXmlInputFactory.create(false, false);
        XMLOutputFactory output = XMLOutputFactory.newFactory();
        XMLStreamReader reader = xml.createXMLStreamReader(input);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        XMLStreamWriter writer = output.createXMLStreamWriter(bytes, StandardCharsets.UTF_8.name());
        int manifestDepth = 0;
        int skipDepth = 0;
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (skipDepth > 0) {
                    if (event == XMLStreamConstants.START_ELEMENT) skipDepth++;
                    else if (event == XMLStreamConstants.END_ELEMENT) skipDepth--;
                    continue;
                }
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String local = reader.getLocalName();
                    if ("manifest".equals(local)) manifestDepth = 1;
                    else if (manifestDepth > 0) manifestDepth++;
                    if (manifestDepth > 1 && "item".equals(local)) {
                        String href = attr(reader, "href");
                        if (href != null && removedPaths.contains(resolve(base, href))) {
                            skipDepth = 1;
                            manifestDepth--;
                            continue;
                        }
                    }
                    writeStart(reader, writer);
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    writer.writeEndElement();
                    if (manifestDepth > 0) manifestDepth--;
                } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.SPACE) {
                    writer.writeCharacters(reader.getText());
                } else if (event == XMLStreamConstants.CDATA) {
                    writer.writeCData(reader.getText());
                } else if (event == XMLStreamConstants.COMMENT) {
                    writer.writeComment(reader.getText());
                } else if (event == XMLStreamConstants.PROCESSING_INSTRUCTION) {
                    writer.writeProcessingInstruction(reader.getPITarget(), Objects.toString(reader.getPIData(), ""));
                }
            }
            writer.flush();
            return bytes.toByteArray();
        } finally {
            try { writer.close(); } finally { reader.close(); }
        }
    }

    private void writeStart(XMLStreamReader reader, XMLStreamWriter writer) throws Exception {
        String namespace = Objects.toString(reader.getNamespaceURI(), "");
        String prefix = Objects.toString(reader.getPrefix(), "");
        if (namespace.isBlank()) writer.writeStartElement(reader.getLocalName());
        else writer.writeStartElement(prefix, reader.getLocalName(), namespace);
        for (int i = 0; i < reader.getNamespaceCount(); i++) {
            String nsPrefix = reader.getNamespacePrefix(i);
            String uri = Objects.toString(reader.getNamespaceURI(i), "");
            if (nsPrefix == null || nsPrefix.isBlank()) writer.writeDefaultNamespace(uri);
            else writer.writeNamespace(nsPrefix, uri);
        }
        for (int i = 0; i < reader.getAttributeCount(); i++) {
            String attrNs = Objects.toString(reader.getAttributeNamespace(i), "");
            String attrPrefix = Objects.toString(reader.getAttributePrefix(i), "");
            String local = reader.getAttributeLocalName(i);
            String value = reader.getAttributeValue(i);
            if (attrNs.isBlank()) writer.writeAttribute(local, value);
            else writer.writeAttribute(attrPrefix, attrNs, local, value);
        }
    }

    private void writeMimetype(ZipOutputStream out) throws Exception {
        CRC32 crc = new CRC32(); crc.update(MIMETYPE);
        ZipEntry entry = new ZipEntry("mimetype");
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(MIMETYPE.length);
        entry.setCompressedSize(MIMETYPE.length);
        entry.setCrc(crc.getValue());
        entry.setTime(ZIP_TIME.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
        entry.setExtra(null);
        out.putNextEntry(entry);
        out.write(MIMETYPE);
        out.closeEntry();
    }

    private String attr(XMLStreamReader reader, String name) {
        for (int i = 0; i < reader.getAttributeCount(); i++) {
            if (name.equals(reader.getAttributeLocalName(i))) return reader.getAttributeValue(i);
        }
        return null;
    }

    private String parent(String path) { int slash = path.lastIndexOf('/'); return slash < 0 ? "" : path.substring(0, slash); }

    private String resolve(String base, String relative) {
        String value = relative == null ? "" : relative;
        int hash = value.indexOf('#'); if (hash >= 0) value = value.substring(0, hash);
        int query = value.indexOf('?'); if (query >= 0) value = value.substring(0, query);
        Deque<String> parts = new ArrayDeque<>();
        String joined = (base == null || base.isBlank() ? "" : base + "/") + value.replace('\\','/');
        for (String part : joined.split("/")) {
            if (part.isBlank() || ".".equals(part)) continue;
            if ("..".equals(part)) { if (!parts.isEmpty()) parts.removeLast(); else return ""; }
            else parts.addLast(part);
        }
        return String.join("/", parts);
    }
    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted())
            throw new CancellationException("EPUB optimization cancelled");
    }

}
