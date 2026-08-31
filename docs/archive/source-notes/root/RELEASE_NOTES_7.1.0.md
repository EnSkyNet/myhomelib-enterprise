# MyHomeLib Enterprise 7.1.0 — Release Notes

v7.1 focuses on upstream online-library compatibility, safe downloads, large-catalog performance and truthful release validation.

Highlights:

- MyHomeLib-compatible declarative `ConnectionScript` (`GET/POST/ADD/CHECK/REDIR/PAUSE`) with deterministic macros and no dynamic code execution;
- `collection.info` import/export and explicit trust policy preserving local credentials/settings during manual update;
- validated/atomic online book downloads, force-refresh correctness, validator-bound Range resume, archive download deduplication and a persistent credential-free queue;
- centralized HTTP proxy/TLS policy, encrypted proxy/custom-trust-store secrets and no trust-all mode;
- stricter INPX auxiliary-member/`extra.inp` behavior;
- stronger metabib dataset validation with provenance/relations/artifact-occurrence foundations;
- versioned manifest/search fingerprints and selective Lucene indexing;
- keyset/bounded Lucene traversal and rebuild telemetry;
- application-layer progress counters with byte telemetry, cancellable throttled JavaFX progress UI and richer update summary;
- statistics error state no longer masquerades as a real zero; duplicate/missing-cover counts are real, and local download/removal invalidates the aggregate cache;
- opt-in high-reliability ZIP-family validation checks duplicate entries, full-entry size/CRC and FB2 payload validity after download;
- additive V37–V40 + metadata V4–V5 migrations with V1–V36 baseline immutability checks;
- JDK 21 GitHub Actions matrix and scheduled performance workflow.

The source tree passes the available offline release gates. A connected `./mvnw clean verify -Pproduction` and real GitHub Actions Ubuntu/Windows/macOS run remain mandatory before calling the release fully validated.
