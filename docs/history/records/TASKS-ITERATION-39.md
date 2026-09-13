# MyHomeLib — Iteration 39 — Reader text providers

**Дата:** 11.09.2026  
**База:** MyHomeLib 7.3 WIP / Iteration 38  
**Правило виконання:** усі source/docs/test changes для запланованого пакета вносяться до першого compile/test запуску. Після ручної ревізії дерево заморожується і виконується один фінальний acceptance/regression цикл.

## 1. MHL-210 — DictionaryProvider SPI

### Мета
Додати словниковий lookup без виходу з Reader із локальним offline-first провайдером та чистою provider abstraction.

### Scope
- `DictionaryProvider` application SPI та descriptor/query/result contracts.
- `DictionaryLookupService` з provider selection, offline-first default, timeout/cancellation та sanitized failures.
- Локальний UTF-8 TSV provider: custom file + bundled starter dictionary.
- Reader selection action `Dictionary` без Reader -> application/infrastructure dependency.
- Provider selection у JavaFX UI.
- Async result guard при book switch/close.
- UK/EN/BG localization.

### Acceptance
- Виділене слово можна знайти без виходу з Reader.
- Локальний provider працює без мережі.
- Якщо provider не вибраний явно, offline provider має пріоритет.
- Provider можна перемкнути явно.
- Timeout/cancel не блокують Reader і не показують stale result.
- Provider failure не валить Reader.

## 2. MHL-211 — TranslationProvider SPI

### Мета
Додати явний переклад виділеного тексту з локальним provider-ом та opt-in cloud adapters.

### Scope
- `TranslationProvider` application SPI та descriptor/query/result contracts.
- `TranslationService` з provider switch, timeout/cancellation, offline-first default та sanitized failures.
- Offline exact-phrase UTF-8 TSV provider.
- Opt-in adapters: DeepL, Google Cloud Translation, generic custom HTTPS JSON provider.
- API credentials: runtime property/environment або encrypted application setting; plaintext persisted secrets відхиляються.
- Reader selection action `Translate`.
- Для remote provider — явне privacy confirmation **перед network invocation**.
- Жодного auto-send при selection/change/open.
- Target-language choice та UK/EN/BG localization.

### Acceptance
- Provider switch працює.
- Offline provider працює без мережі.
- Cloud provider disabled by default.
- Selected text не передається remote provider без explicit user action і privacy confirmation.
- Timeout/cancel/stale completion не змінюють закритий або переключений Reader.
- Credentials не потрапляють у URL/result/UI error payload.

## 3. Межі архітектури

- `myhomelib-reader` знає лише renderer-neutral callbacks `Consumer<ReaderSelection>`.
- Reader не залежить від application/infrastructure/Spring/JDBC.
- JavaFX UI залежить від application services, але не від concrete text-provider adapters.
- Local/HTTP provider adapters живуть у `myhomelib-infrastructure`.
- Remote transport тільки HTTPS; TLS validation не вимикається.
- Cloud providers disabled by default.

## 4. MHL-206 / MHL-207

Iteration 38 status не переписується:
- MHL-206 source реалізований, але acceptance blocked через відсутній `org.apache.pdfbox:pdfbox:3.0.8` у supplied offline Maven repository.
- MHL-207 не стартує, доки MHL-206 не пройде acceptance.
- PDFBox/Maven binaries не додаються до source ZIP.

## 5. Фінальний тестовий цикл

Після source/docs/test freeze:
1. static source/localization/architecture gates;
2. application `TextProviderRequestContextTest`, `DictionaryLookupServiceTest`, `TranslationServiceTest`;
3. infrastructure local dictionary/translation provider tests;
4. HTTP translation adapter tests;
5. Reader/UI text-provider contract tests, якщо reactor може дійти до UI; інакше окремий source-contract gate з blocker attribution;
6. application + infrastructure regression;
7. release-tree cleanliness;
8. source ZIP integrity без Maven payload + SHA-256.

## Очікуваний результат

- MHL-210 — DONE за умови green acceptance.
- MHL-211 — DONE за умови green acceptance.
- MHL-206 blocker не маскується і не обходиться insecure downgrade-ом.
