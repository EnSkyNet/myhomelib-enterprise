package com.myhomelibcorp.application.usecase.collection;

import com.myhomelibcorp.application.port.out.repository.CollectionRepository;
import com.myhomelibcorp.domain.model.collection.Collection;
import lombok.RequiredArgsConstructor;

import java.nio.file.Path;

@RequiredArgsConstructor
public class UpdateCollectionPropertiesUseCase {
    private final CollectionRepository repository;

    public Collection execute(Collection current, String name, Path rootFolder, int type,
                              String user, String password, String url, String notes) {
        if (current == null) throw new IllegalArgumentException("Колекцію не вибрано");
        String safeName = name == null || name.isBlank() ? current.getName() : name.trim();
        Collection updated = new Collection(current.getId(), safeName,
                rootFolder == null ? current.getRootFolder() : rootFolder.toAbsolutePath().normalize(),
                current.getDbFile(), type, blankToNull(user), blankToNull(password), blankToNull(url), blankToNull(notes));
        return repository.save(updated);
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
