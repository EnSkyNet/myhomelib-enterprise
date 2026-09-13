# Iteration 71 — Provider-neutral AI extension point (MHL-510)

Date: 2026-09-13
Base: Iteration 70 / MHL-509

## Delivered

- Plugin API 1.3 adds the additive `AI_PROVIDER` service and public `AiProvider` facade.
- Provider-neutral operations: `SUMMARY` and `QUESTION_ANSWER`.
- Providers declare operations, network requirement and exact required secret names before invocation.
- `PluginLoader` rejects a network AI provider that does not declare `NETWORK_ACCESS`.
- Core/application ships with no AI provider enabled by default.
- `AiExtensionService` enforces persistent per-book opt-in; books default to opted out.
- Book-content sharing and network use require independent explicit per-invocation consent.
- Prompt/book-content/response sizes are bounded and cancellation/deadlines are host-owned.
- Providers cannot construct `AiProviderContext` outside the host package.
- Providers can read only capability-declared secret names through the execution context.
- Credentials are namespaced as `myhomelib.ai.<providerId>.<secretName>` and stored only through the existing `SecretStore` abstraction.
- If a required secure store is unavailable, execution fails closed rather than writing plaintext settings.
- Existing 1.x service contracts and legacy plugin manifest constructor remain intact.

## Validation evidence

- `AiExtensionServiceTest`: 7/7 PASS
- `AiProviderPluginContractTest`: 3/3 PASS
- `PluginSpiSurfaceTest`: 4/4 PASS
- full Plugin API: 28/28 PASS
- full Application: 284 tests, 0 failures, 0 errors, 1 skipped
- `LayerArchitectureTest`: 14/14 PASS
- `tools/architecture-check.py`: PASS
- `tools/implementation-completeness-check.py`: PASS
- `tools/check-critical-ui-localization.py`: PASS
- `tools/static_release_check.py`: PASS
- `tools/supply-chain-policy-check.py`: PASS

## Boundary

No model vendor, endpoint or credential is bundled or enabled by default. This iteration provides the host/plugin extension and privacy/security boundary only; it does not claim quality, availability or privacy properties of any future third-party provider. The six Windows/GitHub external acceptance gates remain unchanged.
