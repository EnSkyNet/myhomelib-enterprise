# MyHomeLib 8.0.0 — developer-source package

```text
MyHomeLib 8.0.0 — архів розробника
Дата актуального developer-source пакета: 2026-09-18

Містить:
- повний вихідний код усіх Maven-модулів;
- production і test Java-код;
- JavaFX FXML/CSS/локалізації;
- Plugin API, sample plugins і вихідний код Calibre Converter Plugin;
- WebDAV/Conflict Review/ШІ/PDF Reader код;
- Maven `pom.xml` і integrity-checked wrapper, зафіксований на Maven 3.9.16; сам Maven distribution не вбудовується;
- build/package/release/smoke-скрипти;
- tools, архітектурну та технічну документацію.

Свідомо не містить:
- */target/;
- локальний .mvn/repository (залежності);
- Calibre Portable runtime та calibre installer;
- готові JAR/ZIP релізи;
- тимчасові логи/кеші.

Для повністю офлайн-збірки використайте окремий maven-offline-repo-upgraded.zip
або дозвольте Maven завантажити залежності у .mvn/repository.
```
