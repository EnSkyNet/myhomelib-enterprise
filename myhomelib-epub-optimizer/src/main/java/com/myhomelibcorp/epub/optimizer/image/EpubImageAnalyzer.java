package com.myhomelibcorp.epub.optimizer.image;

import com.myhomelibcorp.epub.optimizer.fs.EpubPathSafety;
import com.myhomelibcorp.epub.optimizer.graph.EpubResourceGraph;
import com.myhomelibcorp.epub.optimizer.graph.EpubResourceNode;
import com.myhomelibcorp.epub.optimizer.profile.ImageCodec;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Header-only image analysis: dimensions/alpha metadata are read without decoding raster pixels. */
@Component
public final class EpubImageAnalyzer {
    private static final long MAX_HEADER_SCAN = 8L * 1024L * 1024L;
    /** Security ceiling for raster decode used only by optimization verification, not by normal EPUB reading. */
    public static final long MAX_OPTIMIZATION_DECODE_PIXELS = 20_000_000L;
    /** Security ceiling for a single raster resource passed to an in-process ImageIO codec. */
    public static final long MAX_OPTIMIZATION_INPUT_BYTES = 128L * 1024L * 1024L;

    public List<ImageResourceAnalysis> analyze(Path epub, EpubResourceGraph graph) throws Exception {
        List<ImageResourceAnalysis> result = new ArrayList<>();
        try (ZipFile zip = new ZipFile(epub.toFile(), StandardCharsets.UTF_8)) {
            for (EpubResourceNode node : graph.nodes().values()) {
                ImageCodec codec = codec(node);
                if (codec == null || codec == ImageCodec.SVG) continue;
                ZipEntry entry = zip.getEntry(node.path());
                if (entry == null || entry.getSize() < 0) continue;
                Header header;
                try (InputStream input = zip.getInputStream(entry)) {
                    try {
                        header = readHeader(codec, input, Math.min(entry.getSize(), MAX_HEADER_SCAN));
                    } catch (EOFException | IllegalArgumentException malformedHeader) {
                        // A malformed/short image must not abort analysis of the whole EPUB.
                        // Unknown properties keep later image transformations conservative.
                        header = Header.unknown();
                    }
                }
                int refs = (int) graph.references().stream().filter(ref -> node.path().equals(ref.toPath())).count();
                ImageRole role = role(node, header, refs);
                result.add(new ImageResourceAnalysis(node.path(), codec, header.width, header.height, header.dimensionsKnown,
                        header.alpha, header.alphaKnown, header.animated, header.animationKnown, header.bitDepth,
                        Math.max(0L, entry.getSize()), header.icc, header.exif, header.orientation, role, refs));
            }
        }
        return List.copyOf(result);
    }

    /**
     * Independent decoded-raster equality check for v1 lossless providers.
     * Animated formats are deliberately unsupported here; a future verifier must compare every frame.
     */
    public boolean decodedPixelsEqual(Path source, Path candidate, ImageCodec codec) throws Exception {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(codec, "codec");
        if (codec != ImageCodec.PNG && codec != ImageCodec.JPEG) return false;
        final Path safeSource;
        final Path safeCandidate;
        try {
            safeSource = EpubPathSafety.requireSafeRegularFile(source, "EPUB image comparison source");
            safeCandidate = EpubPathSafety.requireSafeRegularFile(candidate, "EPUB image comparison candidate");
        } catch (Exception unsafe) {
            return false;
        }
        if (Files.size(safeSource) > MAX_OPTIMIZATION_INPUT_BYTES || Files.size(safeCandidate) > MAX_OPTIMIZATION_INPUT_BYTES) return false;
        checkCancelled();
        BufferedImage left = ImageIO.read(safeSource.toFile());
        checkCancelled();
        BufferedImage right = ImageIO.read(safeCandidate.toFile());
        checkCancelled();
        if (left == null || right == null || left.getWidth() != right.getWidth() || left.getHeight() != right.getHeight()) return false;
        long pixels = (long) left.getWidth() * (long) left.getHeight();
        if (pixels <= 0L || pixels > MAX_OPTIMIZATION_DECODE_PIXELS) return false;
        int width = left.getWidth();
        int[] leftRow = new int[width];
        int[] rightRow = new int[width];
        for (int y = 0; y < left.getHeight(); y++) {
            if ((y & 0x3f) == 0) checkCancelled();
            left.getRGB(0, y, width, 1, leftRow, 0, width);
            right.getRGB(0, y, width, 1, rightRow, 0, width);
            if (!Arrays.equals(leftRow, rightRow)) return false;
        }
        return true;
    }

