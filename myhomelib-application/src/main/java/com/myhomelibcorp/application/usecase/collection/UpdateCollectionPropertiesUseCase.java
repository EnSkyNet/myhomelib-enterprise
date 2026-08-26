package com.myhomelibcorp.application.usecase.collection;

import com.myhomelibcorp.application.port.out.repository.CollectionRepository;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.collection.CollectionType;
import lombok.RequiredArgsConstructor;

import java.nio.file.Path;

@RequiredArgsConstructor
public class UpdateCollectionPropertiesUseCase {
    private final CollectionRepository repository;
    private final CollectionLifecyclePort lifecyclePort;

    public Collection execute(Collection current, String name, Path rootFolder, int type,
                              String user, String password, String url, String notes) {
        if (current == null) throw new IllegalArgumentException("Колекцію не вибрано");
        boolean validType = java.util.Arrays.stream(CollectionType.values()).anyMatch(value -> value.getCode() == type);
        if (!validType) throw new IllegalArgumentException("Невідомий тип колекції: " + type);
        String safeName = name == null || name.isBlank() ? current.getName() : name.trim();
        repository.findByName(safeName)
                .filter(existing -> !existing.getId().equals(current.getId()))
                .ifPresent(existing -> {
                    throw new IllegalArgumentException("Колекція з назвою '" + safeName + "' вже існує");
                });
        Collection updated = new Collection(current.getId(), safeName,
                rootFolder == null ? current.getRootFolder() : rootFolder.toAbsolutePath().normalize(),
                current.getDbFile(), type, blankToNull(user), blankToNull(password), blankToNull(url), blankToNull(notes));
        Collection saved = repository.save(updated);
        Collection active = lifecyclePort.getCurrentCollection();
        if (active != null && active.getId() != null && active.getId().equals(saved.getId())) {
            lifecyclePort.updateCurrentCollection(saved);
        }
        return saved;
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
