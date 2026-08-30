package com.myhomelibcorp.application.usecase.collection;

import com.myhomelibcorp.application.port.out.repository.CollectionRepository;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.collection.CollectionType;
import com.myhomelibcorp.application.catalog.collectioninfo.CollectionPropertiesTrustPolicy;
import com.myhomelibcorp.application.catalog.collectioninfo.CollectionSourceProperties;
import lombok.RequiredArgsConstructor;

import java.nio.file.Path;

@RequiredArgsConstructor
public class UpdateCollectionPropertiesUseCase {
    private final CollectionRepository repository;
    private final CollectionLifecyclePort lifecyclePort;

    public Collection execute(Collection current, String name, Path rootFolder, int type,
                              String user, String password, String url, String notes) {
        return execute(current, name, rootFolder, type, user, password, url, notes,
                current == null ? null : current.getConnectionScript());
    }

    public Collection execute(Collection current, String name, Path rootFolder, int type,
                              String user, String password, String url, String notes,
                              String connectionScript) {
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
                current.getDbFile(), type, blankToNull(user), blankToNull(password), blankToNull(url),
                blankToNullPreserveMultiline(notes), blankToNullPreserveMultiline(connectionScript));
        Collection saved = repository.save(updated);
        Collection active = lifecyclePort.getCurrentCollection();
        if (active != null && active.getId() != null && active.getId().equals(saved.getId())) {
            lifecyclePort.updateCurrentCollection(saved);
        }
        return saved;
    }


    /** Applies server/source metadata under an explicit trust policy; credentials and local paths are never source-controlled. */
    public Collection applySourceProperties(Collection current, CollectionSourceProperties source,
                                            CollectionPropertiesTrustPolicy policy) {
        if (current == null) throw new IllegalArgumentException("Колекцію не вибрано");
        if (source == null || policy == null || policy == CollectionPropertiesTrustPolicy.PRESERVE_LOCAL_PROPERTIES) {
            return current;
        }
        String url = current.getUrl();
        String notes = current.getNotes();
        String script = current.getConnectionScript();
        if (policy == CollectionPropertiesTrustPolicy.APPLY_SOURCE_PROPERTIES) {
            url = source.url();
            notes = source.notes();
            script = source.connectionScript();
        } else if (policy == CollectionPropertiesTrustPolicy.MERGE_SAFE_PROPERTIES) {
            if (url == null || url.isBlank()) url = source.url();
            if (notes == null || notes.isBlank()) notes = source.notes();
            if (script == null || script.isBlank()) script = source.connectionScript();
        }
        return execute(current, current.getName(), current.getRootFolder(), current.getType(),
                current.getUser(), current.getPassword(), url, notes, script);
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
    private static String blankToNullPreserveMultiline(String s) { return s == null || s.isBlank() ? null : s; }
}
