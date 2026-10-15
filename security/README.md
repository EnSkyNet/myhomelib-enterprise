# Облік binaries і перевірка охоплення SCA

`binary-manifest.json` містить 40 EPUBCheck JAR та один Windows executable: точний SHA-256, розмір, версію й підставу ідентифікації, джерело, ліцензійні матеріали, дату перевірки. `checkedOn` означає дату огляду переліку, а не успішного CVE-сканування. Непідтверджені метадані позначено явно.

```bash
python3 tools/vendor-binary-inventory.py --check --sbom target/vendor-bom.cdx.json
./tools/invoke-maven.sh -B -ntp -DskipTests -Psbom,dependency-check verify
python3 tools/vendor-binary-inventory.py --check --sca-report target/dependency-check-report.json
```

Maven SBOM охоплює граф залежностей; vendor SBOM доповнює його фізичними binaries. `scanSet` містить `tools`, `dist` і стандартні каталоги ресурсів. Повторіть сканування після пакування, до видалення app-image, та перевірку з `--image-root dist`. Вона вимагає наявності JAR у фінальному образі й відповідних SHA-256 у звіті. Збережіть JSON/HTML/SARIF і обидва SBOM для одного кандидата.

Coverage validator відхиляє пропущені JAR та analysis exceptions. Він не вигадує CPE й не доводить відсутності вразливостей; поріг небезпеки контролює Dependency-Check. Походження, ліцензійні обов’язки й безпека native `mobitool.exe` потребують окремого огляду.

При оновленні binary перевірте upstream-артефакт, доступні підписи/хеші, версію й ліцензійні матеріали; перегляньте inventory, оновіть SBOM, виконайте negative tests, live scan та packaged-tool smoke. Не перегенеровуйте хеші лише для приховування невідомої зміни.

Першоджерела: [CycloneDX 1.6](https://github.com/CycloneDX/specification/tree/1.6/schema), [Dependency-Check Maven](https://dependency-check.github.io/DependencyCheck/dependency-check-maven/configuration.html).
