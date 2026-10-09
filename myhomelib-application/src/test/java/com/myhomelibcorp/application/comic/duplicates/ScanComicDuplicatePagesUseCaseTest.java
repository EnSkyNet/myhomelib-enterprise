package com.myhomelibcorp.application.comic.duplicates;

import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.resource.BookResourcePort;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ScanComicDuplicatePagesUseCaseTest {
    private final BookId id=BookId.fromString("00000000-0000-0000-0000-000000000901");
    private final Book book=Book.builder().id(id).title("Comic").file(BookFile.empty()).build();

    @Test
    void groupsExactDuplicateBytesButNotDifferentSameSize() {
        BookResourcePort r=resources(List.of("001.jpg","002.jpg","003.jpg"),Map.of("001.jpg","same","002.jpg","diff","003.jpg","same"));
        ComicDuplicateScanResult result=scanner(r).scan(id,ComicDuplicateScanControl.none());
        assertThat(result.groups()).hasSize(1);
        assertThat(result.groups().getFirst().pages()).extracting(DuplicatePageEvidence::entryName).containsExactly("001.jpg","003.jpg");
        assertThat(result.failures()).isEmpty();
    }

    @Test
    void unsafeTraversalAndNonImagesAreNeverOpened() {
        BookResourcePort r=resources(List.of("../evil.jpg","notes.txt","safe/01.png"),Map.of("safe/01.png","ok"));
        scanner(r).scan(id,ComicDuplicateScanControl.none());
        verify(r,never()).readArchiveEntry(any(),eq("../evil.jpg"));
        verify(r,never()).readArchiveEntry(any(),eq("notes.txt"));
        verify(r).readArchiveEntry(any(),eq("safe/01.png"));
    }

    @Test
    void corruptEntryIsReportedAndScanContinues() {
        BookResourcePort r=mockBase(List.of("1.jpg","2.jpg"));
        when(r.readArchiveEntry(any(),eq("1.jpg"))).thenReturn(Optional.of(new ByteArrayInputStream("ok".getBytes())));
        when(r.readArchiveEntry(any(),eq("2.jpg"))).thenThrow(new UncheckedIOException(new IOException("corrupt")));
        ComicDuplicateScanResult result=scanner(r).scan(id,ComicDuplicateScanControl.none());
        assertThat(result.scannedPages()).isEqualTo(1); assertThat(result.failures()).hasSize(1); assertThat(result.failures().getFirst().entryName()).isEqualTo("2.jpg");
    }

    @Test
    void cancellationStopsBeforeReadingRemainingPages() {
        BookResourcePort r=resources(List.of("1.jpg","2.jpg","3.jpg"),Map.of("1.jpg","a","2.jpg","b","3.jpg","c"));
        AtomicInteger progress=new AtomicInteger();
        ComicDuplicateScanControl control=new ComicDuplicateScanControl(() -> progress.get()>=1, p -> progress.set(p.completed()));
        assertThatThrownBy(() -> scanner(r).scan(id,control)).isInstanceOf(CancellationException.class);
        verify(r,times(1)).readArchiveEntry(any(),anyString());
    }

    @Test
    void thousandPageArchiveKeepsOnlyDigestEvidenceAndCompletesDeterministically() {
        List<String> names=new ArrayList<>(); Map<String,String> values=new LinkedHashMap<>();
        for(int i=0;i<1000;i++){String name=String.format("%04d.jpg",i); names.add(name); values.put(name,i%100==0?"dup":"v"+i);}
        ComicDuplicateScanResult result=scanner(resources(names,values)).scan(id,ComicDuplicateScanControl.none());
        assertThat(result.totalPages()).isEqualTo(1000); assertThat(result.scannedPages()).isEqualTo(1000);
        assertThat(result.groups()).singleElement().satisfies(g -> assertThat(g.pages()).hasSize(10));
    }

    private ScanComicDuplicatePagesUseCase scanner(BookResourcePort resources){
        BookQueryRepository q=mock(BookQueryRepository.class); when(q.findById(id)).thenReturn(Optional.of(book)); return new ScanComicDuplicatePagesUseCase(q,resources);
    }
    private BookResourcePort mockBase(List<String> names){
        BookResourcePort r=mock(BookResourcePort.class); Path path=Path.of("comic.cbz"); when(r.locateBookContainer(book)).thenReturn(Optional.of(path)); when(r.listArchiveEntries(path)).thenReturn(names); return r;
    }
    private BookResourcePort resources(List<String> names,Map<String,String> values){
        BookResourcePort r=mockBase(names); when(r.readArchiveEntry(any(),anyString())).thenAnswer(inv -> {String n=inv.getArgument(1); String v=values.get(n); return v==null?Optional.empty():Optional.of(new ByteArrayInputStream(v.getBytes()));}); return r;
    }
}
