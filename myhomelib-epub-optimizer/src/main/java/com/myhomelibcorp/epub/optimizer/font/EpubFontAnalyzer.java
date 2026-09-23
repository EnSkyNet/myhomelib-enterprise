package com.myhomelibcorp.epub.optimizer.font;

import com.myhomelibcorp.epub.optimizer.fs.EpubPathSafety;
import com.myhomelibcorp.epub.optimizer.graph.EpubResourceGraph;
import com.myhomelibcorp.epub.optimizer.graph.EpubResourceNode;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Component
public final class EpubFontAnalyzer {
    public List<FontResourceAnalysis> analyze(Path epub, EpubResourceGraph graph) throws Exception {
        List<FontResourceAnalysis> result = new ArrayList<>();
        try (ZipFile zip = new ZipFile(epub.toFile(), StandardCharsets.UTF_8)) {
            for (EpubResourceNode node : graph.nodes().values()) {
                if (!font(node)) continue;
                ZipEntry entry = zip.getEntry(node.path());
                if (entry == null) continue;
                int refs = (int) graph.references().stream().filter(ref -> node.path().equals(ref.toPath())).count();
                Variable variable = node.encrypted() ? Variable.unknown() : variableFont(zip, entry, node.path());
                result.add(new FontResourceAnalysis(node.path(), node.mediaType(), Math.max(0L, entry.getSize()), refs,
                        node.encrypted(), variable.value, variable.known));
            }
        }
        return List.copyOf(result);
    }

    /** Independently verifies a provider-produced font candidate. */
    public StandaloneFontAnalysis inspectCandidate(Path font, String expectedMediaType) throws Exception {
        if (font == null) return StandaloneFontAnalysis.invalid();
        final Path safeFont;
        try { safeFont = EpubPathSafety.requireSafeRegularFile(font, "EPUB font candidate"); }
        catch (Exception unsafe) { return StandaloneFontAnalysis.invalid(); }
        long size = Files.size(safeFont);
        if (size < 12L) return StandaloneFontAnalysis.invalid();
        String media = expectedMediaType == null ? "" : expectedMediaType.trim().toLowerCase(Locale.ROOT);
        byte[] header;
        try (InputStream in = Files.newInputStream(safeFont, LinkOption.NOFOLLOW_LINKS)) { header = in.readNBytes(12); }
        FontContainer container = detectContainer(header);
        if (container == FontContainer.UNKNOWN) return StandaloneFontAnalysis.invalid();
        if (!mediaCompatible(media, container)) return StandaloneFontAnalysis.invalid();
        Variable variable = container == FontContainer.SFNT ? variableFont(safeFont) : Variable.unknown();
        return new StandaloneFontAnalysis(true, size, variable.value, variable.known, container.name());
    }

