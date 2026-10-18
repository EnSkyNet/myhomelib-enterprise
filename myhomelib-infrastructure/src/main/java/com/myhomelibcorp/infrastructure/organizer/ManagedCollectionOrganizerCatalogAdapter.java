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

    /**
     * The legacy catalog adapter only represents coordinates within the active
     * collection root. Moving a tracked file beyond that root would otherwise
     * skip the catalog callback and leave the row pointing to the deleted source.
     * Also reject moving a physical container shared by multiple logical books:
     * findByStorage() returns only one matching book and cannot update all rows.
     */
    @Override
    public void validateBeforeFilesystemCommit(OrganizationPlanItem item) {
        Collection collection = collections.getCurrentCollection();
        Path root = normalizedRoot(collection);
        if (root == null || item == null || item.sourcePath() == null || item.targetPath() == null) return;
        Path source = item.sourcePath().toAbsolutePath().normalize();
        Path target = item.targetPath().toAbsolutePath().normalize();
        if (!source.startsWith(root)) return;
        // A logical book extracted/converted from an archive is still catalog-managed.
        // It must not be published outside the managed root, because the callback
        // cannot represent the new coordinates in this collection's SQLite data.
        if (!item.sourceEntry().isBlank()) {
            if (!target.startsWith(root) && locate(root, source, item.sourceEntry()).isPresent()) {
                throw new IllegalStateException("Книга в архіві належить поточній колекції, але цільова папка "
                        + "розташована поза її коренем. Зміну скасовано до копіювання.");
            }
            return; // A logical extraction does not relocate the shared source archive.
        }
        Path parent = source.getParent();
        String folder = parent == null || parent.equals(root) ? "" : normalizeRelative(root.relativize(parent));
        String fileName = source.getFileName().toString();
        // Count physical storage references, not just one logical archive entry.
        // This catches shared containers for which findByStorage(..., "") returns empty.
        Integer references = collections.getCurrentJdbcTemplate().queryForObject("""
                SELECT COUNT(*) FROM books
                WHERE COALESCE(collection_root, '') IN (?, '')
                  AND REPLACE(COALESCE(folder, ''), CHAR(92), '/') = ?
                  AND COALESCE(file_name, '') = ?
                """, Integer.class, root.toString(), folder, fileName);
        if (references == null || references == 0) return; // Unmanaged filesystem import/export.
        if (!target.startsWith(root)) {
            throw new IllegalStateException("Файл належить поточній колекції, але нова папка розташована поза її коренем. "
                    + "Переміщення скасовано до оновлення каталогу. Виберіть папку всередині колекції.");
        }
        if (references > 1) {
            throw new IllegalStateException("Цей фізичний файл використовується кількома книгами каталогу. "
                    + "Переміщення заблоковано, щоб не пошкодити їхні шляхи.");
        }
        // Some logical books identify a source archive entry even though the plan
        // moves its physical container. A single-row findByStorage cannot find it.
        if (locate(root, source, "").isEmpty()) {
            throw new IllegalStateException("Не вдалося однозначно визначити запис фізичного файла "
                    + "в каталозі. Переміщення без оновлення SQLite заборонено.");
        }
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
