package com.myhomelibcorp.application.usecase.collection;

import com.myhomelibcorp.application.event.CollectionOpenedEvent;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionSwitcher;
import com.myhomelibcorp.application.port.out.repository.CollectionRepository;
import com.myhomelibcorp.domain.model.collection.Collection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class SwitchCollectionUseCase {

    private final CollectionRepository collectionRepository;
    private final CollectionSwitcher collectionSwitcher;
    private final ApplicationEventPublisher eventPublisher;

    public void execute(String collectionId) {
        Optional<Collection> collectionOpt = collectionRepository.findById(collectionId);
        if (collectionOpt.isEmpty()) {
            throw new IllegalArgumentException("Колекцію не знайдено: " + collectionId);
        }
        Collection collection = collectionOpt.get();
        execute(collection);
    }

    public void execute(Collection collection) {
        if (collection == null) {
            throw new IllegalArgumentException("Колекція не може бути null");
        }

        log.info("Переключення на колекцію: {}", collection.getName());

        // Технічне переключення
        collectionSwitcher.switchToCollection(collection);

        // Публікуємо подію ТІЛЬКИ ТУТ
        CollectionOpenedEvent event = new CollectionOpenedEvent(collection);
        eventPublisher.publishEvent(event);
        log.info("Опубліковано подію CollectionOpenedEvent для колекції: {}", collection.getName());
    }
}