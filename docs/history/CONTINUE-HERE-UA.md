# MyHomeLib 8.0.0 R4 — CONTINUE HERE

Дата handoff: 2026-09-21

## Важливо

Цей архів містить **останній реально збережений повний кодовий checkpoint** проєкту:
`MyHomeLib-8.0.0-R4-CONVERTERS-FIXED-WINDOWS-READY-2026-09-21`.

Після цього checkpoint під час deep audit були знайдені, виправлялись і тестувались додаткові дефекти по всьому проєкту, але через reset робочого середовища ці пізніші зміни не всі збереглися у файловому дереві.

Тому:
- цей ZIP — **робоча база для продовження**;
- `POST-CHECKPOINT-FIXES-UA.md` — перелік пізніших підтверджених змін, які треба відновити поверх коду;
- `TASKS-CONTINUE-UA.md` — покроковий план продовження;
- не називати аудит завершеним, доки не відновлено post-checkpoint patchset і не пройдені final gates.

## Що вже точно є в коді цього checkpoint

1. EPUBCheck 5.4.0 bundled як JAR + dependencies.
2. Bundled discovery EPUBCheck працює через `java -jar`, `epubcheck.exe` не є вимогою.
3. libmobi інтегрований через CLI `mobitool`.
4. Linux `mobitool` 0.12 зібраний і перевірений.
5. Windows build path для `mobitool.exe` через CMake + MSVC.
6. `prepare-runtime-tools.ps1/.sh`.
7. `smoke-runtime-tools.ps1/.sh`.
8. jpackage staging runtime tools.
9. jpackage runtime зберігає `java/java.exe`, необхідний для EPUBCheck JAR.
10. libmobi probe виправлено для реального output з build-banner + `libmobi: 0.12`.
11. EPUB optimizer failures класифікуються як `OUTPUT_INVALID`.
12. ConnectionScript HTTPS / secret-redaction fixes.
13. Runtime tool discovery враховує jpackage layouts.
14. Реальний Linux packaged smoke для EPUBCheck/libmobi був PASS.
15. Static release gates для цього checkpoint проходили після cleanup.

## Не вважати цей архів final release

Це development/audit handoff, а не фінальний реліз.
