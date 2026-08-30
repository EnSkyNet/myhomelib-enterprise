# Cross-platform release pipeline

MyHomeLib Enterprise builds and verifies release candidates on Windows, Linux and macOS with JDK 21.

## CI gates

`.github/workflows/ci-release.yml` runs on pull requests, main/master pushes, manual dispatches and `v*` tags. Every operating-system job runs the full Maven reactor with:

```text
./mvnw clean verify -Pproduction
```

The Linux job additionally runs the offline architecture, migration, large-library and Stage 3–22 regression guards. A failure in any matrix job prevents a tagged release job from running.

## Portable desktop artifacts

`package-portable.sh` / `package-portable.ps1` call `jpackage --type app-image` and archive the resulting self-contained application image. The image contains a JDK runtime and the built application/dependencies; it does not download Maven artifacts or application dependencies when launched. Network-backed user features (for example an explicitly configured online collection) remain optional and do not make network access a startup requirement.

Artifacts are named by platform and architecture, for example:

```text
myhomelib-7.1.0-linux-x86_64.tar.gz
myhomelib-7.1.0-macos-arm64.tar.gz
myhomelib-7.1.0-windows-amd64.zip
```

The release also retains the executable bootstrap JAR and the MCP JAR.

## Packaged-launcher smoke

After `jpackage`, CI invokes the packaged executable with:

```text
--release-smoke
```

This headless code path proves that the native launcher reaches application code and can load resources contributed by multiple Maven modules: the main FXML, a Flyway migration, context help and the bundled Ukrainian language catalogue. It prints `MYHOMELIB_RELEASE_SMOKE_OK` and exits without starting JavaFX/Spring. Windows validates the native launcher exit code because the normal jpackage launcher is a GUI executable without an attached console.

This smoke is intentionally not a substitute for a real clean-machine GUI launch. Before signing a final desktop installer, also open the app interactively on a clean supported OS and create/open a small collection.

## Checksums and tagged releases

`checksums.sh` / `checksums.ps1` create SHA-256 manifests for local candidates. For a `v*` tag, the final GitHub release job downloads artifacts from all successful matrix jobs, collects the unique platform archives plus bootstrap/MCP JARs, generates one `SHA256SUMS`, and publishes those files through the repository's GitHub token.

## Local release

Linux/macOS:

```bash
./release.sh
```

Windows PowerShell:

```powershell
.\release.ps1
```

Both commands run `clean verify`, create a portable app-image archive, execute the packaged-launcher smoke and generate checksums.
