package com.myhomelibcorp.application.usecase.collection;

import com.myhomelibcorp.application.catalog.CatalogSourceIdentity;
import com.myhomelibcorp.application.imports.context.ImportContext;
import com.myhomelibcorp.application.imports.statistics.ImportResult;
import com.myhomelibcorp.application.port.out.download.RemoteCatalogDownloadPort;
import com.myhomelibcorp.application.port.out.download.RemoteCatalogPackage;
import com.myhomelibcorp.application.port.out.download.RemoteCatalogUpdatePlan;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.application.service.CollectionLifecycleService;
import com.myhomelibcorp.application.usecase.imports.ImportFileUseCase;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.shared.util.AppPaths;
import lombok.RequiredArgsConstructor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.DoubleConsumer;

/** Updates the active collection from a remote INPX server or direct catalog file. */
@RequiredArgsConstructor
public class UpdateCollectionFromNetworkUseCase {
    private final RemoteCatalogDownloadPort downloader;
    private final ImportFileUseCase importer;
    private final CollectionLifecycleService lifecycle;
    private final ApplicationSettingsPort settings;

    public ImportResult execute(Collection collection, String source, AtomicBoolean cancel, DoubleConsumer progress) {
        if (collection == null) throw new IllegalArgumentException("Колекцію не вибрано");
        if (source == null || source.isBlank()) throw new IllegalArgumentException("Не задано URL INPX/сервера");
        Collection active = lifecycle.getCurrentCollection();
        if (active == null || active.getId() == null || collection.getId() == null || !active.getId().equals(collection.getId())) {
            throw new IllegalStateException("Оновлювати з мережі можна лише активну колекцію");
        }

        AtomicBoolean flag = cancel == null ? new AtomicBoolean(false) : cancel;
        DoubleConsumer sink = progress == null ? p -> { } : progress;
        String versionKey = versionKey(active.getId());
        String localVersion = settings.get(versionKey, "");
        List<Path> downloaded = new ArrayList<>();
        boolean importedAny = false;
        boolean indexRebuildStarted = false;

        try {
            RemoteCatalogUpdatePlan plan = downloader.downloadUpdates(
                    active, source.trim(), localVersion, flag, p -> sink.accept(Math.min(0.25, p * 0.25)));
            if (plan == null) throw new IllegalStateException("Сервер не повернув план оновлення каталогу");
            for (RemoteCatalogPackage pkg : plan.packages()) {
                if (pkg != null && pkg.file() != null) downloaded.add(pkg.file());
            }
            if (flag.get()) throw new IllegalStateException("Оновлення скасовано");
            if (plan.upToDate()) return new ImportResult(0, 0, 0, 0, 0);

            long imported = 0;
            long skipped = 0;
            long duplicates = 0;
            long errors = 0;
            long duration = 0;

            for (int packageIndex = 0; packageIndex < plan.packages().size(); packageIndex++) {
                RemoteCatalogPackage pkg = plan.packages().get(packageIndex);
                if (flag.get()) throw new IllegalStateException("Оновлення скасовано");
                Path root = active.getRootFolder() != null ? active.getRootFolder() : pkg.file().getParent();
                double importStart = 0.25 + 0.75 * packageIndex / plan.packages().size();
                double importSpan = 0.75 / plan.packages().size();
                ImportResult current = importer.execute(ImportContext.builder()
                        .file(pkg.file())
                        .rootDirectory(root)
                        .updateExisting(true)
                        .indexAfterSave(false)
                        .catalogFullSnapshot(pkg.fullSnapshot())
                        .catalogSourceKey(CatalogSourceIdentity.remoteCollection(collection.getId()))
                        .catalogSourceLocation(pkg.sourceUrl())
                        .batchSize(5000)
                        .cancelFlag(flag)
                        .progressListener(p -> sink.accept(Math.min(1.0, importStart + p * importSpan)))
                        .build());

                if (flag.get()) throw new IllegalStateException("Оновлення скасовано");
                importedAny = true;
                imported += current.imported();
                skipped += current.skipped();
                duplicates += current.duplicates();
                errors += current.errors();
                duration += current.durationMs();

                // Advance the local DataVersion analogue only after this package was imported successfully.
                if (pkg.version() != null && !pkg.version().isBlank()) {
                    settings.put(versionKey, pkg.version());
                }
            }

            indexRebuildStarted = true;
            lifecycle.rebuildSearchIndex();
            return new ImportResult(imported, skipped, duplicates, errors, duration);
        } catch (RuntimeException e) {
            rebuildIndexAfterPartialUpdate(importedAny, indexRebuildStarted, e);
            throw e;
        } catch (Exception e) {
            rebuildIndexAfterPartialUpdate(importedAny, indexRebuildStarted, e);
            throw new IllegalStateException("Не вдалося оновити колекцію з мережі: " + e.getMessage(), e);
        } finally {
            for (Path path : downloaded) {
                if (path != null && path.startsWith(AppPaths.cacheDir())) {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                }
            }
        }
    }

    private void rebuildIndexAfterPartialUpdate(boolean importedAny, boolean indexRebuildStarted, Exception original) {
        if (!importedAny || indexRebuildStarted) return;
        try {
            lifecycle.rebuildSearchIndex();
        } catch (Exception rebuildFailure) {
            original.addSuppressed(rebuildFailure);
        }
    }

    static String versionKey(String collectionId) {
        return "collection." + collectionId + ".catalogVersion";
    }
}
