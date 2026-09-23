package com.myhomelibcorp.infrastructure.organizer;

import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.service.CommittedCatalogMutationService;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.organizer.apply.OrganizerCatalogPort;
import com.myhomelibcorp.organizer.plan.OrganizationPlanItem;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Optional;

/** Keeps the active managed collection storage projection aligned with Organizer filesystem commits. */
@Component
public final class ManagedCollectionOrganizerCatalogAdapter implements OrganizerCatalogPort {
    private final CollectionManager collections;
    private final BookQueryRepository books;
    private final CommittedCatalogMutationService mutations;

    public ManagedCollectionOrganizerCatalogAdapter(CollectionManager collections,
                                                     BookQueryRepository books,
                                                     CommittedCatalogMutationService mutations) {
        this.collections = collections;
        this.books = books;
        this.mutations = mutations;
    }

    @Override
    public boolean supports(OrganizationPlanItem item) {
        Collection collection = collections.getCurrentCollection();
        Path root = normalizedRoot(collection);
        if (root == null || item == null || item.sourcePath() == null || item.targetPath() == null) return false;
        Path source = item.sourcePath().toAbsolutePath().normalize();
        Path target = item.targetPath().toAbsolutePath().normalize();
        if (!source.startsWith(root) || !target.startsWith(root)) return false;
        return locate(root, source, item.sourceEntry()).isPresent() || locate(root, target, "").isPresent();
    }

    @Override
    public void afterFilesystemCommit(OrganizationPlanItem item) {
        Collection collection = collections.getCurrentCollection();
        Path root = normalizedRoot(collection);
        if (root == null || item == null || item.targetPath() == null) return;
        Path source = item.sourcePath() == null ? null : item.sourcePath().toAbsolutePath().normalize();
        Path target = item.targetPath().toAbsolutePath().normalize();
        Optional<Book> book = source == null ? Optional.empty() : locate(root, source, item.sourceEntry());
        if (book.isEmpty()) book = locate(root, target, "");
        if (book.isEmpty()) return;
        if (item.action() == com.myhomelibcorp.organizer.plan.OrganizationAction.DELETE_EXACT_DUPLICATE
                || item.action() == com.myhomelibcorp.organizer.plan.OrganizationAction.ARCHIVE_ORIGINAL) {
            mutations.updateAvailability(book.get(), false);
            return;
        }
        update(book.get(), root, target, "");
    }

    @Override
    public void rollbackCatalog(OrganizationPlanItem item) {
        Collection collection = collections.getCurrentCollection();
        Path root = normalizedRoot(collection);
        if (root == null || item == null || item.sourcePath() == null) return;
        Path source = item.sourcePath().toAbsolutePath().normalize();

        if (item.action() == com.myhomelibcorp.organizer.plan.OrganizationAction.DELETE_EXACT_DUPLICATE
                || item.action() == com.myhomelibcorp.organizer.plan.OrganizationAction.ARCHIVE_ORIGINAL) {
            // These actions never moved the catalog row to targetPath; afterFilesystemCommit only made
            // the source book unavailable. Looking up targetPath here could select the retained duplicate.
            locate(root, source, item.sourceEntry()).ifPresent(book -> mutations.updateAvailability(book, true));
            return;
        }

        Path target = item.targetPath() == null ? null : item.targetPath().toAbsolutePath().normalize();
        Optional<Book> book = target == null ? Optional.empty() : locate(root, target, "");
        if (book.isEmpty()) book = locate(root, source, item.sourceEntry());
        if (book.isEmpty()) return;
        update(book.get(), root, source, item.sourceEntry());
    }

    private Optional<Book> locate(Path root, Path physicalPath, String archiveEntry) {
        if (physicalPath == null || !physicalPath.startsWith(root)) return Optional.empty();
        Path parent = physicalPath.getParent();
        String folder = parent == null || parent.equals(root) ? "" : normalizeRelative(root.relativize(parent));
        String fileName = physicalPath.getFileName() == null ? "" : physicalPath.getFileName().toString();
        String entry = archiveEntry == null ? "" : archiveEntry;
        Optional<Book> exact = books.findByStorage(root.toString(), folder, fileName, entry);
        if (exact.isPresent()) return exact;
        return books.findByStorage("", folder, fileName, entry);
    }

    private void update(Book book, Path root, Path physicalPath, String archiveEntry) {
        if (book == null || book.getId() == null || physicalPath == null || !physicalPath.startsWith(root)) return;
        Path parent = physicalPath.getParent();
        String folder = parent == null || parent.equals(root) ? "" : normalizeRelative(root.relativize(parent));
        String fileName = physicalPath.getFileName() == null ? "" : physicalPath.getFileName().toString();
        mutations.updateStorage(book.getId(), root.toString(), folder, fileName, archiveEntry == null ? "" : archiveEntry, true);
    }

    private Path normalizedRoot(Collection collection) {
        if (collection == null || collection.getRootFolder() == null) return null;
        return collection.getRootFolder().toAbsolutePath().normalize();
    }

    private String normalizeRelative(Path path) {
        if (path == null) return "";
        String value = path.toString().replace('\\', '/');
        return ".".equals(value) ? "" : value;
    }
}
