# MyHomeLib 8.0.0 — Dependency Upgrade Final Validation

Validation date: 2026-09-15

## Upgraded dependency baseline

- Java: 21 (validated with JDK 21 toolchain; Windows preparation log used Temurin 21.0.11)
- Spring Boot: 4.1.1
- JavaFX: 21.0.12
- SQLite JDBC: 3.53.4.0
- Flyway Core: 12.4.0
- JUnit: 6.0.3
- Mockito: 5.23.0
- AssertJ: 3.27.7
- Testcontainers: 2.0.5
- ArchUnit: 1.5.0
- Maven Surefire: 3.5.6
- Bundled Maven: 3.9.6

The Spring Boot 4 migration includes package/configuration changes required by the new baseline (including Boot health contributor API and JDBC test auto-configuration package changes). The project continues to own its explicit multi-datasource Flyway lifecycle rather than delegating it to a generic Boot Flyway auto-configuration path.

## Windows offline repository provenance

The included `.mvn/repository` is the upgraded repository prepared on Windows from the project-provided `PREPARE-OFFLINE-REPO.cmd` / `tools/prepare-offline-repo.ps1` workflow.

That workflow creates `maven-offline-repo-upgraded.zip` only after the following sequence succeeds:

1. online `clean verify`;
2. dependency synchronization / `dependency:go-offline`;
3. offline `clean verify` against the project-local Maven repository;
4. cleanup of transient Maven cache markers;
5. repository ZIP creation.

The generated repository archive was then imported into this bundle and independently inspected for the required Spring Boot 4.1.1, JavaFX 21.0.12 Windows, SQLite JDBC 3.53.4.0 and Flyway 12.4.0 artifacts.

## Independent offline reactor validation

The upgraded project was independently verified offline using the bundled Maven 3.9.6 and the included repository.

Because the repository was populated on Windows, JavaFX 21.0.12 contains Windows native classifier JARs, not Linux native classifier JARs. On the Linux validation host, the reactor was therefore executed with the Windows JavaFX classifier only to validate compilation, dependency resolution, non-native logic, Spring wiring, database behavior, architecture and end-to-end tests.

Exactly two native-JavaFX-dependent test classes were excluded on Linux:

- `WorkspaceManagerNavigationStateTest` — 2 tests;
- `SpringContextStartupSmokeTest` — 1 test.

Those classes require a native JavaFX toolkit and cannot initialize on Linux from Windows JavaFX native JARs (`No toolkit found`). No other test classes were excluded.

Independent Linux offline result after those platform-only exclusions:

- reactor modules: 16/16 SUCCESS;
- tests executed: 1,094;
- failures: 0;
- errors: 0;
- skipped: 10;
- Maven result: BUILD SUCCESS;
- total reactor time: approximately 1 minute 36 seconds.

## Static / architecture / security validation

All of the following project gates passed on the final upgraded tree:

- dependency-upgrade-check;
- static release check;
- Iteration 85 finish/polish check;
- implementation completeness check;
- architecture check;
- functional regression check;
- XML/archive security check;
- privacy/temp lifecycle check;
- SecretStore policy check;
- managed executor check;
- Spring constructor wiring check;
- Spring proxyability check;
- supply-chain policy check;
- persistence error transparency check.

Selected metrics from the final gates:

- POM/FXML/XML files checked: 45, errors: 0;
- SQLite migrations: 60, integrity OK;
- production Java files checked by completeness gate: 1,095;
- Java sources seen by static release check: 1,502;
- test sources: 378;
- unsupported TODO/FIXME markers: 0;
- empty public/protected methods: 0;
- missing FXML handlers: 0;
- supply-chain policy: PASS.

## Real-data acceptance — FB2

The reader was exercised against both supplied real FB2 archives on the upgraded dependency stack.

Result:

- 2/2 books parsed successfully;
- failures: 0;
- errors: 0;
- Maven result: BUILD SUCCESS.

Probe 1:
- source bytes: 14,618,515;
- text characters: 6,657,913;
- paragraphs: 54,551;
- chapters: 16;
- TOC items: 316;
- resources: 41.

Probe 2:
- source bytes: 8,181,973;
- text characters: 3,470,361;
- paragraphs: 28,682;
- chapters: 9;
- TOC items: 172;
- resources: 38.

## Real-data acceptance — Flibusta INPX

The production INPX import path was exercised against the supplied `flibusta_online_fb2.inpx` on the upgraded SQLite/Flyway stack.

Input SHA-256:

`75bebb7a7ccf203bd934ef2af986f17d737ba4c4abfc277956f60bb84a6c7655`

Result:

- processed: 707,154 / 707,154;
- imported: 707,154;
- errors: 0;
- books in resulting catalog: 707,154;
- authors: 166,231;
- genres: 272;
- explicitly deleted records: 135,207;
- resulting SQLite database size: 1,583,337,472 bytes;
- duration: 92,609 ms;
- throughput: approximately 7,635.91 books/second;
- Maven result: BUILD SUCCESS.

## Offline verification on Windows

Prerequisite: JDK 21 available on `PATH`.

Recommended command:

```bat
VERIFY-OFFLINE-WINDOWS.cmd
```

Equivalent direct command:

```bat
mvnw.cmd -o -B -ntp clean verify
```

The wrapper launcher uses the bundled Maven 3.9.6 and the project-local `.mvn\repository`; no system Maven installation is required.

## Packaging scope

The full offline Windows x64 bundle includes:

- complete source tree;
- project Maven configuration;
- Maven 3.9.6 runtime;
- upgraded project-local Maven repository;
- Windows JavaFX 21.0.12 native classifier artifacts;
- Maven wrapper files and checksum;
- offline build/verification scripts;
- this validation report.

Generated `target` directories, temporary databases, test outputs, `dist`, Python caches, IDE metadata and VCS metadata are intentionally excluded from the final ZIP.

## Platform note

This bundle is self-contained for Maven dependency resolution on Windows x64, except for the required JDK 21 installation. It does not claim to contain Linux/macOS JavaFX native classifier artifacts. A Linux/macOS offline build must first add the matching JavaFX 21.0.12 platform classifiers to `.mvn/repository`.
