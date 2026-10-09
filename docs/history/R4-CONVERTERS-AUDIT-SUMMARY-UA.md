# MyHomeLib 8.0.0 R4 — аудит EPUBCheck/libmobi та Windows packaging

Дата: 2026-09-21

## Фінальна інтеграційна модель

- EPUBCheck bundled mode: `tools/validators/epubcheck/epubcheck.jar` + `lib/`.
- Окремий `epubcheck.exe` для bundled mode не потрібний.
- У jpackage runtime збережено `java/java.exe`, тому EPUBCheck запускається bundled Java.
- libmobi: Java не лінкує DLL через JNI/JNA; використовується зовнішній CLI `mobitool`.
- Windows runtime name: `tools/converters/libmobi/mobitool.exe`.
- Linux/macOS runtime name: `tools/converters/libmobi/mobitool`.
- На Windows `package-desktop.ps1` автоматично викликає `tools/prepare-runtime-tools.ps1`, який за відсутності `mobitool.exe` збирає його з bundled libmobi 0.12 source через CMake + MSVC.

## Виправлені дефекти

1. libmobi real `mobitool -v` має build-banner перед `libmobi: 0.12`; старий probe перевіряв лише перший рядок і міг помилково позначати справний інструмент unavailable.
2. jpackage staging раніше не переносив EPUBCheck/libmobi в готовий app-image.
3. default jlink runtime не містив `java/java.exe`, через що bundled EPUBCheck JAR не міг запускатися на чистій машині без системної Java.
4. bundled EPUBCheck discovery тепер JAR-first/JAR-only; `epubcheck.exe` не є вимогою bundled package.
5. raw XML parser failure після `mobitool` тепер класифікується як `ConversionFailureKind.OUTPUT_INVALID` і не публікує невалідний EPUB.
6. ConnectionScript HTTPS/redaction regressions виправлено; malformed expanded URL не повертає `%PASS%` у помилці.
7. Додано release smoke для packaged EPUBCheck/libmobi.
8. Додано regression на termination descendant process tree після timeout.

## Перевірки

- `ConverterToolProbeTest`: 5/5 PASS.
- `ConverterToolLocatorTest`: 5/5 PASS.
- `ConnectionScriptExecutorTest`: 9/9 PASS.
- `EpubCheckToolCommandFactoryTest`: 6/6 PASS після JAR-only policy.
- `ProcessExecutionSupportTest`: 5/5 PASS.
- `ToolchainBookConverterPublicationTest`: 2/2 PASS.
- real production Spring composition `RealLibMobiToolchainAcceptanceTest`: PASS:
  - `sample-ncx.mobi` -> STRICT MOBI->EPUB accepted;
  - `sample-textread.mobi` -> rejected; final target not published.
- Linux packaged app-image:
  - packaged launcher smoke PASS;
  - packaged EPUBCheck probe: `EPUBCheck v5.4.0`;
  - packaged libmobi probe: `libmobi: 0.12`;
  - packaged runtime-tools smoke PASS.
- `tools/build-check-v7.py`: PASS.
- `tools/static_release_check.py`: PASS.
- `tools/supply-chain-policy-check.py`: PASS.

## libmobi fixture survey

8 non-DRM fixtures were converted by `mobitool` with exit code 0.
EPUBCheck accepted 1/8 and rejected 7/8. This confirms that MyHomeLib must not treat `mobitool` exit code 0 as sufficient EPUB validity evidence.

## Windows

This Linux audit environment cannot execute a Windows PE binary. The project therefore does not falsely ship an unverified third-party `mobitool.exe`.

The Windows release path is made deterministic:
1. use bundled libmobi 0.12 source;
2. build with CMake + Visual Studio C++ Build Tools;
3. require `mobitool.exe -v` -> `libmobi: 0.12`;
4. package it into Windows jpackage app-image;
5. require `tools/smoke-runtime-tools.ps1` before release.

Official libmobi 0.12 release assets contain source packages, not an official prebuilt Windows `mobitool.exe`, so source-build during Windows packaging is the reproducible upstream-aligned path.

## Tree delta versus supplied WIP handoff

- Added files: 182
- Modified files: 23
- Removed files: 0

See `R4-CONVERTERS-CHANGED-FILES.txt`.
