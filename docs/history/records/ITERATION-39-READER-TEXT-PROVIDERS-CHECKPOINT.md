# Iteration 39 checkpoint — Reader text providers

**Date:** 2026-09-11  
**Base:** Iteration 38 source  
**Scope:** MHL-210 + MHL-211

## Implementation status before final tests

### MHL-210 — DictionaryProvider SPI
- Application contracts added: query, entry, provider SPI, descriptor, lookup result/service.
- Lookup service is offline-first by default, supports explicit provider selection, deadline/cancellation and sanitized issue mapping.
- `LocalDictionaryProvider` reads bounded UTF-8 TSV from `dictionary.local.path` / config `dictionary.tsv`, then bundled starter data.
- Reader selection context menu exposes Dictionary through a renderer-neutral callback.
- JavaFX controller performs provider choice and async guarded result presentation through application service only.

### MHL-211 — TranslationProvider SPI
- Application contracts added: query, result, provider SPI, descriptor, translation result/service.
- Service supports explicit provider switch, offline-first default, timeout/cancellation and attribution validation.
- `LocalPhraseTranslationProvider` provides offline exact-phrase lookup.
- DeepL, Google Cloud Translation and generic custom HTTPS adapters are opt-in and disabled by default.
- Remote credentials come from runtime property/env or authenticated encrypted settings; persisted plaintext secrets are rejected.
- Reader Translate action is explicit. Remote providers require a privacy confirmation immediately before the translation flow invokes the application service.
- Active dictionary/translation requests are cooperatively cancelled on replacement/dispose; stale completions are rejected by both request identity and current-book guard.

### Localization
New Reader text-provider keys are synchronized in root and bundled UK/EN/BG catalogs.

### Test source added, not yet executed
- `TextProviderRequestContextTest`
- `DictionaryLookupServiceTest`
- `TranslationServiceTest`
- `LocalDictionaryProviderTest`
- `LocalPhraseTranslationProviderTest`
- `TranslationHttpProviderTest`
- `TextProviderHttpSupportTest`
- `ReaderTextProviderUiContractTest`

## Pre-test rule
No compile/test command is run until implementation, documentation, localization, acceptance-test source and manual review are complete.

## Iteration 38 dependency status carried forward
MHL-206 remains acceptance-blocked because the supplied offline repository does not contain PDFBox 3.0.8. No PDFBox or Maven binary is added to the source release. MHL-207 remains gated.

## Manual review / freeze

Completed before the first compile/test run:
- Reader callback files contain no application/infrastructure/Spring/JDBC dependency.
- JavaFX workspace imports application dictionary/translation services only; it does not import concrete infrastructure providers.
- Translation has no selection-change observer or automatic invocation path. Remote confirmation is shown after provider/language choice and immediately before creating/invoking the translation request.
- Active dictionary/translation request cancellation is wired into both book replacement and workspace dispose; completion additionally checks request identity and `sameOpenBook`.
- DeepL/Google/custom providers are remote and disabled by default; custom provider is considered enabled only with a valid HTTPS endpoint.
- API keys/tokens are never placed in URLs; persisted plaintext credentials are rejected by `TextProviderHttpSupport`.
- Bundled dictionary/translation TSV resources were manually inspected and corrected to use real TAB delimiters (not literal `\\t` sequences).
- No TODO/FIXME markers or embedded production credentials remain in the Iteration 39 implementation paths.
- `ITERATION-39-CHANGED-FILES.txt` records 47 changed/new source/doc/test files relative to the Maven-free Iteration 38 release baseline (35 added, 12 modified, 0 deleted; inventory file itself excluded from that count).

**Freeze rule:** from this point, functional source changes are complete. The next actions are validation only; if a gate exposes a concrete defect, only that defect is corrected and its affected gates are rerun.
