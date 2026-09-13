# Plugin API compatibility policy

## Version model

The public plugin contract uses `PluginApiVersion(major, minor)` and a plugin declares an inclusive `PluginApiRange`.
The host rejects a plugin before activation when the current host API is outside that range.

Plugin API **1.3** is the current contract in Iteration 71. Changes from 1.2 are additive: the optional `AI_PROVIDER` service, provider-neutral AI request/capability types and host-owned consent/secret boundary were added without removing or changing existing 1.x service methods.

## Expected compatibility

- A **minor** Plugin API update may add new optional types, helpers or service identifiers without breaking existing 1.x
  plugins that declare a compatible range.
- A **major** Plugin API update may contain breaking contract changes. A 1.x plugin must not assume compatibility with 2.x.
- Plugin trust/approval is bound to the exact plugin id and plugin version. Updating a plugin therefore requires a fresh
  host approval even when its API range remains compatible.
- Manifest permissions are exact: the approved permission set must equal the requested set at activation time.
- Service declarations and actual bindings are exact; undeclared or missing services are rejected.

## CI recommendation

Compile against the oldest Plugin API minor you intend to support, set an explicit range, and run
`PluginTestHarness.verify(...)` or `verifyServiceLoader(...)` in CI. Also test real behavior of your provider; the harness
validates the host contract, not domain correctness, network availability, performance or security of arbitrary plugin
code.

## Security boundary

The Plugin API does not claim in-process Java sandboxing. Permissions make requested capabilities visible and enforce
host-controlled call gates, while `UNTRUSTED` plugins are blocked from in-process execution. Strong isolation requires a
future process boundary.
