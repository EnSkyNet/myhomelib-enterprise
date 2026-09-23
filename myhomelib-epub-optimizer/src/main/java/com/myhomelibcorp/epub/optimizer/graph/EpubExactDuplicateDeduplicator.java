package com.myhomelibcorp.epub.optimizer.graph;

import com.myhomelibcorp.epub.optimizer.fs.EpubPathSafety;
import com.myhomelibcorp.shared.xml.SecureXmlInputFactory;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Conservative exact-binary deduplication with reference rewiring. */
@Component
public final class EpubExactDuplicateDeduplicator {
    private static final byte[] MIMETYPE = "application/epub+zip".getBytes(StandardCharsets.US_ASCII);
    private static final LocalDateTime ZIP_TIME = LocalDateTime.of(1980, 1, 1, 0, 0);
    private static final int MAX_TEXT_REWRITE_BYTES = 16 * 1024 * 1024;
    private static final Pattern CSS_URL = Pattern.compile("url\\(\\s*(['\"]?)([^)'\"]+)\\1\\s*\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CSS_IMPORT = Pattern.compile("@import\\s+(?:url\\()?\\s*(['\"]?)([^)'\";]+)\\1", Pattern.CASE_INSENSITIVE);
    private static final Pattern SAFE_INTERNAL_PATH = Pattern.compile("[A-Za-z0-9._~!$&'()*+,;=:@/\\-]+");

    public int deduplicate(Path source, Path target, EpubResourceGraph graph) throws Exception {
        source = EpubPathSafety.requireSafeRegularFile(source, "EPUB dedupe source");
        target = EpubPathSafety.prepareTarget(target, "EPUB dedupe target");
        Map<String,String> replacements = replacements(graph);
        if (replacements.isEmpty()) {
            EpubPathSafety.copyFreshFile(source, target, "EPUB dedup passthrough");
            return 0;
        }
        try (ZipFile zip = new ZipFile(source.toFile(), StandardCharsets.UTF_8);
             ZipOutputStream out = new ZipOutputStream(EpubPathSafety.openFreshOutputStream(target, "EPUB dedup target"), StandardCharsets.UTF_8)) {
            out.setLevel(9);
            writeMimetype(out);
            List<? extends ZipEntry> entries = Collections.list(zip.entries()).stream()
                    .filter(entry -> !entry.isDirectory())
                    .filter(entry -> !"mimetype".equals(entry.getName()))
                    .filter(entry -> !replacements.containsKey(entry.getName()))
                    .sorted(Comparator.comparingInt((ZipEntry entry) -> "META-INF/container.xml".equals(entry.getName()) ? 0 : 1)
                            .thenComparing(ZipEntry::getName))
                    .toList();
            byte[] buffer = new byte[64 * 1024];
            for (ZipEntry entry : entries) {
                checkCancelled();
                byte[] replacement = transformed(zip, entry, graph, replacements);
                ZipEntry copy = new ZipEntry(entry.getName());
                copy.setTimeLocal(ZIP_TIME); copy.setExtra(null); copy.setMethod(ZipEntry.DEFLATED);
                out.putNextEntry(copy);
                if (replacement != null) out.write(replacement);
                else try (InputStream in = zip.getInputStream(entry)) {
                    for (int n; (n = in.read(buffer)) >= 0;) { checkCancelled(); if (n > 0) out.write(buffer, 0, n); }
                }
                out.closeEntry();
            }
        }
        return replacements.size();
    }

    private Map<String,String> replacements(EpubResourceGraph graph) {
        if (!graph.warnings().isEmpty()) return Map.of();
        boolean scripted = graph.nodes().values().stream().anyMatch(node -> {
            String media = media(node);
            return node.properties().contains("scripted") || media.contains("javascript") || media.contains("ecmascript");
        });
        if (scripted) return Map.of();
        Map<String,String> result = new LinkedHashMap<>();
        for (List<String> group : graph.exactDuplicateGroups().values()) {
            List<EpubResourceNode> nodes = group.stream().map(graph.nodes()::get).filter(Objects::nonNull).toList();
            if (nodes.size() < 2 || nodes.stream().anyMatch(node -> !eligible(node, graph))) continue;
            String media = media(nodes.getFirst());
            if (nodes.stream().anyMatch(node -> !media.equals(media(node)))) continue;
            List<String> paths = nodes.stream().map(EpubResourceNode::path).sorted().toList();
            String canonical = paths.getFirst();
            for (String duplicate : paths.subList(1, paths.size())) {
                if (hasUnsafeReference(graph, duplicate)) continue;
                result.put(duplicate, canonical);
            }
        }
        return Map.copyOf(result);
    }

    private boolean eligible(EpubResourceNode node, EpubResourceGraph graph) {
        if (!node.manifestItem() || node.encrypted() || node.navigation() || node.spineItem()) return false;
        if (!node.properties().isEmpty() || graph.roots().contains(node.path())) return false;
        if (!SAFE_INTERNAL_PATH.matcher(node.path()).matches()) return false;
        String media = media(node);
        return media.startsWith("image/") || media.startsWith("font/") || media.startsWith("audio/") || media.startsWith("video/");
    }

    private boolean hasUnsafeReference(EpubResourceGraph graph, String duplicate) {
        return graph.references().stream().filter(ref -> duplicate.equals(ref.toPath())).anyMatch(ref -> switch (ref.type()) {
            case XHTML_HREF, XHTML_SRC, SVG_REFERENCE, CSS_URL, CSS_IMPORT, FONT_FACE, MANIFEST -> false;
            default -> true;
        });
    }

    private byte[] transformed(ZipFile zip, ZipEntry entry, EpubResourceGraph graph, Map<String,String> replacements) throws Exception {
        String path = entry.getName();
        try (InputStream in = zip.getInputStream(entry)) {
            if (path.equals(graph.packagePath())) return rewriteXml(in, path, graph.packagePath(), replacements, true);
            EpubResourceNode node = graph.nodes().get(path);
            String media = node == null ? "" : media(node);
            if (media.equals("text/css") || path.toLowerCase(Locale.ROOT).endsWith(".css")) {
                byte[] bytes = in.readNBytes(MAX_TEXT_REWRITE_BYTES + 1);
                checkCancelled();
                if (bytes.length > MAX_TEXT_REWRITE_BYTES) return null;
                String css = new String(bytes, StandardCharsets.UTF_8);
                return rewriteCss(css, parent(path), replacements).getBytes(StandardCharsets.UTF_8);
            }
            if (media.contains("xhtml") || media.equals("text/html") || media.contains("svg") || media.contains("smil")
                    || media.contains("dtbncx") || path.toLowerCase(Locale.ROOT).matches(".*\\.(xhtml|html|htm|svg|smil|ncx)$")) {
                return rewriteXml(in, path, graph.packagePath(), replacements, false);
            }
        }
        return null;
    }

    private byte[] rewriteXml(InputStream input, String path, String packagePath, Map<String,String> replacements,
                              boolean removeManifestItems) throws Exception {
        XMLInputFactory xml = SecureXmlInputFactory.create(false, false);
        XMLOutputFactory output = XMLOutputFactory.newFactory();
        XMLStreamReader reader = xml.createXMLStreamReader(input);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        XMLStreamWriter writer = output.createXMLStreamWriter(bytes, StandardCharsets.UTF_8.name());
        String base = parent(path);
        int manifestDepth = 0, skipDepth = 0;
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
                    if (removeManifestItems) {
                        if ("manifest".equals(local)) manifestDepth = 1;
                        else if (manifestDepth > 0) manifestDepth++;
                        if (manifestDepth > 1 && "item".equals(local)) {
                            String href = attr(reader,"href");
                            if (href != null && replacements.containsKey(EpubResourceGraphAnalyzer.resolve(base, stripSuffix(href)))) {
                                skipDepth = 1; manifestDepth--; continue;
                            }
                        }
                    }
                    writeStart(reader, writer, base, replacements);
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    writer.writeEndElement();
                    if (removeManifestItems && manifestDepth > 0) manifestDepth--;
                } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.SPACE) writer.writeCharacters(reader.getText());
                else if (event == XMLStreamConstants.CDATA) writer.writeCData(reader.getText());
                else if (event == XMLStreamConstants.COMMENT) writer.writeComment(reader.getText());
                else if (event == XMLStreamConstants.PROCESSING_INSTRUCTION) writer.writeProcessingInstruction(reader.getPITarget(), Objects.toString(reader.getPIData(),""));
            }
            writer.flush(); return bytes.toByteArray();
        } finally { try { writer.close(); } finally { reader.close(); } }
    }

    private void writeStart(XMLStreamReader reader, XMLStreamWriter writer, String base, Map<String,String> replacements) throws Exception {
        String namespace=Objects.toString(reader.getNamespaceURI(),""); String prefix=Objects.toString(reader.getPrefix(),"");
        if(namespace.isBlank())writer.writeStartElement(reader.getLocalName()); else writer.writeStartElement(prefix,reader.getLocalName(),namespace);
        for(int i=0;i<reader.getNamespaceCount();i++){String p=reader.getNamespacePrefix(i),u=Objects.toString(reader.getNamespaceURI(i),"");if(p==null||p.isBlank())writer.writeDefaultNamespace(u);else writer.writeNamespace(p,u);}
        for(int i=0;i<reader.getAttributeCount();i++){
            String local=reader.getAttributeLocalName(i), value=reader.getAttributeValue(i), rewritten=value;
            if("style".equals(local)) rewritten=rewriteCss(value,base,replacements);
            else if("srcset".equals(local)) rewritten=rewriteSrcset(value,base,replacements);
            else if("href".equals(local)||"src".equals(local)||"poster".equals(local)||"data".equals(local)) rewritten=rewriteReference(value,base,replacements);
            String ns=Objects.toString(reader.getAttributeNamespace(i),""),p=Objects.toString(reader.getAttributePrefix(i),"");
            if(ns.isBlank())writer.writeAttribute(local,rewritten);else writer.writeAttribute(p,ns,local,rewritten);
        }
    }

    private String rewriteCss(String css, String base, Map<String,String> replacements) {
        String value = rewriteCssPattern(css, base, replacements, CSS_URL, 2);
        return rewriteCssPattern(value, base, replacements, CSS_IMPORT, 2);
    }

    private String rewriteCssPattern(String css, String base, Map<String,String> replacements, Pattern pattern, int group) {
        Matcher matcher=pattern.matcher(css); StringBuffer out=new StringBuffer();
        while(matcher.find()){
            String raw=matcher.group(group), rewritten=rewriteReference(raw,base,replacements);
            if(raw.equals(rewritten)) continue;
            String whole=matcher.group();
            String changed=whole.replace(raw,rewritten);
            matcher.appendReplacement(out,Matcher.quoteReplacement(changed));
        }
        matcher.appendTail(out); return out.toString();
    }

    private String rewriteSrcset(String value, String base, Map<String,String> replacements) {
        List<String> result=new ArrayList<>();
        for(String candidate:value.split(",")){
            String trimmed=candidate.trim(); if(trimmed.isBlank()){result.add(trimmed);continue;}
            String[]parts=trimmed.split("\\s+",2); String rewritten=rewriteReference(parts[0],base,replacements);
            result.add(parts.length==1?rewritten:rewritten+" "+parts[1]);
        }
        return String.join(", ",result);
    }

    private String rewriteReference(String raw, String base, Map<String,String> replacements) {
        if(raw==null||raw.isBlank())return raw;
        String lower=raw.trim().toLowerCase(Locale.ROOT);
        if(lower.startsWith("http:")||lower.startsWith("https:")||lower.startsWith("data:")||lower.startsWith("mailto:")||lower.startsWith("javascript:")||raw.trim().startsWith("#"))return raw;
        String core=stripSuffix(raw.trim()); String resolved=EpubResourceGraphAnalyzer.resolve(base,core); String canonical=replacements.get(resolved);
        if(canonical==null)return raw;
        String suffix=raw.trim().substring(core.length());
        return relative(base,canonical)+suffix;
    }

    private String stripSuffix(String raw) { int h=raw.indexOf('#'),q=raw.indexOf('?');int cut=h<0?q:q<0?h:Math.min(h,q);return cut<0?raw:raw.substring(0,cut); }

    private String relative(String base, String target) {
        List<String>b=parts(base),t=parts(target);int common=0;while(common<b.size()&&common<t.size()&&b.get(common).equals(t.get(common)))common++;
        List<String>out=new ArrayList<>();for(int i=common;i<b.size();i++)out.add("..");out.addAll(t.subList(common,t.size()));return String.join("/",out);
    }
    private List<String> parts(String path){if(path==null||path.isBlank())return List.of();return Arrays.stream(path.split("/")).filter(v->!v.isBlank()).toList();}
    private String media(EpubResourceNode node){return node.mediaType()==null?"":node.mediaType().toLowerCase(Locale.ROOT);}
    private String parent(String path){int slash=path.lastIndexOf('/');return slash<0?"":path.substring(0,slash);}
    private String attr(XMLStreamReader reader,String name){for(int i=0;i<reader.getAttributeCount();i++)if(name.equals(reader.getAttributeLocalName(i)))return reader.getAttributeValue(i);return null;}

    private void writeMimetype(ZipOutputStream out)throws Exception{CRC32 crc=new CRC32();crc.update(MIMETYPE);ZipEntry e=new ZipEntry("mimetype");e.setMethod(ZipEntry.STORED);e.setSize(MIMETYPE.length);e.setCompressedSize(MIMETYPE.length);e.setCrc(crc.getValue());e.setTime(ZIP_TIME.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());e.setExtra(null);out.putNextEntry(e);out.write(MIMETYPE);out.closeEntry();}
    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted())
            throw new CancellationException("EPUB optimization cancelled");
    }

}
