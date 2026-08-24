package com.myhomelibcorp.application.usecase.collection;

import com.myhomelibcorp.application.imports.context.ImportContext;
import com.myhomelibcorp.application.imports.statistics.ImportResult;
import com.myhomelibcorp.application.port.out.download.RemoteCatalogDownloadPort;
import com.myhomelibcorp.application.service.CollectionLifecycleService;
import com.myhomelibcorp.application.usecase.imports.ImportFileUseCase;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.shared.util.AppPaths;
import lombok.RequiredArgsConstructor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.DoubleConsumer;

@RequiredArgsConstructor
public class UpdateCollectionFromNetworkUseCase {
    private final RemoteCatalogDownloadPort downloader;
    private final ImportFileUseCase importer;
    private final CollectionLifecycleService lifecycle;

    public ImportResult execute(Collection collection, String inpxUrl, AtomicBoolean cancel, DoubleConsumer progress) {
        if (collection == null) throw new IllegalArgumentException("Колекцію не вибрано");
        if (inpxUrl == null || inpxUrl.isBlank()) throw new IllegalArgumentException("Не задано URL INPX");
        AtomicBoolean flag = cancel == null ? new AtomicBoolean(false) : cancel;
        Path downloaded = null;
        try {
            downloaded = downloader.download(collection, inpxUrl, flag, progress == null ? p -> {} : progress);
            if (flag.get()) throw new IllegalStateException("Оновлення скасовано");
            Path root = collection.getRootFolder() != null ? collection.getRootFolder() : downloaded.getParent();
            ImportResult result = importer.execute(ImportContext.builder()
                    .file(downloaded).rootDirectory(root).updateExisting(true).indexAfterSave(false)
                    .batchSize(1000).cancelFlag(flag).build());
            if (!flag.get()) lifecycle.rebuildSearchIndex();
            return result;
        } catch (RuntimeException e) { throw e;
        } catch (Exception e) { throw new IllegalStateException("Не вдалося оновити колекцію з мережі: " + e.getMessage(), e);
        } finally {
            if (downloaded != null && downloaded.startsWith(AppPaths.cacheDir())) try { Files.deleteIfExists(downloaded); } catch (Exception ignored) { }
        }
    }
}
