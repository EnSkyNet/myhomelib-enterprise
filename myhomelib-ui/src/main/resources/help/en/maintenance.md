# Collection maintenance

Automatic update watches the configured INPX/ZIP source and uses a stabilization delay plus a state fingerprint, so bulk file changes do not create a storm of duplicate events.

Cleanup/repair always follows **Analyze → Dry-run preview → Apply → Report**. A backup is created before database repair. Orphan files are not deleted automatically.
