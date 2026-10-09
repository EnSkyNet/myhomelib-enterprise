package com.myhomelibcorp.application.port.out.cache;

import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;

public interface AuthorCache extends EntityCache<AuthorId, Author> {
}