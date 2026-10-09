# MyHomeLib — Changes V15 → V16

Date: 2026-09-28
Baseline: V15 clean checkpoint.

## Modified source/test files

1. `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/reader/NewReaderWorkspaceController.java`
   - delegates sidebar full-text-search lifecycle to `ReaderSidebarSearchCoordinator`.
2. `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/search/SearchWorkspaceController.java`
   - delegates collection content-search lifecycle to `ContentSearchCoordinator`.
3. `myhomelib-bootstrap/src/main/java/com/myhomelibcorp/integration/DesktopIntegrationBackend.java`
   - delegates AI provider/configuration/secrets/execution responsibility to `DesktopAiIntegration`.
4. `myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/backup/VersionedUserDataTransferAdapter.java`
   - delegates global reader-preferences file lifecycle to `ReaderPreferencesTransferStore`.
5. `NewReaderSidebarSearchFxTest.java`
6. `ContentGlobalSearchUiContractTest.java`
7. `SearchWorkspaceControllerFxTest.java`

## Added source/test files

1. `ReaderSidebarSearchCoordinator.java`
2. `ContentSearchCoordinator.java`
3. `DesktopAiIntegration.java`
4. `DesktopIntegrationBackendAiCharacterizationTest.java`
5. `ReaderPreferencesTransferStore.java`

No production/test source file was deleted.

## Behavioral intent

These are responsibility extractions only. They preserve the established public UI/application/plugin contracts and keep existing cancellation, collection identity, lazy initialization, safe-file-publication and rollback semantics.

## Validation summary

- Reader module: 137 tests, 0 failures/errors, 1 skipped.
- UI module after search extraction: 226/226 PASS.
- Bootstrap module after AI extraction: 52 tests, 0 failures/errors, 2 skipped.
- Versioned user-data transfer characterization: 14/14 PASS before and after extraction.
- Full current-V16 infrastructure module: 637 tests, 0 failures/errors, 9 skipped — PASS.
- Full current-V16 20-module reactor: 1789 tests, 0 failures/errors, 17 skipped — BUILD SUCCESS under Maven 3.9.6 + Xvfb.
- Production package: BUILD SUCCESS.
- Linux jpackage app-image: PASS.
- Packaged launcher smoke: PASS.
- Stage23 release artifact validation: PASS.
- Static release check: PASS.
- Architecture debt: unchanged single known group.

## Maven version note

The supplied offline Maven distribution is 3.9.6. The project wrapper requires 3.9.16, so exact-version release acceptance remains pending rather than being silently downgraded.
