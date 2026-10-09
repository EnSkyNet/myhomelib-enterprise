# MyHomeLib 8.0.0 R4 — deep audit WIP checkpoint
## 2026-09-24

### Включені зміни відносно `R4-FB2-CALIBRE-RUNTIME-FIX-CHECKPOINT-2026-09-24`

Поточний source містить приблизно 22 змінених/нових файли (~462 додані, ~59 видалені рядки; без generated output).

Основні напрямки:
- batch online download lifecycle/queue fix;
- catalog preflight placeholder / first-sync baseline correctness;
- INPX full snapshot і large-delta import speedups;
- Lucene full rebuild write-path optimization;
- search-stream avoidance of empty optional joins;
- startup INDEX/QUERY shared lifecycle;
- cancellation of background Lucene rebuild before executor shutdown;
- JavaFX popup theme lookup fix;
- EPUB 3.4 package promotion path + focused test.

### Аналіз наданого runtime log

Update, який користувач сприйняв як ~15 хв, фактично виконував bootstrap-подібну послідовність:
- `fullSnapshot=true`, `707154` records, empty catalog;
- full INPX import ~476.4 s;
- delta `23841` ~208.4 s;
- full Lucene rebuild `594822` docs ~221.4 s;
- statistics ~6.5 s.

Окремо log підтвердив startup race: `INDEX` блокував `QUERY`, через що падали navigation/status bar і FXML loading. Поточний source уже змінено так, щоб background INDEX був shared-compatible з read-only QUERY/READER_STATE.

### EPUB 3.4

Поточний source містить promotion з EPUB 3.x до OPF package version 3.4 для `MODERN_EPUB34` при `MODERN_EPUB34_EXPERIMENTAL`, із structural + semantic validation. Runtime verification на Windows ще потрібна. Наданий log не містив conversion attempt, тому конкретний Windows failure з нього не витягується.

### Build/test status

Targeted Maven tests були запущені, але build model не завантажився через відсутність DNS/Internet до Maven Central (`repo.maven.apache.org`). Тобто кодові тести не виконувалися й не можуть вважатися ані passed, ані failed.

### Важливо

Це **source WIP checkpoint**, а не підтверджений Windows release JAR. Для release потрібен окремий runtime/regression цикл за `TASKS-CONTINUE-2026-09-24-UA.md`.
