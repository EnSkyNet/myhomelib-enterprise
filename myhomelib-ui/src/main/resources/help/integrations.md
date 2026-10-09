# Інтеграції та Kobo Sync

Integration Center об’єднує зовнішні інтеграції MyHomeLib. Вкладка **Kobo Sync** призначена для нативної синхронізації сумісного Kobo через OPDS-sidecar, а не для звичайного USB Export / Send to Device.

## Kobo Sync

1. Увімкніть Kobo Sync. MyHomeLib створить непрозорий device ID і одноразово покаже credential. Секрет зберігається тільки через системний SecretStore; після закриття діалогу його не можна прочитати з бази або конфігурації.
2. Налаштуйте Kobo на endpoint, який показує ваш MyHomeLib server. Для мережевого доступу діють чинні правила TLS/OPDS.
3. **Підготувати синхронізацію** оновлює локальну projection/cursor. Сам пристрій забирає incremental зміни під час свого sync.
4. EPUB/KEPUB передаються напряму. Якщо книга потребує конвертації, використовується чинний converter toolchain, а conversion запускається лише під час фактичного завантаження книги Kobo.
5. Прогрес синхронізується в обидва боки. Старіший update не перезаписує новішу canonical version.
6. **Відкликати пристрій** вимикає доступ і видаляє credential із SecretStore.

Live interoperability, first sync, відкриття книги, cover/metadata, reconnect і revoke потрібно окремо перевірити на реальному Kobo.

## Readwise
У Integration Center → Readwise токен зберігається через захищене сховище секретів. Доступні перевірка з’єднання, експорт усіх анотацій і очищення токена. Для експорту книг із поточного виділення/фільтра використовуйте Annotation Manager.

