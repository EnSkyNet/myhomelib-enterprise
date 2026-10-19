package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.application.dto.ReadingProgressDto;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.port.out.repository.BookmarkRepository;
import com.myhomelibcorp.application.port.out.repository.ReadingProgressRepository;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.reader.api.ReaderPosition;
import com.myhomelibcorp.ui.service.LocalizationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReaderPersistenceConcurrentBooksTest {
    @TempDir Path temporary;

    @Test
    void stalledBookCannotHoldUpSaveForDifferentBook() throws Exception {
        CountDownLatch slowEntered = new CountDownLatch(1);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        var progress = mock(ReadingProgressRepository.class);
        when(progress.findByBookId(any())).thenReturn(Optional.empty());
        doAnswer(invocation -> {
            ReadingProgressDto dto = invocation.getArgument(0);
            if ("slow".equals(dto.getBookId())) {
                slowEntered.countDown();
                if (!releaseSlow.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out in test");
                }
            }
            return null;
        }).when(progress).save(any(ReadingProgressDto.class));
        var lifecycle = mock(CollectionLifecyclePort.class);
        when(lifecycle.getCurrentCollection()).thenReturn(
                new Collection("c", "Test", null, null, 0, null, null, null, null));
        var service = new NewReaderPersistenceService(progress, mock(BookmarkRepository.class),
                mock(LocalizationService.class), lifecycle, new LibraryOperationCoordinator(),
                new ReaderPositionRecoveryJournal(temporary.resolve("journal")));
        var pool = Executors.newFixedThreadPool(2);
        try {
            var slow = pool.submit(() -> service.savePosition("c", "slow", new ReaderPosition(0, 10, 0, 0), 100));
            assertThat(slowEntered.await(3, TimeUnit.SECONDS)).isTrue();
            var fast = pool.submit(() -> service.savePosition("c", "fast", new ReaderPosition(0, 20, 0, 0), 100));
            assertThat(fast.get(3, TimeUnit.SECONDS)).isTrue();
            releaseSlow.countDown();
            assertThat(slow.get(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseSlow.countDown();
            pool.shutdownNow();
        }
    }
}
