package com.myhomelibcorp.epub.optimizer.graph;

import com.myhomelibcorp.epub.optimizer.archive.EpubZipTransformSupport;
import com.myhomelibcorp.epub.optimizer.fs.EpubPathSafety;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Conservative exact-binary deduplication with reference rewiring. */
@Component
public final class EpubExactDuplicateDeduplicator {
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
        EpubZipTransformSupport.rewrite(
                source, target, "EPUB dedup target", replacements.keySet(), "EPUB optimization cancelled",
                (zip, entry, output) -> {
                    byte[] replacement = transformed(zip, entry, graph, replacements);
                    if (replacement != null) output.writeBytes(replacement);
                });
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
            if (path.equals(graph.packagePath())) return rewriteXml(in, path, replacements, true);
            EpubResourceNode node = graph.nodes().get(path);
            String media = node == null ? "" : media(node);
            if (media.equals("text/css") || path.toLowerCase(Locale.ROOT).endsWith(".css")) {
                byte[] bytes = in.readNBytes(MAX_TEXT_REWRITE_BYTES + 1);
                checkCancelled();
                if (bytes.length > MAX_TEXT_REWRITE_BYTES) return null;
                String css = new String(bytes, StandardCharsets.UTF_8);
                return rewriteCss(css, EpubReferencePaths.parent(path), replacements).getBytes(StandardCharsets.UTF_8);
            }
            if (media.contains("xhtml") || media.equals("text/html") || media.contains("svg") || media.contains("smil")
                    || media.contains("dtbncx") || path.toLowerCase(Locale.ROOT).matches(".*\\.(xhtml|html|htm|svg|smil|ncx)$")) {
                return rewriteXml(in, path, replacements, false);
            }
        }
        return null;
    }

    private byte[] rewriteXml(
            InputStream input, String path, Map<String,String> replacements,
            boolean removeManifestItems) throws Exception {
        String base = EpubReferencePaths.parent(path);
        return EpubXmlRewriteSupport.rewrite(
                input,
                removeManifestItems
                        ? href -> replacements.containsKey(EpubReferencePaths.resolveReference(base, href))
                        : null,
                (local, value) -> rewriteAttribute(local, value, base, replacements));
    }

    private String rewriteAttribute(String local, String value, String base, Map<String,String> replacements) {
        if ("style".equals(local)) return rewriteCss(value, base, replacements);
        if ("srcset".equals(local)) return rewriteSrcset(value, base, replacements);
        if ("href".equals(local) || "src".equals(local) || "poster".equals(local) || "data".equals(local)) {
            return rewriteReference(value, base, replacements);
        }
        return value;
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
        String core=EpubReferencePaths.stripSuffix(raw.trim()); String resolved=EpubReferencePaths.resolve(base,core); String canonical=replacements.get(resolved);
        if(canonical==null)return raw;
        String suffix=raw.trim().substring(core.length());
        return EpubReferencePaths.relative(base,canonical)+suffix;
    }

    private String media(EpubResourceNode node){return node.mediaType()==null?"":node.mediaType().toLowerCase(Locale.ROOT);}

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted())
            throw new CancellationException("EPUB optimization cancelled");
    }

}
