# Windows portable: результат етапу 08.10.2026

## Варіант архіву, зібраного в Linux

Архів `MyHomeLib-R18-8.0.0-windows-x64-portable-bootstrap.zip` містить:
- Java 21 Spring Boot executable JAR **зі спеціальними JavaFX-бібліотеками для Windows x64**;
- `Start-MyHomeLib.cmd`, `Setup-Windows-Runtime.ps1`, маркер `myhomelib2.ini` і `data/`;
- надані інструменти конвертації та валідатор EPUB.

**Це не app-image від Windows jpackage.** Windows Java 21 runtime не входить до ZIP.
Під час першого запуску `.cmd` PowerShell завантажує офіційний Temurin JRE 21.0.12.1+1
Windows x64 і перевіряє **фіксований SHA-256** перед встановленням у `runtime/`.
Після одноразового завантаження Java і всі дані розташовані у директорії програми;
системного встановлення Java не потрібно. Без інтернету першого запуску слід
завчасно скопіювати перевірений Temurin JRE 21 Windows x64 в `runtime/`.

**Повна автономність без першого завантаження** потребує створення `jpackage --type
app-image` на Windows з JDK 21 і JavaFX для Windows за допомогою
`package-portable.ps1`. Код і скрипт збережені в репозиторії.

## Перевірки й обмеження

Під Linux можна підтвердити Maven-збірку, статичний вміст Windows JavaFX JAR,
запуск `--release-smoke` на JVM Linux (він не піднімає JavaFX), повноту включення
конвертерів і цілісність ZIP. **Неможливо** вважати Windows GUI, Calibre,
скачування Java на Windows, DLL-залежності, DPI та реальні показники Private Bytes
перевіреними до Windows-приймання.

Встановлено рекомендовані початкові JVM параметри `-Xms32m -Xmx1024m`;
вони обмежують Java heap, але не сумарну фізичну пам’ять процесу.
