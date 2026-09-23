package com.myhomelibcorp.epub.optimizer.semantic;

import com.myhomelibcorp.epub.optimizer.graph.EpubResourceGraph;
import com.myhomelibcorp.epub.optimizer.graph.EpubResourceGraphAnalyzer;
import com.myhomelibcorp.epub.optimizer.graph.EpubResourceNode;
import com.myhomelibcorp.shared.xml.SecureXmlInputFactory;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Component
public class EpubSemanticSnapshotService {
    private static final int MAX_SEMANTIC_TEXT_CHARS = 8_000_000;
    private static final int MAX_METADATA_TEXT_CHARS = 1_000_000;
    private static final Set<String> SEMANTIC_METADATA_FIELDS = Set.of(
            "title", "creator", "language", "identifier", "publisher", "date", "subject", "description");

    private final EpubResourceGraphAnalyzer graphAnalyzer;

    public EpubSemanticSnapshotService(EpubResourceGraphAnalyzer graphAnalyzer) {
        this.graphAnalyzer = graphAnalyzer;
    }

    public EpubSemanticSnapshot snapshot(Path epub) throws Exception {
        EpubResourceGraph graph = graphAnalyzer.analyze(epub);
        try (ZipFile zip = new ZipFile(epub.toFile(), StandardCharsets.UTF_8)) {
            PackageSemanticData packageData = parsePackage(zip, graph.packagePath());
            Map<String, String> chapterHashes = new LinkedHashMap<>();
            for (String path : packageData.spinePaths) {
                checkCancelled();
                chapterHashes.put(path, textHash(zip, path));
            }
            String navHash = "";
            Optional<EpubResourceNode> nav = graph.nodes().values().stream().filter(EpubResourceNode::navigation).findFirst();
            if (nav.isPresent()) navHash = textHash(zip, nav.get().path());
            return new EpubSemanticSnapshot(graph.packagePath(), packageData.metadata,
                    packageData.spinePaths, chapterHashes, navHash);
        }
    }

    private PackageSemanticData parsePackage(ZipFile zip, String packagePath) throws Exception {
        Map<String, String> idToPath = new LinkedHashMap<>();
        List<String> spineIds = new ArrayList<>();
        Map<String, String> metadata = new TreeMap<>();
        String base = parent(packagePath);
        ZipEntry opf = zip.getEntry(packagePath);
        if (opf == null) throw new IllegalArgumentException("EPUB package document is missing: " + packagePath);
        try (InputStream in = zip.getInputStream(opf)) {
            XMLStreamReader reader = SecureXmlInputFactory.create(false, false).createXMLStreamReader(in);
            String currentMetadataKey = null;
            StringBuilder currentText = new StringBuilder();
            long events = 0L;
            try {
                while (reader.hasNext()) {
                    int event = reader.next();
                    if ((++events & 0x0fffL) == 0L) checkCancelled();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        String local = reader.getLocalName();
                        if ("item".equals(local)) {
                            String id = attr(reader, "id");
                            String href = attr(reader, "href");
                            if (id != null && href != null) idToPath.put(id, resolve(base, href));
                        } else if ("itemref".equals(local)) {
                            String idref = attr(reader, "idref");
                            if (idref != null) spineIds.add(idref);
                        } else if (SEMANTIC_METADATA_FIELDS.contains(local)) {
                            currentMetadataKey = local;
                            currentText.setLength(0);
                        }
                    } else if ((event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA)
                            && currentMetadataKey != null) {
                        appendBounded(currentText, reader.getText(), MAX_METADATA_TEXT_CHARS, "EPUB package metadata");
                    } else if (event == XMLStreamConstants.END_ELEMENT && currentMetadataKey != null
                            && currentMetadataKey.equals(reader.getLocalName())) {
                        String value = normalizeText(currentText.toString());
                        if (!value.isBlank()) metadata.merge(currentMetadataKey, value, (a,b) -> a + " | " + b);
                        currentMetadataKey = null;
                    }
                }
            } finally { reader.close(); }
        }
        List<String> spinePaths = spineIds.stream().map(idToPath::get).filter(Objects::nonNull).toList();
        return new PackageSemanticData(metadata, spinePaths);
    }

    private String textHash(ZipFile zip, String path) throws Exception {
        ZipEntry entry = zip.getEntry(path);
        if (entry == null) return "MISSING";
        StringBuilder text = new StringBuilder();
        try (InputStream in = zip.getInputStream(entry)) {
            XMLStreamReader reader = SecureXmlInputFactory.create(false, false).createXMLStreamReader(in);
            long events = 0L;
            try {
                while (reader.hasNext()) {
                    int event = reader.next();
                    if ((++events & 0x0fffL) == 0L) checkCancelled();
                    if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                        appendBounded(text, reader.getText(), MAX_SEMANTIC_TEXT_CHARS, "EPUB semantic text");
                        appendBounded(text, " ", MAX_SEMANTIC_TEXT_CHARS, "EPUB semantic text");
                    }
                }
            } finally { reader.close(); }
        }
        checkCancelled();
        return sha256(normalizeText(text.toString()).getBytes(StandardCharsets.UTF_8));
    }

    private static void appendBounded(StringBuilder target, String value, int maxChars, String label) {
        if (value == null || value.isEmpty()) return;
        if (target.length() > maxChars - value.length()) {
            throw new IllegalArgumentException(label + " exceeds safe comparison limit of " + maxChars + " characters");
        }
        target.append(value);
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new java.util.concurrent.CancellationException("EPUB semantic snapshot cancelled");
        }
    }

    private String normalizeText(String value) {
        if (value == null || value.isEmpty()) return "";
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFC);
        StringBuilder result = new StringBuilder(normalized.length());
        boolean pendingSpace = false;
        for (int offset = 0; offset < normalized.length();) {
            int cp = normalized.codePointAt(offset);
            offset += Character.charCount(cp);
            boolean whitespace = cp == 0x00A0 || Character.isWhitespace(cp);
            if (whitespace) {
                if (!result.isEmpty()) pendingSpace = true;
                continue;
            }
            if (pendingSpace) { result.append(' '); pendingSpace = false; }
            result.appendCodePoint(cp);
        }
        return result.toString();
    }

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private String attr(XMLStreamReader reader, String name) {
        for (int i = 0; i < reader.getAttributeCount(); i++) if (name.equals(reader.getAttributeLocalName(i))) return reader.getAttributeValue(i);
        return null;
    }

    private String parent(String path) { int slash = path.lastIndexOf('/'); return slash < 0 ? "" : path.substring(0, slash); }

    private String resolve(String base, String relative) {
        String value = relative == null ? "" : relative;
        int hash = value.indexOf('#'); if (hash >= 0) value = value.substring(0, hash);
        Deque<String> parts = new ArrayDeque<>();
        String joined = (base.isBlank() ? "" : base + "/") + value.replace('\\','/');
        for (String part : joined.split("/")) {
            if (part.isBlank() || ".".equals(part)) continue;
            if ("..".equals(part)) {
                if (!parts.isEmpty()) parts.removeLast(); else return "";
            } else parts.addLast(part);
        }
        return String.join("/", parts);
    }

    private record PackageSemanticData(Map<String, String> metadata, List<String> spinePaths) { }
}
