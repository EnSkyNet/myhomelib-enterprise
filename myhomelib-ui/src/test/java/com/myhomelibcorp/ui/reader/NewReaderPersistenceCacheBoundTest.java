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

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class NewReaderPersistenceCacheBoundTest {
    @Test
    void smallTextAndCharacterOffsetChangesPersistExactLastPosition() {
        ReadingProgressRepository progress = mock(ReadingProgressRepository.class);
        Map<String, ReadingProgressDto> storage = new ConcurrentHashMap<>();
        when(progress.findByBookId(anyString())).thenAnswer(i -> Optional.ofNullable(storage.get(i.getArgument(0))));
        doAnswer(i -> {
            ReadingProgressDto item = i.getArgument(0);
            storage.put(item.getBookId(), item);
            return null;
        }).when(progress).save(any(ReadingProgressDto.class));
        CollectionLifecyclePort lifecycle = mock(CollectionLifecyclePort.class);
        when(lifecycle.getCurrentCollection()).thenReturn(
                new Collection("c1", "Test", null, null, 0, null, null, null, null));
        NewReaderPersistenceService service = new NewReaderPersistenceService(
                progress, mock(BookmarkRepository.class), mock(LocalizationService.class),
                lifecycle, new LibraryOperationCoordinator());

        ReaderPosition first = new ReaderPosition(0, 100, 2, 0);
        ReaderPosition fewCharactersLater = new ReaderPosition(0, 103, 2, 0);
        ReaderPosition exactCharacter = new ReaderPosition(0, 103, 2, 1);
        assertThat(service.savePosition("c1", "book", first, 10000)).isTrue();
        assertThat(service.savePosition("c1", "book", fewCharactersLater, 10000)).isTrue();
        assertThat(service.savePosition("c1", "book", exactCharacter, 10000)).isTrue();
        // Repeated, identical snapshots need no additional database write.
        assertThat(service.savePosition("c1", "book", exactCharacter, 10000)).isTrue();

        assertThat(service.loadPosition("c1", "book")).contains(exactCharacter);
        verify(progress, times(3)).save(any(ReadingProgressDto.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void hundredsOfPersistedBooksCannotGrowCacheAndEvictedPositionStillLoadsFromDatabase() throws Exception {
        ReadingProgressRepository progress = mock(ReadingProgressRepository.class);
        Map<String, ReadingProgressDto> storage = new ConcurrentHashMap<>();
        when(progress.findByBookId(anyString())).thenAnswer(i -> Optional.ofNullable(storage.get(i.getArgument(0))));
        doAnswer(i -> {
            ReadingProgressDto item = i.getArgument(0);
            storage.put(item.getBookId(), item);
            return null;
        }).when(progress).save(any(ReadingProgressDto.class));
        CollectionLifecyclePort lifecycle = mock(CollectionLifecyclePort.class);
        when(lifecycle.getCurrentCollection()).thenReturn(
                new Collection("c1", "Test", null, null, 0, null, null, null, null));
        NewReaderPersistenceService service = new NewReaderPersistenceService(
                progress, mock(BookmarkRepository.class), mock(LocalizationService.class),
                lifecycle, new LibraryOperationCoordinator());

        ReaderPosition oldest = new ReaderPosition(1, 23, 2, 3);
        for (int i = 0; i < 750; ++i) {
            ReaderPosition value = i == 0 ? oldest : new ReaderPosition(1, i + 23L, 2, 3);
            assertThat(service.savePosition("c1", "book-" + i, value, 10000)).isTrue();
        }
        Field cacheField = NewReaderPersistenceService.class.getDeclaredField("lastSavedPositions");
        cacheField.setAccessible(true);
        Map<String, ReaderPosition> cache = (Map<String, ReaderPosition>) cacheField.get(service);
        assertThat(cache).hasSize(512);
        assertThat(cache).doesNotContainKey("c1\u0000book-0");
        assertThat(cache).containsKey("c1\u0000book-749");

        assertThat(service.loadPosition("c1", "book-0")).contains(oldest);
        assertThat(cache).hasSize(512).containsKey("c1\u0000book-0");
        verify(progress, times(750)).save(any(ReadingProgressDto.class));
        service.clearCache();
        assertThat(cache).isEmpty();
    }
}
