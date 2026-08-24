# Architecture Debt Baseline — Stage 2

Date: 24.08.2026

This file records architecture debt that already existed when the hard module
baseline was established. It is not an allow-list for new code. The offline
checker uses a **ratchet** policy: deleting an item is allowed; introducing a
new violating class fails validation.

## 1. UI classes that directly use application output ports

Baseline: **18 classes**.

- `ui.controller.CollectionWizardController`
- `ui.controller.GroupController`
- `ui.presenter.CoverPresenter`
- `ui.reader.NewReaderPersistenceService`
- `ui.reader.NewReaderWorkspaceController`
- `ui.service.ApplicationSettingsDialog`
- `ui.service.BookDownloadCoordinator`
- `ui.service.ClassicLibraryActionsService`
- `ui.service.CollectionAttachUiService`
- `ui.service.CollectionCopyUiService`
- `ui.service.CollectionPropertiesUiService`
- `ui.service.CollectionUpdateUiService`
- `ui.service.DefaultNavigationService`
- `ui.service.ExternalBookLauncher`
- `ui.service.LocalizationService`
- `ui.service.SupportBundleService`
- `ui.service.UserDataUiService`
- `ui.table.TreeBookTableController`

### Reduction rule

When a feature is touched, prefer:

```text
JavaFX controller/service
        -> application use case/query/input API
        -> application output port
        -> infrastructure adapter
```

rather than UI injecting the output port directly.

## 2. UI classes that use non-value domain model types

Baseline: **28 classes**.

- `ui.collection.CollectionWorkspaceController`
- `ui.controller.BackupController`
- `ui.controller.CollectionController`
- `ui.controller.CollectionWizardController`
- `ui.controller.DatabaseToolsController`
- `ui.controller.GroupController`
- `ui.controller.ImportController`
- `ui.controller.MainController`
- `ui.controller.SavedSearchesController`
- `ui.event.CollectionChangedEvent`
- `ui.group.GroupWorkspaceController`
- `ui.navigation.WorkspaceManager`
- `ui.presenter.CollectionPresenter`
- `ui.presenter.GroupPresenter`
- `ui.reader.NewReaderPersistenceService`
- `ui.reader.NewReaderWorkspaceController`
- `ui.reader.ReaderSettingsMapper`
- `ui.service.BookDownloadCoordinator`
- `ui.service.ClassicLibraryActionsService`
- `ui.service.CollectionCopyUiService`
- `ui.service.CollectionPropertiesUiService`
- `ui.service.CollectionUpdateUiService`
- `ui.service.DefaultNavigationService`
- `ui.service.DialogService`
- `ui.service.FxmlLoaderFactory`
- `ui.table.TreeBookTableController`
- `ui.viewmodel.ApplicationState`
- `ui.viewmodel.CollectionWizardViewModel`

Domain value objects such as `BookId`, `AuthorId`, `SeriesId`, `GenreId` and
`LanguageCode` are not counted in this debt category.

### Reduction rule

For screen-facing data prefer application DTO/view projections. Keep domain
entities behind application services unless the domain object itself is the
explicit API contract and changing it is justified.

### Stage 2 reduction

`NavigationPanelController` was moved behind the application-level
`NavigationQueryService`/`NavigationNodeDto` boundary. It no longer injects
application output ports and no longer renders `Author`, `Series` or `Genre`
domain entities directly. The ratchet ceiling therefore moved from 19/29 to
18/28.

## 3. Other tracked debt

- `MyHomeLibApp` performs more startup orchestration than an ideal composition
  root; future lifecycle services can reduce it.
- Reader portable and JavaFX code share one Maven module, although package
  isolation is already enforced.
- The MCP sidecar has its own direct SQLite/archive implementation rather than
  sharing the desktop application layer; this is intentional for now and must
  not be changed accidentally by importing desktop modules.

## 4. How the ratchet works

Run:

```bash
python3 tools/architecture-check.py
```

If one of the above dependencies is removed, validation still passes. If a new
class is added to either debt category, validation fails. The baseline may only
be expanded by an explicit architecture decision accompanied by documentation,
not as a side effect of feature work.

## Stage 3 status

Year/language/archive navigation was implemented behind the existing
`NavigationQueryService` boundary. No JavaFX controller gained a direct output
port or non-value domain entity dependency.

Ratchet remains:

- UI direct output-port users: **18**
- UI non-value domain-model users: **28**

The new `NavigationFacetRepository` is consumed only by the application
navigation service and implemented in infrastructure.
