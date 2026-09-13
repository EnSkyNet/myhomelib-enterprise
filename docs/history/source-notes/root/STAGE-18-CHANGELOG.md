# Stage 18 Changelog — OPDS lifecycle UI

Date: 2026-08-25

## Implemented

- Added desktop OPDS lifecycle service with explicit start/stop/status handling outside JavaFX controllers.
- Added persisted OPDS settings for bind address, port, Basic Authentication and autostart.
- Default bind is `127.0.0.1:8088`.
- Added optional HTTP Basic Authentication.
- Added automatic startup/shutdown hooks through the desktop lifecycle component.
- Added OPDS management action to the centralized Stage-14 `ActionRegistry` and Main menu.
- Added explicit network-exposure warning when the configured bind address is not loopback.
- `/health` remains available for lifecycle monitoring even when Basic Authentication protects the OPDS catalog.
- Server implementation remains composed in bootstrap; JavaFX UI sees only the application `OpdsServerControl` abstraction.

## Security/UX decisions

- Localhost is the safe default.
- Exposing OPDS beyond localhost is explicit and visibly warned about; firewall/router configuration is not modified automatically.
- Authentication credentials are used only when Basic Auth is enabled.