    /** Independently inspects a provider-produced standalone image; provider metadata is never trusted as proof. */
    public StandaloneImageAnalysis inspectCandidate(Path image, ImageCodec expectedCodec) throws Exception {
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(expectedCodec, "expectedCodec");
        final Path safeImage;
        try { safeImage = EpubPathSafety.requireSafeRegularFile(image, "EPUB image candidate"); }
        catch (Exception unsafe) { return StandaloneImageAnalysis.invalid(expectedCodec); }
        long size = Files.size(safeImage);
        if (size <= 0L || size > MAX_OPTIMIZATION_INPUT_BYTES) return StandaloneImageAnalysis.invalid(expectedCodec);
        checkCancelled();
        boolean signatureVerified;
        Header header;
        try (InputStream input = Files.newInputStream(safeImage, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            signatureVerified = signatureMatches(expectedCodec, input);
        }
        if (!signatureVerified) return StandaloneImageAnalysis.invalid(expectedCodec);
        try (InputStream input = Files.newInputStream(safeImage, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            try { header = readHeader(expectedCodec, input, Math.min(size, MAX_HEADER_SCAN)); }
            catch (EOFException | IllegalArgumentException malformed) { header = Header.unknown(); }
        }
        return new StandaloneImageAnalysis(expectedCodec, true, header.width, header.height, header.dimensionsKnown,
                header.alpha, header.alphaKnown, header.animated, header.animationKnown, header.bitDepth,
                header.icc, header.exif, header.orientation, size);
    }

    private boolean signatureMatches(ImageCodec codec, InputStream input) throws Exception {
        byte[] head = input.readNBytes(32);
        return switch (codec) {
            case PNG -> head.length >= 8 && (head[0] & 0xff) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G'
                    && (head[4] & 0xff) == 0x0d && (head[5] & 0xff) == 0x0a && (head[6] & 0xff) == 0x1a && (head[7] & 0xff) == 0x0a;
            case JPEG -> head.length >= 3 && (head[0] & 0xff) == 0xff && (head[1] & 0xff) == 0xd8 && (head[2] & 0xff) == 0xff;
            case GIF -> head.length >= 6 && ((new String(head, 0, 6, StandardCharsets.US_ASCII)).equals("GIF87a")
                    || (new String(head, 0, 6, StandardCharsets.US_ASCII)).equals("GIF89a"));
            case WEBP -> head.length >= 12 && ascii(head, 0, "RIFF") && ascii(head, 8, "WEBP");
            case AVIF -> avifSignature(head);
            case JPEG_XL -> jpegXlSignature(head);
            case SVG -> false; // SVG is markup and is never handled by the binary image provider path.
        };
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException("EPUB image verification cancelled");
    }

    private boolean avifSignature(byte[] head) {
        if (head.length < 16 || !ascii(head, 4, "ftyp")) return false;
        for (int offset = 8; offset + 4 <= head.length; offset += 4) {
            if (ascii(head, offset, "avif") || ascii(head, offset, "avis")) return true;
        }
        return false;
    }

    private boolean jpegXlSignature(byte[] head) {
        if (head.length >= 2 && (head[0] & 0xff) == 0xff && (head[1] & 0xff) == 0x0a) return true;
        byte[] container = {0,0,0,12,'J','X','L',' ',13,10,(byte)0x87,10};
        if (head.length < container.length) return false;
        for (int i = 0; i < container.length; i++) if (head[i] != container[i]) return false;
        return true;
    }

    private boolean ascii(byte[] bytes, int offset, String value) {
        if (offset < 0 || offset + value.length() > bytes.length) return false;
        for (int i = 0; i < value.length(); i++) if ((byte) value.charAt(i) != bytes[offset + i]) return false;
        return true;
    }

    private Header readHeader(ImageCodec codec, InputStream input, long scanLimit) throws Exception {
        return switch (codec) {
            case PNG -> png(input, scanLimit);
            case JPEG -> jpeg(input, scanLimit);
            case GIF -> gif(input, scanLimit);
            case WEBP -> webp(input);
            case AVIF, JPEG_XL, SVG -> Header.unknown();
        };
    }

    private Header png(InputStream input, long limit) throws Exception {
        DataInputStream in = new DataInputStream(input);
        byte[] sig = in.readNBytes(8);
        byte[] expected = {(byte)137,80,78,71,13,10,26,10};
        if (!Arrays.equals(sig, expected)) return Header.unknown();
        long scanned = 8; int width=0,height=0,bit=0; boolean dimensions=false,alpha=false,alphaKnown=false,icc=false,exif=false,animated=false;
        while (scanned + 12 <= limit) {
            int length = in.readInt(); byte[] typeBytes = in.readNBytes(4); scanned += 8;
            if (length < 0 || scanned + (long) length + 4 > limit) break;
            String type = new String(typeBytes, StandardCharsets.US_ASCII);
            if ("IHDR".equals(type) && length >= 13) {
                width=in.readInt();height=in.readInt();bit=in.readUnsignedByte();int color=in.readUnsignedByte();
                in.skipNBytes(3); if(length>13)in.skipNBytes(length-13L);
                dimensions=width>0&&height>0;alpha=color==4||color==6;alphaKnown=true;
            } else { in.skipNBytes(length); }
            in.skipNBytes(4); scanned += (long) length + 4;
            if ("tRNS".equals(type)) { alpha=true;alphaKnown=true; }
            else if ("iCCP".equals(type)) icc=true;
            else if ("eXIf".equals(type)) exif=true;
            else if ("acTL".equals(type)) animated=true;
            if ("IDAT".equals(type)) break;
        }
        return new Header(width,height,dimensions,alpha,alphaKnown,animated,true,bit,icc,exif,0);
    }

    private Header jpeg(InputStream input, long limit) throws Exception {
        DataInputStream in = new DataInputStream(input); long scanned=0;
        if (in.readUnsignedByte()!=0xFF || in.readUnsignedByte()!=0xD8) return Header.unknown(); scanned=2;
        int width=0,height=0,bit=0;boolean dimensions=false,icc=false,exif=false;
        while(scanned+4<=limit){
            int prefix; do { prefix=in.readUnsignedByte();scanned++; } while(prefix!=0xFF&&scanned<limit);
            int marker; do { marker=in.readUnsignedByte();scanned++; } while(marker==0xFF&&scanned<limit);
            if(marker==0xD9||marker==0xDA)break;
            if(marker>=0xD0&&marker<=0xD7)continue;
            int length=in.readUnsignedShort();scanned+=2;if(length<2||scanned+length-2>limit)break;int payload=length-2;
            if(isSof(marker)&&payload>=6){bit=in.readUnsignedByte();height=in.readUnsignedShort();width=in.readUnsignedShort();int consumed=5;in.skipNBytes(payload-consumed);dimensions=width>0&&height>0;}
            else if(marker==0xE1&&payload>=6){byte[]h=in.readNBytes(Math.min(payload,16));exif=starts(h,"Exif\0\0".getBytes(StandardCharsets.US_ASCII));in.skipNBytes(payload-h.length);}
            else if(marker==0xE2&&payload>=11){byte[]h=in.readNBytes(Math.min(payload,20));icc=starts(h,"ICC_PROFILE\0".getBytes(StandardCharsets.US_ASCII));in.skipNBytes(payload-h.length);}
            else in.skipNBytes(payload);
            scanned+=payload;
        }
        return new Header(width,height,dimensions,false,true,false,true,bit,icc,exif,0);
    }

    private Header gif(InputStream input, long limit) throws Exception {
        DataInputStream in = new DataInputStream(input);
        byte[] header = in.readNBytes(13);
        if (header.length < 13) return Header.unknown();
        String signature = new String(header, 0, 6, StandardCharsets.US_ASCII);
        if (!signature.equals("GIF87a") && !signature.equals("GIF89a")) return Header.unknown();
        int width = (header[6] & 255) | ((header[7] & 255) << 8);
        int height = (header[8] & 255) | ((header[9] & 255) << 8);
        int packed = header[10] & 255;
        long scanned = 13;
        if ((packed & 0x80) != 0) {
            int entries = 1 << ((packed & 0x07) + 1);
            long tableBytes = (long) entries * 3L;
            if (scanned + tableBytes > limit) return new Header(width,height,width>0&&height>0,false,false,false,false,8,false,false,0);
            in.skipNBytes(tableBytes); scanned += tableBytes;
        }
        int frames = 0;
        boolean transparent = false;
        boolean complete = false;
        while (scanned < limit) {
            int marker;
            try { marker = in.readUnsignedByte(); }
            catch (EOFException eof) { break; }
            scanned++;
            if (marker == 0x3B) { complete = true; break; }
            if (marker == 0x2C) {
                byte[] descriptor = in.readNBytes(9); scanned += descriptor.length;
                if (descriptor.length < 9) break;
                frames++;
                int imagePacked = descriptor[8] & 255;
                if ((imagePacked & 0x80) != 0) {
                    int entries = 1 << ((imagePacked & 0x07) + 1);
                    long tableBytes = (long) entries * 3L;
                    if (scanned + tableBytes > limit) break;
                    in.skipNBytes(tableBytes); scanned += tableBytes;
                }
                if (scanned >= limit) break;
                in.readUnsignedByte(); scanned++; // LZW minimum code size
                long skipped = skipGifSubBlocks(in, limit - scanned);
                if (skipped < 0) break;
                scanned += skipped;
                continue;
            }
            if (marker == 0x21) {
                if (scanned >= limit) break;
                int label = in.readUnsignedByte(); scanned++;
                if (label == 0xF9) {
                    if (scanned >= limit) break;
                    int size = in.readUnsignedByte(); scanned++;
                    if (size < 0 || scanned + size + 1L > limit) break;
                    byte[] control = in.readNBytes(size); scanned += control.length;
                    if (control.length != size) break;
                    if (size > 0 && (control[0] & 0x01) != 0) transparent = true;
                    int terminator = in.read();
                    if (terminator < 0) break;
                    scanned++;
                    if (terminator != 0) break;
                } else {
                    long skipped = skipGifSubBlocks(in, limit - scanned);
                    if (skipped < 0) break;
                    scanned += skipped;
                }
                continue;
            }
            break;
        }
        boolean animationKnown = complete || frames > 1;
        boolean alphaKnown = complete || transparent;
        return new Header(width,height,width>0&&height>0,transparent,alphaKnown,frames>1,animationKnown,8,false,false,0);
    }

    /** Returns consumed bytes or -1 when the sub-block stream is truncated/over limit. */
    private long skipGifSubBlocks(DataInputStream in, long remaining) throws Exception {
        long consumed = 0;
        while (consumed < remaining) {
            int size;
            try { size = in.readUnsignedByte(); }
            catch (EOFException eof) { return -1; }
            consumed++;
            if (size == 0) return consumed;
            if (consumed + size > remaining) return -1;
            in.skipNBytes(size); consumed += size;
        }
        return -1;
    }

    private Header webp(InputStream input) throws Exception {
        byte[]h=input.readNBytes(30);if(h.length<16||!starts(h,"RIFF".getBytes(StandardCharsets.US_ASCII))||!equalsAt(h,8,"WEBP"))return Header.unknown();
        if(equalsAt(h,12,"VP8X")&&h.length>=30){int flags=h[20]&255;int width=1+le24(h,24),height=1+le24(h,27);return new Header(width,height,true,(flags&0x10)!=0,true,(flags&0x02)!=0,true,8,false,false,0);}
        return Header.unknown();
    }

    private boolean isSof(int marker){return switch(marker){case 0xC0,0xC1,0xC2,0xC3,0xC5,0xC6,0xC7,0xC9,0xCA,0xCB,0xCD,0xCE,0xCF->true;default->false;};}
    private boolean starts(byte[]value,byte[]prefix){if(value.length<prefix.length)return false;for(int i=0;i<prefix.length;i++)if(value[i]!=prefix[i])return false;return true;}
    private boolean equalsAt(byte[]value,int offset,String ascii){byte[]b=ascii.getBytes(StandardCharsets.US_ASCII);if(offset+b.length>value.length)return false;for(int i=0;i<b.length;i++)if(value[offset+i]!=b[i])return false;return true;}
    private int le24(byte[]b,int o){return (b[o]&255)|((b[o+1]&255)<<8)|((b[o+2]&255)<<16);}

    private ImageCodec codec(EpubResourceNode node){String m=node.mediaType()==null?"":node.mediaType().toLowerCase(Locale.ROOT),p=node.path().toLowerCase(Locale.ROOT);if(m.contains("jpeg")||p.matches(".*\\.jpe?g$"))return ImageCodec.JPEG;if(m.contains("png")||p.endsWith(".png"))return ImageCodec.PNG;if(m.contains("gif")||p.endsWith(".gif"))return ImageCodec.GIF;if(m.contains("webp")||p.endsWith(".webp"))return ImageCodec.WEBP;if(m.contains("avif")||p.endsWith(".avif"))return ImageCodec.AVIF;if(m.contains("jxl")||p.endsWith(".jxl"))return ImageCodec.JPEG_XL;if(m.contains("svg")||p.endsWith(".svg"))return ImageCodec.SVG;return null;}
    private ImageRole role(EpubResourceNode node,Header h,int refs){if(node.properties().contains("cover-image"))return ImageRole.COVER;if(h.dimensionsKnown&&h.width<=256&&h.height<=256&&refs>0)return ImageRole.ICON;return refs>0?ImageRole.CONTENT:ImageRole.UNKNOWN;}

    public record StandaloneImageAnalysis(ImageCodec codec, boolean signatureVerified, int width, int height,
                                          boolean dimensionsKnown, boolean alpha, boolean alphaKnown,
                                          boolean animated, boolean animationKnown, int bitDepth,
                                          boolean iccProfile, boolean exif, int orientation, long byteSize) {
        static StandaloneImageAnalysis invalid(ImageCodec codec) {
            return new StandaloneImageAnalysis(codec, false, 0, 0, false, false, false, false, false, 0, false, false, 0, 0L);
        }
    }

    private record Header(int width,int height,boolean dimensionsKnown,boolean alpha,boolean alphaKnown,boolean animated,boolean animationKnown,int bitDepth,boolean icc,boolean exif,int orientation){static Header unknown(){return new Header(0,0,false,false,false,false,false,0,false,false,0);}}
}
