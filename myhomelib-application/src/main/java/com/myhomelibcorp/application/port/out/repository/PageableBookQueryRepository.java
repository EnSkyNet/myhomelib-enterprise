package com.myhomelibcorp.application.port.out.repository;

import com.myhomelibcorp.application.dto.BookListItem;
import com.myhomelibcorp.application.query.book.PageableBookQuery;
import com.myhomelibcorp.application.query.common.PageResult;

public interface PageableBookQueryRepository {
    PageResult<BookListItem> findPage(PageableBookQuery query);
    long count(PageableBookQuery query);
}