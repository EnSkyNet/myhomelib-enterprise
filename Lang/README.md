# MyHomeLib language files

UI translations are loaded from UTF-8 `*.json` files in this directory. The Java code does not contain English/Bulgarian translation maps anymore.

Each file has this structure:

```json
{
  "code": "pl",
  "name": "Polski",
  "translations": {
    "Колекція": "Kolekcja",
    "Книги": "Książki"
  }
}
```

Rules:

- file encoding: UTF-8;
- `code`: ISO-like language code (`pl`, `de`, `pt-br`, etc.);
- `name`: the text shown in the language menu;
- `translations`: source Ukrainian UI text -> translated UI text;
- missing keys safely fall back to the original Ukrainian text;
- invalid JSON/catalogues are ignored and reported to the log.

On the first run MyHomeLib creates default `uk.json`, `en.json`, and `bg.json` if the language directory does not exist yet. On every language-menu refresh it scans all `*.json` files and synchronizes `config/available-languages.txt`. Adding a new valid JSON file is therefore enough to add a language; no Java/FXML changes are required.

`config/language.txt` stores the currently selected language code. `config/available-languages.txt` is generated automatically and should not be edited manually.