    /**
     * Independently verifies Unicode coverage of an SFNT candidate using cmap format 4/12/13.
     * Unsupported containers or cmap formats are deliberately reported as incomplete.
     */
    public CodePointCoverage verifyCodePointCoverage(Path font, Set<Integer> requiredCodePoints) {
        Set<Integer> required = requiredCodePoints == null ? Set.of() : Set.copyOf(requiredCodePoints);
        if (required.isEmpty()) return new CodePointCoverage(true, true, 0, List.of());
        if (font == null) return CodePointCoverage.unverified(required.size());
        final Path safeFont;
        try { safeFont = EpubPathSafety.requireSafeRegularFile(font, "EPUB font coverage candidate"); }
        catch (Exception unsafe) { return CodePointCoverage.unverified(required.size()); }
        try (SeekableByteChannel channel = Files.newByteChannel(safeFont, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            if (channel.size() < 12L) return CodePointCoverage.unverified(required.size());
            ByteBuffer header = read(channel, 0L, 12);
            if (header == null || detectContainer(header.array()) != FontContainer.SFNT) {
                return CodePointCoverage.unverified(required.size());
            }
            header.order(ByteOrder.BIG_ENDIAN);
            header.position(4);
            int tables = Short.toUnsignedInt(header.getShort());
            if (tables <= 0 || tables > 4096) return CodePointCoverage.unverified(required.size());
            long directoryBytes = (long) tables * 16L;
            if (12L + directoryBytes > channel.size() || directoryBytes > Integer.MAX_VALUE) {
                return CodePointCoverage.unverified(required.size());
            }
            ByteBuffer records = read(channel, 12L, (int) directoryBytes);
            if (records == null) return CodePointCoverage.unverified(required.size());
            records.order(ByteOrder.BIG_ENDIAN);
            long cmapOffset = -1L;
            long cmapLength = -1L;
            for (int i = 0; i < tables; i++) {
                int o = i * 16;
                if (records.get(o) == 'c' && records.get(o + 1) == 'm'
                        && records.get(o + 2) == 'a' && records.get(o + 3) == 'p') {
                    cmapOffset = Integer.toUnsignedLong(records.getInt(o + 8));
                    cmapLength = Integer.toUnsignedLong(records.getInt(o + 12));
                    break;
                }
            }
            if (cmapOffset < 0 || cmapLength < 4L || cmapLength > 32L * 1024L * 1024L
                    || cmapOffset > channel.size() || cmapLength > channel.size() - cmapOffset
                    || cmapLength > Integer.MAX_VALUE) {
                return CodePointCoverage.unverified(required.size());
            }
            ByteBuffer cmap = read(channel, cmapOffset, (int) cmapLength);
            if (cmap == null) return CodePointCoverage.unverified(required.size());
            cmap.order(ByteOrder.BIG_ENDIAN);
            int numTables = Short.toUnsignedInt(cmap.getShort(2));
            if (numTables <= 0 || 4L + (long) numTables * 8L > cmap.capacity()) {
                return CodePointCoverage.unverified(required.size());
            }
            List<CmapSubtable> subtables = new ArrayList<>();
            for (int i = 0; i < numTables; i++) {
                int record = 4 + i * 8;
                long relative = Integer.toUnsignedLong(cmap.getInt(record + 4));
                if (relative < 0 || relative + 2L > cmap.capacity()) continue;
                int offset = (int) relative;
                int format = Short.toUnsignedInt(cmap.getShort(offset));
                if (format == 4) {
                    if (offset + 4 > cmap.capacity()) continue;
                    int length = Short.toUnsignedInt(cmap.getShort(offset + 2));
                    if (length >= 16 && (long) offset + length <= cmap.capacity()) {
                        subtables.add(new CmapSubtable(format, offset, length));
                    }
                } else if (format == 12 || format == 13) {
                    if (offset + 8 > cmap.capacity()) continue;
                    long length = Integer.toUnsignedLong(cmap.getInt(offset + 4));
                    if (length >= 16L && length <= Integer.MAX_VALUE && (long) offset + length <= cmap.capacity()) {
                        subtables.add(new CmapSubtable(format, offset, (int) length));
                    }
                }
            }
            if (subtables.isEmpty()) return CodePointCoverage.unverified(required.size());
            LinkedHashSet<Integer> missing = new LinkedHashSet<>();
            for (int cp : required) {
                if (cp < 0 || cp > Character.MAX_CODE_POINT || !mapped(cmap, subtables, cp)) missing.add(cp);
            }
            return new CodePointCoverage(true, missing.isEmpty(), missing.size(), missing.stream().limit(16).toList());
        } catch (Exception ignored) {
            return CodePointCoverage.unverified(required.size());
        }
    }

    private boolean mapped(ByteBuffer cmap, List<CmapSubtable> subtables, int codePoint) {
        for (CmapSubtable subtable : subtables) {
            if (subtable.format == 4 && codePoint <= 0xFFFF && mappedFormat4(cmap, subtable, codePoint)) return true;
            if ((subtable.format == 12 || subtable.format == 13) && mappedFormat12Or13(cmap, subtable, codePoint)) return true;
        }
        return false;
    }

    private boolean mappedFormat4(ByteBuffer cmap, CmapSubtable table, int codePoint) {
        int base = table.offset;
        if (base + 16 > cmap.capacity()) return false;
        int segCount = Short.toUnsignedInt(cmap.getShort(base + 6)) / 2;
        if (segCount <= 0 || segCount > 32768) return false;
        int endCodes = base + 14;
        int startCodes = endCodes + segCount * 2 + 2;
        int deltas = startCodes + segCount * 2;
        int rangeOffsets = deltas + segCount * 2;
        int tableEnd = base + table.length;
        if (rangeOffsets + segCount * 2 > tableEnd || tableEnd > cmap.capacity()) return false;
        for (int i = 0; i < segCount; i++) {
            int end = Short.toUnsignedInt(cmap.getShort(endCodes + i * 2));
            if (codePoint > end) continue;
            int start = Short.toUnsignedInt(cmap.getShort(startCodes + i * 2));
            if (codePoint < start) return false;
            int delta = cmap.getShort(deltas + i * 2);
            int rangeOffsetPos = rangeOffsets + i * 2;
            int rangeOffset = Short.toUnsignedInt(cmap.getShort(rangeOffsetPos));
            int glyph;
            if (rangeOffset == 0) {
                glyph = (codePoint + delta) & 0xFFFF;
            } else {
                long glyphPos = (long) rangeOffsetPos + rangeOffset + 2L * (codePoint - start);
                if (glyphPos < base || glyphPos + 2L > tableEnd) return false;
                glyph = Short.toUnsignedInt(cmap.getShort((int) glyphPos));
                if (glyph != 0) glyph = (glyph + delta) & 0xFFFF;
            }
            return glyph != 0;
        }
        return false;
    }

    private boolean mappedFormat12Or13(ByteBuffer cmap, CmapSubtable table, int codePoint) {
        int base = table.offset;
        int tableEnd = base + table.length;
        if (base + 16 > tableEnd || tableEnd > cmap.capacity()) return false;
        long groups = Integer.toUnsignedLong(cmap.getInt(base + 12));
        if (groups <= 0L || groups > 1_000_000L || 16L + groups * 12L > table.length) return false;
        int lo = 0;
        int hi = (int) groups - 1;
        long cp = Integer.toUnsignedLong(codePoint);
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int pos = base + 16 + mid * 12;
            long start = Integer.toUnsignedLong(cmap.getInt(pos));
            long end = Integer.toUnsignedLong(cmap.getInt(pos + 4));
            if (cp < start) hi = mid - 1;
            else if (cp > end) lo = mid + 1;
            else {
                long startGlyph = Integer.toUnsignedLong(cmap.getInt(pos + 8));
                long glyph = table.format == 13 ? startGlyph : startGlyph + (cp - start);
                return glyph != 0L;
            }
        }
        return false;
    }

