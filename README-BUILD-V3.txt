V3 BUILD NOTE
=============

Do NOT unpack this archive over an old source tree.
Recommended:
  1. Rename/delete the previous D:\JavaLessons\myhomelib-enterprise directory.
  2. Extract this archive so the resulting directory is:
       D:\JavaLessons\myhomelib-enterprise
  3. Run:
       BUILD-CHECK-FIXES.cmd

The script verifies that SqliteBookQueryRepository.java contains:
  SqliteBookQueryRepository.this.findPage(query).content();
and removes a stale PostgresBookRepository.java if an old copy somehow exists.
