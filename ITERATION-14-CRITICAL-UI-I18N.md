# Iteration 14 — Critical UI i18n hardening

Date: 2026-09-06  
Backlog: **MHL-016**  
Scope: `myhomelib-ui`, `myhomelib-reader`, `Lang`, PR CI

## Goal

Remove critical user-facing Ukrainian literals from the Search, Reader, Import, OPDS and Backup programmatic JavaFX paths and replace them with stable localization keys backed by synchronized Ukrainian, English and Bulgarian catalogues.

## Backlog acceptance mapping

The source backlog requires:

1. UK/EN/BG have the same critical keys.
2. Critical screens contain no user-facing Cyrillic literals outside a deliberate allowlist.
3. A static localization gate prevents regression.
4. UI smoke/regression remains green.

Implementation result:

- critical programmatic text resolves via `LocalizationService.text(key)` / `format(key, args)`;
- **337 stable keys** are currently referenced by **14 guarded source files**;
- all 337 keys exist and are nonblank in UK/EN/BG;
- root `Lang/{uk,en,bg}.json` and packaged `myhomelib-ui/.../lang/default/{uk,en,bg}.json` are byte-equivalent after generation;
- `%` placeholder signatures are checked across all three built-in languages;
- `tools/check-critical-ui-localization.py` rejects user-facing Cyrillic literals and legacy `tr(...)` calls in the guarded sources;
- a broader package audit found **0 unexpected Cyrillic user-facing literals** in critical Search/Reader/Import/OPDS/Backup packages. The only remaining Cyrillic string in that broad scan is `AgЙц` in `JavaFxFontMetricsProvider`, intentionally used as a font-metrics sample and never displayed as UI copy.

## Main code changes

### Stable-key API

`LocalizationService` now separates:

- `text(key)` — stable key lookup;
- `format(key, args)` — deterministic `Locale.ROOT` formatting;
- `tr(sourceText)` — retained only as an incremental legacy/FXML compatibility path outside the migrated critical surfaces.

### Search

Search status, paging, filter indicator, select-all tooltip, save-search dialogs and saved-search errors use `ui.search.*` / `common.*` keys.

### Backup

Backup folder selection, confirmations, progress/status, diagnostics and completion/error messages use `ui.backup.*` keys with stable format placeholders.

### Import

Import chooser titles/filters, progress telemetry, operation stages, settings, result summaries and presenter status messages use `ui.import.*`. File chooser filters are generated with localized display labels without restoring hard-coded extension matrices.

### OPDS

Server controls, TLS certificate workflow, exposure warnings, fingerprint/trust guidance and runtime status messages use `ui.opds.*`.

### Reader

Reader toolbar/status, settings, semantic element labels, themes, bookmarks, TOC, search and open/progress/error states use `ui.reader.*`.

A second broad audit found that `NewReaderPersistenceService` could still leak Ukrainian exception text through `rootMessage()` into dialogs and could persist a Ukrainian fallback chapter title. It is now part of the static gate and uses stable keys for those messages/fallbacks as well.

## Catalogue result

Each built-in catalogue now contains **540 UI translation entries** and **335 genre entries**. This includes both existing legacy source-text entries and the new stable-key contract; legacy entries are not deleted in this iteration because non-critical FXML/source-text surfaces still use them.

## CI regression barrier

`ci-pr.yml` includes:

```bash
python3 tools/validate-language-catalogs.py
python3 tools/check-critical-ui-localization.py
```

The critical gate validates source literals, stable-key completeness, root/bundled equality and format signatures.

## Verification

All results below were executed offline against the supplied Maven repository.

- critical UI localization gate: **PASS — 337 keys / 14 files**;
- language catalogue validator: **PASS — UK/EN/BG, 540 UI keys + 335 genre keys each**;
- broad critical-package literal audit: **0 unexpected**, 1 intentional font-metrics sample;
- non-headless UI regression: **43/43 PASS**;
- fast core: application **127 tests: 126 PASS, 1 SKIP**, OPDS **14/14 PASS**;
- migration/security/concurrency/SecretStore: **33/33 PASS**;
- ArchUnit: **12/12 PASS**;
- E2E journeys: **10/10 PASS**;
- XML/archive, managed-executor, supply-chain, privacy/temp, SecretStore and source architecture policy checks: **PASS**;
- full Maven `test-compile`: **13/13 modules — BUILD SUCCESS**;
- full Maven `package -DskipTests`: **13/13 modules — BUILD SUCCESS**.

Two previously known JavaFX environment-dependent tests (`MainLayoutServiceFxTest`, `MainToolbarWrapFxTest`) remain outside the non-headless Linux regression set; they were not reclassified as passing.

## Files of interest

- `tools/check-critical-ui-localization.py`
- `.github/workflows/ci-pr.yml`
- `Lang/uk.json`, `Lang/en.json`, `Lang/bg.json`
- `myhomelib-ui/src/main/resources/lang/default/{uk,en,bg}.json`
- `myhomelib-ui/.../LocalizationService.java`
- critical Search/Backup/Import/OPDS/Reader controllers/services
- `myhomelib-reader/.../ReaderToolbar.java`
- `myhomelib-reader/.../ReaderStatusBar.java`

## Known boundary

MHL-016 covers the backlog-defined critical screens. The rest of the application may still contain legacy `tr(sourceText)` or non-critical source-text localization; this iteration intentionally adds a ratchet around the release-critical surfaces rather than mechanically rewriting every historical label at once.
