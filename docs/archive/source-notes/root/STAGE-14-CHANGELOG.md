# Stage 14 Changelog — ActionRegistry + configurable hotkeys

Date: 2026-08-25

## Implemented

- Added centralized JavaFX `ActionRegistry` with stable command IDs, localized/display titles, default shortcuts, persisted current shortcuts, visibility flags, context predicates and handlers.
- Migrated the main desktop shortcuts away from the hardcoded `MainController` key-event filter: Back, Forward, F1 help, search focus, refresh, internal/external reader, collection manager, INPX import, export and settings.
- Added persisted `ActionPreference` state through application-level `ActionSettingsService`; UI does not access the settings output port directly.
- Added conflict and syntax validation using JavaFX `KeyCombination` before preferences are saved.
- Added command-customization dialog with shortcut editing, visibility toggles and reset-to-defaults behavior.
- Main menu items are wired through the registry while existing controller methods remain the behavior handlers.
- Context-sensitive commands are disabled when their preconditions are false; navigation actions follow the actual back/forward history state.

## Compatibility

- Existing FXML handlers remain valid.
- No new UI -> infrastructure/output-port dependency was introduced.
- Existing default keyboard behavior is preserved unless the user customizes it.
