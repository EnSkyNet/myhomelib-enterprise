# MyHomeLib: EPUBCheck + libmobi на Windows

## Підсумкова схема

### EPUBCheck
MyHomeLib **не потребує `epubcheck.exe`** у bundled-конфігурації.

Файли:
- `tools/validators/epubcheck/epubcheck.jar`
- `tools/validators/epubcheck/lib/*.jar`
- `tools/validators/epubcheck/licenses/*`

Запуск у packaged MyHomeLib:
`<MyHomeLib>\runtime\bin\java.exe -jar <MyHomeLib>\app\tools\validators\epubcheck\epubcheck.jar <book.epub>`

Під час `jpackage` проєкт зберігає `java.exe` у власному runtime, тому системна Java на ПК користувача не потрібна.

### libmobi
MyHomeLib використовує libmobi через офіційну CLI-утиліту **`mobitool`**.

На Windows потрібен:
`tools\converters\libmobi\mobitool.exe`

Це не JNI/JNA DLL і не `libmobi.exe`. Java запускає `mobitool.exe` окремим процесом.

Офіційний libmobi 0.12 не публікує готовий Windows `mobitool.exe`, тому цей проєкт збирає його з включеного source-коду під час Windows packaging.

## Підготовка Windows runtime tools

Вимоги до build-машини:
- JDK 21+
- CMake
- Visual Studio Build Tools з компонентом **Desktop development with C++**

Запустити з кореня проєкту:

```powershell
.\tools\prepare-runtime-tools.ps1
```

Скрипт:
1. перевіряє `EPUBCheck v5.4.0`;
2. якщо `mobitool.exe` відсутній — знаходить MSVC через `vswhere`;
3. збирає статичний `mobitool.exe` з bundled libmobi 0.12;
4. перевіряє, що `mobitool.exe -v` містить `libmobi: 0.12`.

Після цього:

```text
tools\validators\epubcheck\epubcheck.jar
tools\validators\epubcheck\lib\...
tools\converters\libmobi\mobitool.exe
```

## Збірка MyHomeLib для Windows

```powershell
.\package-portable.ps1
```

або installer:

```powershell
.\package-desktop.ps1 -Type exe
```

`package-desktop.ps1` автоматично викликає `prepare-runtime-tools.ps1`, тому окремо підкладати конвертери не потрібно.

## Перевірка готового app-image

```powershell
.\tools\smoke-runtime-tools.ps1
.\smoke-desktop.ps1
```

Очікувано:
- `EPUBCheck v5.4.0`
- `libmobi: 0.12`
- `Packaged runtime tools smoke: PASS`
- `Desktop packaged-launcher smoke: PASS`

## Політика якості MOBI -> EPUB

`mobitool.exe` може завершитися з кодом 0, але створити EPUB із помилками стандарту. Тому MyHomeLib не довіряє лише exit code:

`MOBI -> mobitool -> structural validator -> EPUB optimizer -> EPUBCheck -> reader reopen -> publication`

У STRICT режимі невалідний EPUB не публікується.
