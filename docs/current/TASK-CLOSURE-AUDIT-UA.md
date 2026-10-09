# MyHomeLib — аудит закриття попередніх завдань

Дата останньої повторної перевірки: **2026-10-02**  
Continuation source: V24 → V25 → **V26 strict re-audit**.  
Джерела: всі наявні `NEXT-TASKS` V7–V24 у `docs/history/records/`, `NEXT_WORK_TASKS_UA.md` та окремо наданий користувачем розширений V10 task contract (`...V10...USER-SUPPLIED.md`, SHA-256 `f0e33192a38208da4605a04e4addddeca7748a3d0d1dea0bf5c93a0b0ea9be2f`).

## Статуси

- `CLOSED` — вимога реалізована і має automated/local evidence.
- `CLOSED_NO_CHANGE` — аудит виконано; production change не виправданий без regression/A-B evidence.
- `SUPERSEDED` — старий gate замінений сильнішим наступним contract/evidence.
- `OPEN_WINDOWS` — потрібен реальний Windows x64 runtime.
- `OPEN_EXTERNAL` — потрібен connected GitHub/зовнішній сервіс/пристрій/credential.

## Консолідований результат

| Родина задач | Джерело | Статус V26 | Evidence / рішення |
|---|---|---|---|
| Correctness-first / regression-first guardrails | V7–V24 | CLOSED | Збережено як build/development contract; V26 production fixes мають вузькі red-before/green-after regression/acceptance tests |
| Full Maven/reactor verification | V7, V10–V24 | CLOSED локально | фінальний V26 `clean verify` фіксується у `docs/release/CURRENT-VALIDATION.md` |
| GC/heap A/B | V8–V10 | CLOSED_NO_CHANGE | V9 production-scale A/B; збережено G1 + `-Xmx1024m`; пізніших unmeasured changes немає |
| Exact clone / structural dedup | V7–V10 | CLOSED_NO_CHANGE | literal cross-file clone gate вже 0 для великого порога; подальші extraction лише за responsibility evidence |
| Async ownership / stale completion | V10–V20 | CLOSED | generation/cancellation guards у Search/Reader/download/navigation; async audit gates; generic framework не вводився |
| Reachable UTF-16/surrogate boundaries | V8–V14 + extended V10 | CLOSED | regression fixes у Search/Reader/annotations/support bundle/portable restore/plugin metadata; V25 додатково закрив archive-entry surrogate boundary Organizer |
| HTTP resume / validator / truncated 206 | V6, V11–V14 | CLOSED | V14 short-body regression + publication integrity tests |
| Binary/archive safety / cumulative limits | V11–V20 | CLOSED | archive/path/security tests і static gates; V26 повторний audit не знайшов нового red regression |
| SQLite busy/concurrency boundaries | V11–V20 | CLOSED_NO_CHANGE | targeted audits не підтвердили новий defect; не введено generic retry framework без RED evidence |
| Plugin package/restart/security lifecycle | V11–V20 | CLOSED локально | plugin lifecycle/security contract tests; Windows installed lifecycle лишається platform acceptance |
| Organizer recursive mixed-library | extended V10, V11/V12 | CLOSED | recursive mixed fixture; FB2/ODT/PDF/MOBI/TXT + multi-book ZIP; actual Apply + deterministic plan + rollback acceptance |
| Organizer metadata coverage all recognized formats | extended V10, V11/V12 | CLOSED | `MetadataExtractionLevel`; every recognized extension must be classified; structured extraction там, де container це підтримує; filename-only позначено явно без вигадування metadata |
| Organizer collision/rerun/non-destructive | extended V10 | CLOSED | Plan/Apply tests: collision resolver, NOOP rerun, exact/fuzzy duplicate policy, fail-closed publication/rollback |
| Converter public filename без UUID | extended V10, V11/V12 | CLOSED | `ConvertBookUseCaseTest`: plain title, `(2)` collision, no artifactId leak, blank-title fallback, extension correctness |
| 100% UI action inventory | extended V10, V11/V12, V24 | CLOSED | V26: 447/447 mapped; 262 FXML + 185 programmatic; 0 unmapped |
| UI action behavioral mapping | extended V10 | CLOSED in V26 | stricter registry: **446 automated action-specific + 1 justified exclusion = 447/447**; generic controller-level evidence більше не достатнє; fingerprint/evidence drift fail-closed |
| User-facing functions description | extended V10, V11/V12, V24 | CLOSED | `docs/current/USER-FUNCTIONS-UA.md`, format/operation matrix, external dependency matrix, coverage links |
| FXML/help/action completeness | V14–V24 | CLOSED | 29 FXML, no missing handlers; 14/14 CoreActions; help routing; UI action registry gate |
| Context-sensitive F1 help | V24 | CLOSED | `HelpTopicRegistryTest`; annotations/operations topics UK/EN/BG |
| Large-class decomposition | V7–V20 | CLOSED_NO_CHANGE | boundary-by-boundary policy; completed proven extractions retained; V26 architecture audit не виявив підстав для масового rewrite |
| Architecture module coverage | historical architecture debt | CLOSED in V25 | checker/ArchUnit тепер охоплюють усі 17 production/runtime modules; direct dependency declarations ratcheted |
| Native File/Directory chooser ownership | V25–V26 audit | CLOSED | V26 централізував усі native `FileChooser`/`DirectoryChooser` у `FileChooserService`; controllers/services використовують boundary; static/regression tests PASS |
| Release/source-tree cleanliness | V19–V26 | CLOSED локально | root docs normalized, history/current/release split, package exclusions; V26 fixed build-check self-mutation (`__pycache__`) and static source metrics contamination by `target/generated-sources`; both have regression contracts |
| Windows real Calibre large FB2 | V15–V24 | OPEN_WINDOWS | Linux не може виконати supplied Windows `ebook-convert.exe`; обов'язковий real process/file-lock acceptance |
| Windows batch conversion / coexistence | V22–V24 | OPEN_WINDOWS | потрібне спостереження real external processes, cancel/partial failure/reader coexistence |
| Windows installer/portable/DPI | V7–V24 | OPEN_WINDOWS | host/session-bound platform acceptance не замінюється Xvfb |
| libmobi/Calibre executable provenance/runtime | V19–V24 | OPEN_WINDOWS / OPEN_EXTERNAL | source/release policy готова; версії/provenance exact release bundle мають бути підтверджені на Windows/release input |
| Connected GitHub / SBOM / SCA / CodeQL / signing evidence | V17–V24 | OPEN_EXTERNAL | readiness/regression tooling локально green і синхронізоване з exact-current V26 validation (1960/18); exact-candidate connected evidence MHL-010/017/018/019 не може бути вигадане локально |
| Windows host/session MHL-011/012 | V17–V24 | OPEN_WINDOWS | потрібна bound Windows host/user/session evidence |
| Device/eject/external readers/system TTS/FFmpeg | V24 | OPEN_WINDOWS / OPEN_EXTERNAL | automated contracts є, physical/system integration потребує реального середовища |
| Live WebDAV/metadata/translation/AI/OPDS-MCP clients | V24 | OPEN_EXTERNAL | protocol/contracts автоматизовані; live credentials/endpoints/client interoperability окремі acceptance gates |
| Performance continuation після V9 | V10–V24 | CLOSED_NO_CHANGE | жодної production tuning-зміни без same-environment A/B; нової A/B-підстави V26 не виявив |

