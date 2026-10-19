package com.myhomelibcorp.epub.optimizer.image;

import com.myhomelibcorp.epub.optimizer.fs.EpubPathSafety;
import com.myhomelibcorp.epub.optimizer.profile.ImageCodec;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * Conservative JDK-only PNG recompressor.
 *
 * <p>The provider never resizes or changes codec and refuses inputs whose decoded raster would be
 * unreasonably large. It is intentionally only a candidate generator: the optimizer independently
 * verifies signature, dimensions, alpha/animation semantics and decoded pixel equality before a
 * result can replace the EPUB resource.</p>
 */
@Component
public final class JavaPngOptimizerProvider implements ImageOptimizerProvider {

    @Override public String id() { return "jdk-png-lossless"; }
    @Override public boolean isAvailable() { return ImageIO.getImageWritersByFormatName("png").hasNext(); }
    @Override public Set<ImageCodec> inputCodecs() { return Set.of(ImageCodec.PNG); }
    @Override public Set<ImageCodec> outputCodecs() { return Set.of(ImageCodec.PNG); }

    @Override
    public Optional<ImageOptimizationCandidate> optimize(Path sourceResource, ImageOptimizationRequest request,
                                                         Path workspace) throws Exception {
        if (sourceResource == null || request == null || request.source() == null || workspace == null) return Optional.empty();
        ImageResourceAnalysis source = request.source();
        if (source.codec() != ImageCodec.PNG || source.animated()) return Optional.empty();
        if (!source.dimensionsKnown() || source.width() <= 0 || source.height() <= 0) return Optional.empty();
        if ((long) source.width() * (long) source.height() > EpubImageAnalyzer.MAX_OPTIMIZATION_DECODE_PIXELS) return Optional.empty();
        // ImageIO exposes BufferedImage samples in Java's common raster models. Avoid silently
        // reducing precision from high-bit-depth PNGs in the v1 lossless path.
        if (source.bitDepth() > 8) return Optional.empty();
        Path safeSource;
        try { safeSource = EpubPathSafety.requireSafeRegularFile(sourceResource, "PNG optimization source"); }
        catch (Exception unsafe) { return Optional.empty(); }
        if (Files.size(safeSource) <= 0L || Files.size(safeSource) > EpubImageAnalyzer.MAX_OPTIMIZATION_INPUT_BYTES) return Optional.empty();
        checkCancelled();
        // v1 never silently strips PNG ancillary metadata. tRNS is rendering-critical but can be
        // represented by the decoded alpha channel and is independently checked after encoding.
        if (hasUnsupportedAncillaryChunks(safeSource)) return Optional.empty();

        EpubPathSafety.createDirectoriesNoLinks(workspace, "EPUB optimizer workspace");
        Path candidate = workspace.resolve("candidate.png").toAbsolutePath().normalize();
        if (!candidate.startsWith(workspace.toAbsolutePath().normalize())) return Optional.empty();
        Files.deleteIfExists(candidate);

        BufferedImage image;
        try (InputStream input = Files.newInputStream(safeSource, LinkOption.NOFOLLOW_LINKS)) { image = ImageIO.read(input); }
        checkCancelled();
        if (image == null || image.getWidth() != source.width() || image.getHeight() != source.height()) return Optional.empty();
        if ((long) image.getWidth() * (long) image.getHeight() > EpubImageAnalyzer.MAX_OPTIMIZATION_DECODE_PIXELS) return Optional.empty();

        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("png");
        if (!writers.hasNext()) return Optional.empty();
        ImageWriter writer = writers.next();
        // ImageOutputStream does not own/close the supplied OutputStream.
        try (var raw = EpubPathSafety.openFreshOutputStream(candidate, "PNG optimization candidate");
             ImageOutputStream output = ImageIO.createImageOutputStream(raw)) {
            if (output == null) return Optional.empty();
            writer.setOutput(output);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                String[] types = param.getCompressionTypes();
                if (types != null && types.length > 0) param.setCompressionType(types[0]);
                // For the JDK PNG writer, a lower quality value requests stronger compression.
                // Candidate acceptance still depends on actual size and pixel equality.
                param.setCompressionQuality(0.0f);
            }
            writer.write(null, new javax.imageio.IIOImage(image, null, null), param);
            output.flush();
            checkCancelled();
        } finally {
            writer.dispose();
        }

        long size = Files.size(candidate);
        if (size <= 0 || size >= source.byteSize()) {
            Files.deleteIfExists(candidate);
            return Optional.empty();
        }
        boolean alpha = image.getColorModel() != null && image.getColorModel().hasAlpha();
        return Optional.of(new ImageOptimizationCandidate(candidate, ImageCodec.PNG,
                image.getWidth(), image.getHeight(), alpha, false, size, true, OptionalDouble.empty()));
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException("PNG optimization cancelled");
    }

    private boolean hasUnsupportedAncillaryChunks(Path png) throws Exception {
        try (InputStream raw = Files.newInputStream(EpubPathSafety.requireSafeRegularFile(png, "PNG chunk source"), LinkOption.NOFOLLOW_LINKS); DataInputStream in = new DataInputStream(raw)) {
            byte[] signature = in.readNBytes(8);
            byte[] expected = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
            if (!java.util.Arrays.equals(signature, expected)) return true;
            int chunks = 0;
            while (chunks++ < 100_000) {
                int length;
                try { length = in.readInt(); } catch (java.io.EOFException eof) { return true; }
                if (length < 0 || length > 128 * 1024 * 1024) return true;
                byte[] typeBytes = in.readNBytes(4);
                if (typeBytes.length != 4) return true;
                String type = new String(typeBytes, StandardCharsets.US_ASCII);
                boolean ancillary = (typeBytes[0] & 0x20) != 0;
                if (ancillary && !"tRNS".equals(type)) return true;
                in.skipNBytes(length);
                if (in.readNBytes(4).length != 4) return true; // CRC
                if ("IEND".equals(type)) return false;
            }
            return true;
        }
    }

}
