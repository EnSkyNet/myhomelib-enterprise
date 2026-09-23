package com.myhomelibcorp.epub.optimizer.font;

import com.myhomelibcorp.epub.optimizer.graph.EpubResourceGraph;
import com.myhomelibcorp.epub.optimizer.graph.EpubResourceNode;
import com.myhomelibcorp.shared.xml.SecureXmlInputFactory;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Collects the Unicode repertoire used by reachable XHTML/HTML/SVG text without executing scripts.
 * Any source of dynamic/generated text that cannot be proven statically marks the result incomplete.
 */
@Component
public final class EpubRequiredCodePointCollector {
    private static final long MAX_TEXT_RESOURCE = 32L * 1024L * 1024L;
    private static final Pattern GENERATED_CONTENT = Pattern.compile("(?is)(^|[;{])\\s*content\\s*:\\s*(?!normal(?:\\s*[;}])|none(?:\\s*[;}])|[\\\"']{2})");

    public RequiredCodePointCollection collect(Path epub, EpubResourceGraph graph) throws Exception {
        Set<Integer> codePoints = new LinkedHashSet<>();
        List<String> warnings = new ArrayList<>();
        boolean complete = graph.warnings().isEmpty();
        codePoints.add((int) ' ');
        codePoints.add(0x00A0); // NBSP is common in publication text even when normalized by XML parsers.

        Set<String> reachable = graph.reachable();
        try (ZipFile zip = new ZipFile(epub.toFile(), StandardCharsets.UTF_8)) {
            for (EpubResourceNode node : graph.nodes().values()) {
                checkCancelled();
                if (!reachable.contains(node.path())) continue;
                String media = node.mediaType() == null ? "" : node.mediaType().toLowerCase(Locale.ROOT);
                String lower = node.path().toLowerCase(Locale.ROOT);
                if (node.encrypted()) {
                    if (textual(media, lower)) {
                        complete = false;
                        warnings.add("Encrypted reachable text prevents complete font coverage analysis: " + node.path());
                    }
                    continue;
                }
                if (node.properties().contains("scripted") || media.contains("javascript") || media.contains("ecmascript")) {
                    complete = false;
                    warnings.add("Dynamic text may be produced by scripting: " + node.path());
                    continue;
                }
                ZipEntry entry = zip.getEntry(node.path());
                if (entry == null || entry.getSize() < 0 || entry.getSize() > MAX_TEXT_RESOURCE) {
                    if (textual(media, lower)) {
                        complete = false;
                        warnings.add("Text resource cannot be safely scanned for font coverage: " + node.path());
                    }
                    continue;
                }
                if (markup(media, lower)) {
                    try (InputStream input = zip.getInputStream(entry)) {
                        collectMarkup(input, codePoints);
                    } catch (Exception malformed) {
                        complete = false;
                        warnings.add("Cannot parse text for font coverage in " + node.path() + ": " + malformed.getClass().getSimpleName());
                    }
                } else if (css(media, lower)) {
                    try (InputStream input = zip.getInputStream(entry)) {
                        byte[] bytes = input.readNBytes((int) MAX_TEXT_RESOURCE + 1);
                        if (bytes.length > MAX_TEXT_RESOURCE) {
                            complete = false;
                            warnings.add("CSS resource exceeds safe font-coverage scan limit: " + node.path());
                            continue;
                        }
                        String value = new String(bytes, StandardCharsets.UTF_8);
                        if (GENERATED_CONTENT.matcher(value).find()) {
                            complete = false;
                            warnings.add("CSS generated content prevents provably complete font subsetting: " + node.path());
                        }
                    } catch (Exception malformed) {
                        complete = false;
                        warnings.add("Cannot inspect CSS generated content in " + node.path() + ": " + malformed.getClass().getSimpleName());
                    }
                }
            }
        }
        return new RequiredCodePointCollection(codePoints, complete, warnings);
    }

    private void collectMarkup(InputStream input, Set<Integer> out) throws Exception {
        XMLStreamReader reader = SecureXmlInputFactory.create(false, false).createXMLStreamReader(input);
        try {
            while (reader.hasNext()) {
                checkCancelled();
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    // Attribute text (alt/title/aria-label and similar fallback text) may become visible.
                    // Over-including it is safe for subsetting and preferable to missing a glyph.
                    for (int i = 0; i < reader.getAttributeCount(); i++) addText(reader.getAttributeValue(i), out);
                    continue;
                }
                if (event != XMLStreamConstants.CHARACTERS && event != XMLStreamConstants.CDATA) continue;
                addText(reader.getText(), out);
            }
        } finally {
            reader.close();
        }
    }

    private void addText(String value, Set<Integer> out) {
        if (value == null || value.isEmpty()) return;
        value.codePoints().filter(EpubRequiredCodePointCollector::glyphCodePoint).forEach(out::add);
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException("EPUB font coverage analysis cancelled");
    }

    private static boolean glyphCodePoint(int cp) {
        if (cp == '\t' || cp == '\n' || cp == '\r') return false;
        return !Character.isISOControl(cp) && Character.isDefined(cp);
    }

    private static boolean markup(String media, String path) {
        return media.contains("xhtml") || media.equals("text/html") || media.contains("svg")
                || path.endsWith(".xhtml") || path.endsWith(".html") || path.endsWith(".htm") || path.endsWith(".svg");
    }

    private static boolean css(String media, String path) { return media.equals("text/css") || path.endsWith(".css"); }
    private static boolean textual(String media, String path) { return markup(media, path) || css(media, path); }
}
