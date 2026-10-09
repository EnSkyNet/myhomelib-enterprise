package com.myhomelibcorp.application.comic.duplicates;

import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.resource.BookResourcePort;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.shared.comic.ComicPageNameSupport;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

/** Exact-byte duplicate scanner. V1 reports evidence only and never mutates an archive. */
@Service
public final class ScanComicDuplicatePagesUseCase {
    static final long MAX_PAGE_BYTES = 64L * 1024L * 1024L;
    private final BookQueryRepository books;
    private final BookResourcePort resources;

    public ScanComicDuplicatePagesUseCase(BookQueryRepository books, BookResourcePort resources) {
        this.books = Objects.requireNonNull(books); this.resources = Objects.requireNonNull(resources);
    }

    public ComicDuplicateScanResult scan(BookId bookId, ComicDuplicateScanControl control) {
        Objects.requireNonNull(bookId); ComicDuplicateScanControl ctl = control == null ? ComicDuplicateScanControl.none() : control;
        Book book = books.findById(bookId).orElseThrow(() -> new IllegalArgumentException("Book not found: " + bookId));
        Path container = resources.locateBookContainer(book).orElseThrow(() -> new IllegalStateException("Comic archive is unavailable"));
        checkCancelled(ctl);
        List<String> pages = ComicPageNameSupport.sortPages(resources.listArchiveEntries(container));
        if (pages.isEmpty()) return new ComicDuplicateScanResult(List.of(), List.of(), 0, 0);
        Map<DigestKey, List<DuplicatePageEvidence>> hashes = new LinkedHashMap<>();
        List<ComicPageScanFailure> failures = new ArrayList<>();
        int scanned = 0;
        for (int i=0;i<pages.size();i++) {
            checkCancelled(ctl); String entry=pages.get(i);
            try (InputStream in = resources.readArchiveEntry(container, entry)
                    .orElseThrow(() -> new IOException("Page entry is unavailable"))) {
                Digest digest = digest(in, ctl);
                hashes.computeIfAbsent(new DigestKey(digest.sha256(), digest.bytes()), ignored -> new ArrayList<>())
                        .add(new DuplicatePageEvidence(i, entry, digest.bytes()));
                scanned++;
            } catch (java.util.concurrent.CancellationException cancelled) { throw cancelled; }
            catch (Exception failure) {
                failures.add(new ComicPageScanFailure(i, entry, safeReason(failure)));
            }
            ctl.progress().accept(new ComicDuplicateScanProgress(i+1, pages.size()));
        }
        List<DuplicatePageGroup> groups = hashes.entrySet().stream().filter(e -> e.getValue().size() > 1)
                .map(e -> new DuplicatePageGroup(e.getKey().sha256(), e.getValue()))
                .sorted(Comparator.comparingInt((DuplicatePageGroup g) -> g.pages().getFirst().pageIndex()).thenComparing(DuplicatePageGroup::sha256))
                .toList();
        return new ComicDuplicateScanResult(groups, failures, scanned, pages.size());
    }

    private static Digest digest(InputStream in, ComicDuplicateScanControl ctl) throws Exception {
        MessageDigest sha=MessageDigest.getInstance("SHA-256"); byte[] buffer=new byte[64*1024]; long total=0;
        for (int n;(n=in.read(buffer))>=0;) {
            checkCancelled(ctl); if(n==0)continue; total+=n; if(total>MAX_PAGE_BYTES) throw new IOException("Comic page exceeds compressed-byte safety limit");
            sha.update(buffer,0,n);
        }
        return new Digest(java.util.HexFormat.of().formatHex(sha.digest()), total);
    }
    private static void checkCancelled(ComicDuplicateScanControl ctl) { if (ctl.cancelled().getAsBoolean() || Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("Comic duplicate scan cancelled"); }
    private static String safeReason(Exception e) { String m=e.getMessage(); return m==null||m.isBlank()?e.getClass().getSimpleName():m; }
    private record Digest(String sha256,long bytes) { }
    private record DigestKey(String sha256,long bytes) { }
}
