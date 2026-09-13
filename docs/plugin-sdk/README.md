# MyHomeLib Plugin SDK 1.x

This guide is the supported starting point for third-party MyHomeLib plugins. The public Java contract lives in
`myhomelib-plugin-api`; the runnable reference implementations live in `myhomelib-plugin-samples`.

## 1. Depend only on the Plugin API

Use the same MyHomeLib release version as the host:

```xml
<dependency>
  <groupId>com.myhomelibcorp</groupId>
  <artifactId>myhomelib-plugin-api</artifactId>
  <version>${myhomelib.version}</version>
</dependency>
```

Target Java 21. Do not depend on `myhomelib-infrastructure`, `myhomelib-ui`, `myhomelib-bootstrap`, database classes,
or Lucene internals. Those are host implementation details rather than plugin contracts.

## 2. Implement one or more stable services

A plugin JAR exposes a `PluginEntrypoint` with an immutable `PluginManifest` and one implementation for every declared
`PluginService`. The current stable service identifiers cover metadata, covers, import, metadata extraction, content
extraction, export, translation, dictionary, devices and optional AI.

The sample module contains three independent entrypoints:

- `SampleDictionaryPlugin` — offline dictionary, no capability required;
- `SampleMetadataPlugin` — metadata provider declaring `NETWORK_ACCESS`;
- `SampleExportPlugin` — export provider declaring `FILESYSTEM_READ` + `FILESYSTEM_WRITE`.

For clarity, one plugin per distributable JAR is recommended even though a classpath can expose multiple entrypoints.

## 3. Register the entrypoint

Add `META-INF/services/com.myhomelibcorp.plugin.api.PluginEntrypoint` to the plugin JAR and place the fully-qualified
entrypoint class name on its own line. MyHomeLib uses the JDK `ServiceLoader` contract for trusted-classpath discovery.

Discovery does not mean activation. The host still checks API compatibility, exact service bindings, explicit core
service overrides, trust and permissions before enable.

## 4. Declare permissions up front

Available host-visible capabilities are:

- `NETWORK_ACCESS`
- `FILESYSTEM_READ`
- `FILESYSTEM_WRITE`

Permissions are part of the manifest and must exactly match the host approval for that plugin **id + version**. A new
plugin version does not inherit trust automatically. `UNTRUSTED` code is not run in-process.

These declarations are host approval/capability gates, not an OS/JVM sandbox. Trusted in-process Java code can call JDK
APIs directly. Strong isolation of untrusted code requires a separate out-of-process host.

## 5. Validate the JAR in CI

The SDK exposes `PluginTestHarness` so plugin projects can run the same structural validation as the host loader:

```java
@Test
void pluginContractIsValid() {
    PluginTestHarness.verify(new MyPlugin());
}
```

For a built test classpath with a ServiceLoader descriptor:

```java
@Test
void everyEntrypointIsValid() {
    PluginTestHarness.verifyServiceLoader(getClass().getClassLoader());
}
```

The harness uses a synthetic exact-version trusted approval **only inside the developer test**. Passing the harness does
not grant runtime trust or bypass the host approval screen.

## 6. Compatibility rules

- Declare an inclusive `PluginApiRange`; `PluginApiRange.currentMajor()` accepts compatible additions within Plugin API 1.x.
- Never silently replace a host/core service. If a plugin intentionally overrides one, list it in
  `overridesCoreServices`; the host may still refuse the override.
- `services().keySet()` must exactly equal the services declared in the manifest.
- Each service implementation must implement the contract represented by its `PluginService`.
- Declare all required network/filesystem capabilities before enable.
- Treat cancellation/deadline/progress contexts as part of the contract for long-running provider operations.
- Do not persist host internals or assume implementation classes are stable across releases.

See `COMPATIBILITY.md` for the versioning policy and the `myhomelib-plugin-samples` module for compilable examples.


## 7. Optional AI providers (Plugin API 1.3)

`PluginService.AI_PROVIDER` is an additive 1.x service. Implement `AiProvider` and declare a stable capability set before activation. A provider that returns `networkRequired=true` must also declare `NETWORK_ACCESS`; the host loader rejects the binding otherwise.

AI providers do **not** own privacy consent. The host creates `AiProviderContext` only after persistent per-book opt-in and per-invocation consent checks. Book content is shared only when the user explicitly allows it, and network-backed execution additionally requires explicit network consent. Providers can request only secret names listed in `AiProviderCapabilities.requiredSecrets()`; the host resolves those names through its `SecretStore` namespace and never exposes arbitrary application secrets.

Do not persist API keys/tokens in plugin settings, logs, Markdown or model prompts. Do not assume a provider is enabled merely because ServiceLoader discovers it; runtime trust/permission approval and host consent remain mandatory.
