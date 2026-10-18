# V10: Reading progress revision compare-and-swap (SQLite)

V72 adds `revision INTEGER NOT NULL DEFAULT 0` to `reading_progress`; existing
anchors and book identity remain unchanged. The version is monotonic while the
row exists. The repository executes one of three paths:

- `revision == null`: unguarded legacy/sync UPSERT, which **increments** revision
  on every conflict and invalidates older Reader snapshots. This does **not**
  resolve older sync vs. newer sync records.
- `revision == -1`: guarded insert-if-absent. If another writer inserted first,
  zero affected rows results in a conflict and the shelf summary is untouched.
- `revision >= 0`: atomic guarded UPDATE with `WHERE book_id=? AND revision=?`,
  incrementing revision; zero affected rows means conflict (never a blind retry).

Reader copies the observed revision from the repository DTO, or uses -1 only
when there is no row. It prepares its durable recovery record first; if the CAS
conflicts, the record is preserved, and the operation returns failure. On a
subsequent load, replay is permitted only when the original medium anchor still
matches. This comparison cannot eliminate all ABA/tombstone races; see below.

All three repository paths update `books.progress` in the same transaction.
Backup import and collection transfer increment existing revisions on conflict;
merge advances beyond the largest of both input revisions.

## Remaining blockers

- Without a tombstone generation per book, deleting and reinserting progress can
  reset revision to zero; a stale client that observed revision zero might then
  update the new row. Protect with an immutable generation or tombstone before
  unconditional production sign-off.
- Sync/backup still support last-writer-wins resolution and can overwrite an
  independent user's progress; their revision increments prevent stale Reader
  updates but do not make those unguarded writers causally ordered.
- Reader journal records do not embed an expected revision. A writer that changes
  the row but retains the same medium anchor can cause an ABA-like replay decision.
- No Flyway execution / full Maven build / Windows NTFS tests in this cycle.
- Never apply V72 to a real collection before backing up and verifying a copy.
