package com.myhomelibcorp.infrastructure.importengine;

import com.myhomelibcorp.application.catalog.collectioninfo.CollectionInfoCodec;
import com.myhomelibcorp.application.catalog.collectioninfo.CollectionSourceProperties;
import com.myhomelibcorp.application.port.out.catalog.CollectionInfoPort;
import com.myhomelibcorp.shared.util.BoundedIoSupport;
import com.myhomelibcorp.infrastructure.archive.SafeZipArchive;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;

@Component
public class InpxCollectionInfoAdapter implements CollectionInfoPort {
    private static final int MAX_COLLECTION_INFO_BYTES = 1024 * 1024;
    @Override
    public Optional<CollectionSourceProperties> read(Path inpxFile) {
        if (inpxFile == null) return Optional.empty();
        String lower = inpxFile.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (!lower.endsWith(".inpx") && !lower.endsWith(".zip")) return Optional.empty();
        try (SafeZipArchive opened = SafeZipArchive.open(
                inpxFile, StandardCharsets.UTF_8, "INPX collection.info source")) {
            var zip = opened.zip();
            ZipArchiveEntry info = null;
            var entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry candidate = entries.nextElement();
                if (!candidate.isDirectory() && candidate.getName().equalsIgnoreCase("collection.info")) {
                    info = candidate;
                    break;
                }
            }
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
