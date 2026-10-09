# TASKS FOR CONTINUATION — MyHomeLib R4 Deep Audit

Працювати з кореня `myhomelib-work/`.

## 1. Відновити post-checkpoint patchset
Використати `POST-CHECKPOINT-FIXES-UA.md` як authoritative список.
Пріоритет:
1. SecretStore/WebDAV
2. network download/checkpoint/recovery
3. collection/search crash safety
4. lifecycle coordinator + async queue-gap
5. reader/session state
6. export/conversion/copy
7. plugin/settings/backup/privacy
8. TTS/process lifecycle

Після кожного блоку — targeted tests.

## 2. Offline Maven repo
Використати надані архіви:
- `maven-offline-repo-upgraded.zip`
- `javafx-21.0.12-linux-x64-offline.zip`
- `MyHomeLib-Iteration40-PDF-offline-deps.zip`
- `.mvn.zip`

Переконатися, що `.mvn/repository` містить реальні Maven directories/files, а не zero-byte placeholders.

## 3. Required regression gates

### Core/application
```bash
./mvnw -o -pl myhomelib-application -am test
```
Target expectation after recovered patchset:
- ~376 tests
- 0 failures
- 0 errors
- only intentional skip(s)

### Reader/UI
```bash
xvfb-run -a ./mvnw -o -pl myhomelib-ui -am test
```
Target expectation:
- Reader ~92 tests
- UI ~158 tests
- 0 failures/errors

### Infrastructure
Run full:
```bash
./mvnw -o -pl myhomelib-infrastructure -am test
```
If long, split by packages:
- persistence/sqlite
- collection/recovery
- download
- sync/webdav
- contentindexing
- security/secrets
- plugin runtime
- settings/backup/support

## 4. Converter real gates

### EPUBCheck
```bash
java -jar tools/validators/epubcheck/epubcheck.jar --version
```
Expected:
`EPUBCheck v5.4.0`

### libmobi Linux
```bash
tools/converters/libmobi/mobitool -v
```
Expected includes:
`libmobi: 0.12`

### Real MOBI -> EPUB
Use bundled fixture:
- positive fixture: `sample-ncx.mobi`
- negative fixture: `sample-textread.mobi`

Strict production path:
MOBI -> mobitool -> structural validator -> optimizer -> EPUBCheck -> reader reopen -> publication.

Expected:
- valid fixture published;
- invalid fixture rejected as `OUTPUT_INVALID`;
- final target absent for invalid conversion.

## 5. Windows gate

On real Windows:
Requirements:
- JDK 21+
- CMake
- Visual Studio Build Tools + Desktop development with C++

Run:
```powershell
.\tools\prepare-runtime-tools.ps1
```

Expected:
- `EPUBCheck v5.4.0`
- `tools\converters\libmobi\mobitool.exe`
- `mobitool.exe -v` contains `libmobi: 0.12`

Then:
```powershell
.\package-desktop.ps1 -Type app-image
.\tools\smoke-runtime-tools.ps1
.\smoke-desktop.ps1
```

Do not claim Windows-ready final until this is executed on real Windows.

## 6. Static/release gates

Before checks:
- remove all generated `target/`, `dist/`, build-native/build-windows output;
- audit documents belong in `docs/`, not root unless root whitelist allows them;
- restore executable bits on `mvnw` and shell scripts.

Run:
```bash
python3 tools/build-check-v7.py
python3 tools/static_release_check.py
python3 tools/supply-chain-policy-check.py
```

All must PASS.

## 7. Full reactor

After targeted tests:
```bash
xvfb-run -a ./mvnw -o clean verify -Pproduction
```

If environment timeout prevents one-shot run:
- run module-by-module;
- aggregate Surefire/Failsafe XML;
- report exact totals;
- do not call full regression green unless every reactor module completed.

## 8. Package gates

Linux:
```bash
./package-desktop.sh app-image
./tools/smoke-runtime-tools.sh
./smoke-desktop.sh
```

Windows:
```powershell
.\package-desktop.ps1 -Type app-image
.\tools\smoke-runtime-tools.ps1
.\smoke-desktop.ps1
```

Verify packaged paths:
Linux:
- `dist/MyHomeLib/lib/app/tools/validators/epubcheck/epubcheck.jar`
- `dist/MyHomeLib/lib/app/tools/converters/libmobi/mobitool`
- `dist/MyHomeLib/lib/runtime/bin/java`

Windows:
- `dist\MyHomeLib\app\tools\validators\epubcheck\epubcheck.jar`
- `dist\MyHomeLib\app\tools\converters\libmobi\mobitool.exe`
- `dist\MyHomeLib\runtime\bin\java.exe`

## 9. Real fixtures
Required final real-fixture gates:
- FB2.ZIP
- INPX
- EPUB standard/reader
- MOBI -> EPUB
- STRICT fail-closed without EPUBCheck when required
- real online collection/no-op where test fixture supports it.

## 10. Final handoff
Only after all gates:
- update verification docs;
- record exact test totals;
- record external tool versions;
- record Windows smoke evidence;
- create final source ZIP;
- create SHA-256;
- no generated `target/` directories inside source archive;
- preserve Unix executable bits where possible.

Do not state "audit complete" before all items above.