## Висновок

Після V26 **локальний source/automated backlog із наявних task-файлів закритий** або свідомо завершений без production change через відсутність red regression/A-B evidence. Незакритий залишок складається лише з Windows/live/connected acceptance, для якого поточне Linux/Xvfb середовище не може створити правдивий evidence.

## Source-by-source review історичних backlog-файлів

Ця таблиця фіксує, що під час V25 і повторно V26 був переглянутий **кожний наявний** handoff/task document, а не лише останній список.

| Task source | Локальний результат V26 | Залишок |
|---|---|---|
| `NEXT_WORK_TASKS_UA.md` | regression guards, real 707k INPX probe, release/tooling та architecture review виконані; broad MainController/clone rewrite не виправданий без окремого defect | Windows Calibre/startup/platform acceptance |
| `NEXT-TASKS-V7` | checkpoint/build/static, memory baseline, dedup/structure/correctness continuation поглинуті сильнішими пізнішими gates | Windows/package external evidence |
| `NEXT-TASKS-V8` | G1/Serial + heap A/B завершено в V9; structural/correctness review продовжено наступними versions | platform evidence |
| `NEXT-TASKS-V9` | performance decision G1 + 1024 MiB збережено; clone/correctness/decomposition audited | Windows acceptance |
| historical `NEXT-TASKS-V10` | async/correctness/decomposition continuation закриті або CLOSED_NO_CHANGE за evidence policy | Windows acceptance |
| **user-supplied extended V10** | Organizer recursive/mixed metadata coverage, converter naming, 447-action UI registry, deterministic user-function documentation mapping, async/correctness/performance guardrails закриті локально | P0 Windows release coverage |
| `NEXT-TASKS-V11` | consolidated Maven, Unicode/archive/async/Organizer/converter/UI-doc contracts поглинуті та перевірені V12–V26 | Windows acceptance |
| `NEXT-TASKS-V12` | Unicode/offset, archive bounds, HTTP resume/publication, persistence/async, Organizer, converter/UI-doc closure verified | Windows acceptance |
| `NEXT-TASKS-V14` | Maven unblock і defect-first audits завершені; architecture debt ratcheted | Windows acceptance |
| `NEXT-TASKS-V15` | release gate, cancellation UX і operation compatibility закриті тестами/подальшими checkpoints | real Windows conversion |
| `NEXT-TASKS-V16` | reactor/release baseline та staged decomposition audited; no speculative rewrite | Windows conversion |
| `NEXT-TASKS-V17` | architecture/package regressions локально закриті; connected evidence tooling готове | MHL external gates |
| `NEXT-TASKS-V18` | cancellation/process contracts і packaging локально закриті | real Windows Calibre |
| `NEXT-TASKS-V19` | libmobi/release-input policy та maintenance tooling локально перевірені | exact release provenance/runtime |
| `NEXT-TASKS-V20` | remaining correctness/architecture/packaging audit завершений | Windows/tool provenance |
| `NEXT-TASKS-V21` | source local gate green; supplied libmobi/Calibre policy збережена | Windows runtime acceptance |
| `NEXT-TASKS-V22` | V22 conversion/batch semantics збережені; Linux-side regressions green | real Windows large-FB2 + batch/coexistence |
| `NEXT-TASKS-V23` | audit/CI/source-package issues закриті; environment contract задокументовано | real Windows + connected closure |
| `NEXT-TASKS-V24` | full functional inventory/documentation/coverage доведено до stricter fail-closed V26 action-specific contract | Windows/live/connected acceptance |

