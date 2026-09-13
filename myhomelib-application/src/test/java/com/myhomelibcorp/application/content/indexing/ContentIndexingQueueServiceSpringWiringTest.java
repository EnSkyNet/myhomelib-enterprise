package com.myhomelibcorp.application.content.indexing;

import com.myhomelibcorp.application.port.out.contentindexing.ContentIndexQueueCheckpointPort;
import com.myhomelibcorp.application.port.out.contentindexing.ContentIndexingTaskProcessor;
import com.myhomelibcorp.application.port.out.contentindexing.PowerStatePort;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ContentIndexingQueueServiceSpringWiringTest {

    @Test
    void springUsesProductionConstructorWhenTestConstructorAlsoExists() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ContentIndexingTaskProcessor.class, () -> mock(ContentIndexingTaskProcessor.class));
            context.registerBean(ContentIndexQueueCheckpointPort.class, NoopCheckpoint::new);
            context.registerBean(ApplicationSettingsPort.class, MemorySettings::new);
            context.registerBean(IndexingPerformanceSettingsService.class,
                    () -> new IndexingPerformanceSettingsService(context.getBean(ApplicationSettingsPort.class)));
            context.registerBean(PowerStatePort.class, () -> () -> false);
            context.register(ContentIndexingQueueService.class);

            context.refresh();

            ContentIndexingQueueService service = context.getBean(ContentIndexingQueueService.class);
            assertThat(service).isNotNull();
        }
    }

    private static final class NoopCheckpoint implements ContentIndexQueueCheckpointPort {
        @Override public Optional<ContentIndexQueueCheckpoint> load(String collectionId) { return Optional.empty(); }
        @Override public void save(ContentIndexQueueCheckpoint checkpoint) { }
        @Override public void clear(String collectionId) { }
    }

    private static final class MemorySettings implements ApplicationSettingsPort {
        private final Map<String, String> values = new java.util.concurrent.ConcurrentHashMap<>();
        @Override public String get(String key, String defaultValue) { return values.getOrDefault(key, defaultValue); }
        @Override public void put(String key, String value) { if (value == null) values.remove(key); else values.put(key, value); }
        @Override public void remove(String key) { values.remove(key); }
        @Override public Map<String, String> findByPrefix(String prefix) {
            Map<String, String> result = new java.util.concurrent.ConcurrentHashMap<>();
            values.forEach((key, value) -> { if (key.startsWith(prefix)) result.put(key, value); });
            return result;
        }
    }
}
