# MyHomeLib — наступні задачі після R18

**Дата:** 2026-10-05
**Фокус:** тільки live/platform acceptance точного R18 candidate. Новий product scope не відкривати до завершення P0.

## P0 — Windows live retest дефектів R16/R17/R18

На fresh portable/packaged R18 в одному сеансі:

1. Відкрити supplied 18-book corpus; для фізично локальних ZIP не повинно бути повторного HTTP materialization, включно з `729586` після restart.
2. Запустити full search-index rebuild і одразу remote-only download; DOWNLOAD не повинен чекати завершення INDEX.
3. Швидко пройти вперед/назад книги; поточна cover має відновлюватися, а decode/archive identity failure повинен мати явний `COVER_*` log evidence.
4. Після author/background navigation запустити `729586` FB2.ZIP → EPUB; passive QUERY не блокує CONVERSION; default AUTO route використовує bundled FBC; output відкривається/валідується.
5. Перезапустити app: source artifact лишається primary, EPUB secondary; EPUB Book Details/Reader працює.
6. Штучно зайняти Lucene `write.lock`: бібліотека відкривається у degraded-search mode; після release lock ручний rebuild повертає search без перезапуску даних/колекції.
7. Закрити app після rapid navigation/download/conversion; немає stuck lifecycle lease, orphan converter/network work або burst user-visible cancellation ERROR.

## P0 — native Windows package/update

- clean installer + portable startup;
- packaged runtime FBC smoke: FB2 → EPUB + EPUBCheck;
- update/stage/restart/helper replacement/health marker;
- forced failed-start rollback;
- previous-version upgrade;
- signature/platform/downgrade rejection;
- Windows DPAPI;
- DPI 100/125/150/200% screenshot-backed acceptance.

## P0 — physical Kobo, якщо Kobo входить у реліз

First sync, add/update/remove, cover/metadata, reconnect, bidirectional progress, credential revoke/rotation, repository failure behavior без false «not found».

## P1 — real providers/browser/content

- Readwise real account: auth, dedup/update, rate limit, timeout/partial failure, idempotent retry;
- PWA supported browser: install, offline shell, explicitly saved book offline, reconnect replay, auth/token revoke;
- real large CBR/CB7 corpus;
- real WebDAV conflict/credential rotation/clear failure;
- system TTS voices + provider fallback; FFmpeg if enabled;
- configured external reader executable.

## P1 — target-machine soak

1–2 години на реальній Windows машині з великою бібліотекою: search/navigation/Reader/download/conversion/themes/languages/restart. Збирати working set, heap, handles, threads, GC, temp files та orphan processes.

## Release-only connected gates

Перед Windows package job provision authoritative converter bundle (`tools.zip`/equivalent) з FBC; clean hosted checkout не містить цей зовнішній payload. Не послаблювати `MHL_REQUIRE_FBC=1`.

Для exact frozen candidate: GitHub required checks, CycloneDX SBOM + digest, SCA/Dependency-Check, CodeQL, Authenticode/signing, SHA256SUMS, immutable reviewer/evidence bundle.

## Не відкривати без нового evidence

- новий memory/cache/pool tuning;
- нову Lucene orchestration model;
- cosmetic large refactor;
- нові integrations/features.

R18 local code/static/full-regression evidence вже green; наступний ризик — не відома локальна code regression, а реальна Windows/device/provider boundary.
