package com.myhomelibcorp.epub.optimizer.image;

import com.myhomelibcorp.epub.optimizer.EpubOptimizationProfile;
import com.myhomelibcorp.epub.optimizer.profile.ImageCodec;
import com.myhomelibcorp.epub.optimizer.profile.StandardReadingSystemProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;

import static org.assertj.core.api.Assertions.assertThat;

class JavaPngOptimizerProviderTest {
    @TempDir Path tempDir;

    @Test
    void recompressesPngOnlyWhenDecodedPixelsArePreserved() throws Exception {
        Path source = tempDir.resolve("source.png");
        BufferedImage image = new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, ((x / 32 + y / 32) & 1) == 0 ? 0xff102030 : 0xff203040);
            }
        }
        writePng(image, source, 1.0f);
        long sourceSize = Files.size(source);
        ImageResourceAnalysis analysis = new ImageResourceAnalysis("OEBPS/image.png", ImageCodec.PNG,
                512, 512, true, true, true, false, true, 8, sourceSize,
                false, false, 0, ImageRole.CONTENT, 1);
        ImageOptimizationRequest request = new ImageOptimizationRequest(analysis, EpubOptimizationProfile.BALANCED_EPUB33,
                StandardReadingSystemProfile.GENERIC_EPUB33_COMPAT, 0, 0, 1, 0.0, 1.0);

        ImageOptimizationCandidate candidate = new JavaPngOptimizerProvider()
                .optimize(source, request, tempDir.resolve("provider"))
                .orElseThrow();

        assertThat(candidate.codec()).isEqualTo(ImageCodec.PNG);
        assertThat(candidate.lossless()).isTrue();
        assertThat(candidate.byteSize()).isLessThan(sourceSize);
        assertThat(new EpubImageAnalyzer().decodedPixelsEqual(source, candidate.file(), ImageCodec.PNG)).isTrue();
    }

    @Test
    void rejectsSourceReachedThroughSymlinkedParentWhenPlatformAllowsSymlinks() throws Exception {
        Path real = Files.createDirectory(tempDir.resolve("real-source"));
        Path source = real.resolve("source.png");
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        writePng(image, source, 1.0f);
        Path link = tempDir.resolve("source-link");
        try {
            Files.createSymbolicLink(link, real);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unsupported) {
            return;
        }
        ImageResourceAnalysis analysis = new ImageResourceAnalysis("OEBPS/image.png", ImageCodec.PNG,
                32, 32, true, true, true, false, true, 8, Files.size(source),
                false, false, 0, ImageRole.CONTENT, 1);
        ImageOptimizationRequest request = new ImageOptimizationRequest(analysis, EpubOptimizationProfile.BALANCED_EPUB33,
                StandardReadingSystemProfile.GENERIC_EPUB33_COMPAT, 0, 0, 1, 0.0, 1.0);

        assertThat(new JavaPngOptimizerProvider().optimize(link.resolve("source.png"), request, tempDir.resolve("provider-link-test")))
                .isEmpty();
    }

    private static void writePng(BufferedImage image, Path target, float compressionQuality) throws Exception {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("png");
        assertThat(writers.hasNext()).isTrue();
        ImageWriter writer = writers.next();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(Files.newOutputStream(target,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
            writer.setOutput(output);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                String[] types = param.getCompressionTypes();
                if (types != null && types.length > 0) param.setCompressionType(types[0]);
                param.setCompressionQuality(compressionQuality);
            }
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
    }
}
