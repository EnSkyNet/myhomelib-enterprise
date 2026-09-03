#!/usr/bin/env python3
"""Ratchet: continuation pages must not re-count large book result sets."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def text(rel: str) -> str:
    return (ROOT / rel).read_text(encoding="utf-8")

repo = text("myhomelib-application/src/main/java/com/myhomelibcorp/application/port/out/repository/BookQueryRepository.java")
author = text("myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/book/LoadBooksByAuthorUseCase.java")
group = text("myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/group/LoadGroupBooksUseCase.java")
group_ui = text("myhomelib-ui/src/main/java/com/myhomelibcorp/ui/group/GroupWorkspaceController.java")
book_loader = text("myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/BookLoaderService.java")

checks = {
    "repository known-total contract": "findPage(BookQuery query, long knownTotal)" in repo,
    "author continuation reuses total": "findPage(query, expectedTotal.getAsLong())" in author,
    "author title continuation uses keyset": "findTitlePageByCursor" in author and "BookPageDirection.AFTER" in author,
    "group use case accepts known total": "execute(Long groupId, int limit, int offset, long knownTotal)" in group,
    "group workspace retains exact total": "OptionalLong currentGroupTotal" in group_ui and "knownTotal.getAsLong()" in group_ui,
    "main catalog retains known total": "OptionalLong knownTotal" in book_loader and "loadBooksUseCase.execute(submittedQuery, knownTotal.getAsLong())" in book_loader,
}

failed = [name for name, ok in checks.items() if not ok]
if failed:
    print("LIST CONTINUATION COUNT REUSE CHECK: FAIL")
    for name in failed:
        print(f" - {name}: FAIL")
    raise SystemExit(1)

print("LIST CONTINUATION COUNT REUSE CHECK: PASS")
for name in checks:
    print(f" - {name}: PASS")