Розширений V10, наданий користувачем у цій сесії, збережено байт-в-байт у `docs/history/records/audit-checkpoints-2026-09-29/`; SHA-256 обох копій: `f0e33192a38208da4605a04e4addddeca7748a3d0d1dea0bf5c93a0b0ea9be2f`.

## Уточнення щодо «100% UI coverage»

Локальний V26 доказ означає **100% inventory + action-specific behavioral evidence**, але не підміняє реальні external/Windows endpoints. Registry має 446 automated action-specific entries і 1 justified `Platform.exit()` exclusion; кожна automated entry має observable assertion/Mockito-backed evidence та implementation fingerprint. Live/Windows/system-dependent actions залишаються `OPEN_WINDOWS`/`OPEN_EXTERNAL`, тому structural/delegate evidence не видається за platform acceptance.

Документаційний acceptance із extended V10 тепер також формально закритий: `tools/user-functions-documentation-check.py` вимагає, щоб **447/447** JavaFX actions мали валідний `user_function_ref` на канонічний flow у `docs/current/USER-FUNCTIONS-UA.md`; цей check входить у `build-check-v7.py`.

## Повторна перевірка ранніх iteration/task документів у V26

V26 не обмежився `NEXT-TASKS V7+`. Повторно виконані наявні deterministic regression-gates для ранніх feature iterations: Iteration 39 text providers, 40 PDF closure, 45 archive efficiency, 46 bounded archive streaming, 47 comics, 48 TTS/accessibility, 49 content search/index, 50 indexing queue, 51 search-everywhere/health, 52 sync foundation, 55 sync conflict/key rotation, а також Stage 10–22 і 34–37 (details, collection maintenance, actions, export, OPDS, Reader, help/localization, portable user data, artifact health, annotations). **Усі PASS.**

Iteration/task документи 29, 33–42, 44–53 та Iteration 60 залишаються історичними source-of-requirements. Їхні product contracts присутні в current code/tests; зовнішня частина Iteration 42/подальших release-evidence вимог лишається `OPEN_EXTERNAL`, а не маскується локальними тестами.

Корекція історичного формулювання: V25 `447/447 mapped` не слід трактувати як достатній доказ кожної concrete UI action. V26 навмисно посилив критерій і на RED-випадку виявив, що `BookDetailsController.onEdit` був фактичним no-op. Після regression-first fix цей action входить до 446 automated action-specific entries.


## Deep audit 2026-10-02

Повторна source-tree перевірка перед зовнішнім acceptance підтвердила закриття product scope V30–V34 і додатково виявила дві release-quality прогалини: unused import у Kobo contract та відсутність `myhomelib-updater` у architecture registries. Обидві виправлено; architecture coverage тепер 17/17. Єдиний exact cross-module method clone усунуто через Shared JSON escaping primitive. Історичні root-level checkpoint status файли перенесено до `docs/history/records/checkpoints/`. Новий hygiene gate не дозволяє повернення таких файлів у root, broken local Markdown links, duplicate Java FQCN або cache/build trash у source package.