    private ByteBuffer read(SeekableByteChannel channel, long offset, int length) throws Exception {
        if (offset < 0L || length < 0) return null;
        ByteBuffer buffer = ByteBuffer.allocate(length);
        channel.position(offset);
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer);
            if (read < 0) return null;
            if (read == 0) Thread.onSpinWait();
        }
        buffer.flip();
        return buffer;
    }

    private Variable variableFont(Path font) {
        try {
            Path safeFont = EpubPathSafety.requireSafeRegularFile(font, "EPUB variable-font analysis source");
            try (InputStream in = Files.newInputStream(safeFont, LinkOption.NOFOLLOW_LINKS)) {
            byte[] header = in.readNBytes(12);
            if (detectContainer(header) != FontContainer.SFNT) return Variable.unknown();
            ByteBuffer b = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
            b.position(4); int tables = Short.toUnsignedInt(b.getShort());
            if (tables <= 0 || tables > 4096) return Variable.unknown();
            byte[] records = in.readNBytes(tables * 16);
            if (records.length != tables * 16) return Variable.unknown();
            for (int i = 0; i < tables; i++) {
                int o = i * 16;
                if (records[o] == 'f' && records[o + 1] == 'v' && records[o + 2] == 'a' && records[o + 3] == 'r') {
                    return new Variable(true, true);
                }
            }
            return new Variable(false, true);
            }
        } catch (Exception ignored) {
            return Variable.unknown();
        }
    }

    private FontContainer detectContainer(byte[] header) {
        if (header == null || header.length < 4) return FontContainer.UNKNOWN;
        if (header[0] == 'w' && header[1] == 'O' && header[2] == 'F' && header[3] == 'F') return FontContainer.WOFF;
        if (header[0] == 'w' && header[1] == 'O' && header[2] == 'F' && header[3] == '2') return FontContainer.WOFF2;
        boolean sfnt10 = header[0] == 0 && header[1] == 1 && header[2] == 0 && header[3] == 0;
        boolean otto = header[0] == 'O' && header[1] == 'T' && header[2] == 'T' && header[3] == 'O';
        boolean trueType = header[0] == 't' && header[1] == 'r' && header[2] == 'u' && header[3] == 'e';
        return sfnt10 || otto || trueType ? FontContainer.SFNT : FontContainer.UNKNOWN;
    }

    private boolean mediaCompatible(String media, FontContainer container) {
        if (media.contains("woff2")) return container == FontContainer.WOFF2;
        if (media.contains("woff")) return container == FontContainer.WOFF;
        if (media.contains("ttf") || media.contains("truetype") || media.contains("opentype")
                || media.contains("sfnt") || media.contains("font") || media.isBlank()) {
            return container == FontContainer.SFNT;
        }
        return container == FontContainer.SFNT;
    }

    private Variable variableFont(ZipFile zip, ZipEntry entry, String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".ttf") || lower.endsWith(".otf"))) return Variable.unknown();
        try (InputStream in = zip.getInputStream(entry)) {
            byte[] header = in.readNBytes(12);
            if (header.length < 12) return Variable.unknown();
            ByteBuffer b = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
            b.position(4); int tables = Short.toUnsignedInt(b.getShort());
            if (tables <= 0 || tables > 4096) return Variable.unknown();
            byte[] records = in.readNBytes(tables * 16);
            if (records.length != tables * 16) return Variable.unknown();
            for (int i=0;i<tables;i++) if (records[i*16]=='f'&&records[i*16+1]=='v'&&records[i*16+2]=='a'&&records[i*16+3]=='r') return new Variable(true,true);
            return new Variable(false,true);
        } catch (Exception ignored) { return Variable.unknown(); }
    }

    private boolean font(EpubResourceNode node) {
        String media = node.mediaType() == null ? "" : node.mediaType().toLowerCase(Locale.ROOT);
        String path = node.path().toLowerCase(Locale.ROOT);
        return media.startsWith("font/") || media.contains("font") || path.endsWith(".ttf")
                || path.endsWith(".otf") || path.endsWith(".woff") || path.endsWith(".woff2");
    }
    public record StandaloneFontAnalysis(boolean signatureVerified, long byteSize, boolean variableFont,
                                         boolean variableFontKnown, String container) {
        static StandaloneFontAnalysis invalid() { return new StandaloneFontAnalysis(false, 0L, false, false, "UNKNOWN"); }
    }
    public record CodePointCoverage(boolean verified, boolean complete, int missingCount, List<Integer> missingSample) {
        public CodePointCoverage { missingSample = List.copyOf(missingSample == null ? List.of() : missingSample); }
        static CodePointCoverage unverified(int missingCount) { return new CodePointCoverage(false, false, Math.max(0, missingCount), List.of()); }
    }
    private record CmapSubtable(int format, int offset, int length) { }
    private enum FontContainer { SFNT, WOFF, WOFF2, UNKNOWN }
    private record Variable(boolean value, boolean known){static Variable unknown(){return new Variable(false,false);}}
}
