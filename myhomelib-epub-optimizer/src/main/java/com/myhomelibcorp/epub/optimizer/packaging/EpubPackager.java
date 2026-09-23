package com.myhomelibcorp.epub.optimizer.packaging;

import java.nio.file.Path;

public interface EpubPackager {
    void repack(Path source, Path target, EpubPackagingProfile profile) throws Exception;
    ZipCompressionBackend backend();
}
