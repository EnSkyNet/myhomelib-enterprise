# Як підготувати оновлений Maven offline repository

Цей пакет уже містить:

- оновлений код MyHomeLib;
- Spring Boot 4.1.1 у `pom.xml`;
- JavaFX 21.0.12;
- SQLite JDBC 3.53.4.0;
- Flyway 12.4.0;
- Maven 3.9.6 у `.mvn/maven`;
- попередній локальний Maven cache у `.mvn/repository`;
- скрипт, який докачає лише відсутні/оновлені залежності та перевірить їх офлайн.

## Що потрібно на ПК

1. Windows 10/11.
2. Доступ до Інтернету.
3. JDK 21 у `PATH` (`java -version` має показувати Java 21).

Окремо Maven встановлювати **не потрібно** — Maven 3.9.6 уже включено.

## Що зробити

1. Повністю розпакуйте архів у звичайну локальну папку, наприклад `C:\MyHomeLib-upgrade`.
2. Запустіть `PREPARE-OFFLINE-REPO.cmd`.
3. Скрипт автоматично:
   - запустить online `clean verify`;
   - докачає Maven-залежності;
   - виконає `dependency:go-offline`;
   - повторно запустить `clean verify` **в offline-режимі**;
   - видалить тимчасові `.lastUpdated` файли;
   - створить `maven-offline-repo-upgraded.zip`;
   - створить файл SHA-256.
4. Завантажте в чат файл `maven-offline-repo-upgraded.zip`.

Якщо скрипт завершиться з помилкою, замість ZIP надішліть `PREPARE-OFFLINE-REPO.log` — у ньому буде точна причина.

## Важливо

Не запускайте скрипт без повного розпакування ZIP. Не запускайте його без JDK 21. Антивірус/корпоративний proxy має дозволяти HTTPS-доступ Maven до репозиторіїв залежностей.
## Примітка для Windows PowerShell 5.1

Скрипт у цьому bundle враховує, що `java -version` штатно пише версію в stderr. Це не помилка Java. Перевірка виконується через окреме захоплення stdout/stderr і не повинна завершуватися `NativeCommandError` на коректному JDK 21.

