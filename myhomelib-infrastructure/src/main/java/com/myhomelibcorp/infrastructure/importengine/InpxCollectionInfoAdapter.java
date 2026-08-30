package com.myhomelibcorp.infrastructure.importengine;

import com.myhomelibcorp.application.catalog.collectioninfo.CollectionInfoCodec;
import com.myhomelibcorp.application.catalog.collectioninfo.CollectionSourceProperties;
import com.myhomelibcorp.application.port.out.catalog.CollectionInfoPort;
import com.myhomelibcorp.shared.util.BoundedIoSupport;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Component
public class InpxCollectionInfoAdapter implements CollectionInfoPort {
    private static final int MAX_COLLECTION_INFO_BYTES = 1024 * 1024;
    @Override
    public Optional<CollectionSourceProperties> read(Path inpxFile) {
        if (inpxFile == null || !inpxFile.toFile().isFile()) return Optional.empty();
        String lower = inpxFile.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (!lower.endsWith(".inpx") && !lower.endsWith(".zip")) return Optional.empty();
        try (ZipFile zip = new ZipFile(inpxFile.toFile(), StandardCharsets.UTF_8)) {
            ZipEntry info = zip.stream()
                    .filter(e -> !e.isDirectory() && e.getName().equalsIgnoreCase("collection.info"))
                    .findFirst().orElse(null);
            if (info == null) return Optional.empty();
            try (var in = zip.getInputStream(info)) {
                String text = new String(BoundedIoSupport.readFully(in, MAX_COLLECTION_INFO_BYTES), StandardCharsets.UTF_8);
                return Optional.of(CollectionInfoCodec.parse(text));
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Не вдалося прочитати collection.info: " + inpxFile, e);
        }
    }
}
