# Integrations and Kobo Sync

Integration Center groups external MyHomeLib integrations. The **Kobo Sync** tab provides native synchronization for a compatible Kobo through the OPDS sidecar; it is separate from USB Export / Send to Device.

## Kobo Sync

1. Enable Kobo Sync. MyHomeLib creates an opaque device ID and displays the credential once. The secret is stored only through the operating-system SecretStore and is not readable from the database or plain configuration.
2. Point the Kobo to the endpoint exposed by your MyHomeLib server. Existing TLS/OPDS network rules still apply.
3. **Prepare synchronization** refreshes the local projection/cursor. The device pulls incremental changes during its own sync.
4. EPUB/KEPUB content is served directly. If conversion is required, MyHomeLib reuses the existing converter toolchain and converts only when the Kobo actually downloads the book.
5. Reading progress synchronizes in both directions. An older update cannot replace a newer canonical version.
6. **Revoke device** disables access and removes the credential from SecretStore.

Live interoperability, first sync, opening/reading, cover/metadata, reconnect and revoke must be validated separately on a real Kobo.

## Readwise
Integration Center → Readwise stores the token through the secure secret store. You can test the connection, export all annotations and clear the token. Use Annotation Manager to export books from the current selection/filter.

