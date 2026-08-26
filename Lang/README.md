# MyHomeLib language files

UI and FB2 genre translations are loaded from standalone UTF-8 `*.json` files in this directory. No mandatory signing is required: a user can add or edit a language pack and refresh the language list.

Current schema:

```json
{
  "schemaVersion": 2,
  "code": "pl",
  "name": "Polski",
  "translations": {
    "Колекція": "Kolekcja",
    "Книги": "Książki"
  },
  "genres": {
    "sf": "Fantastyka naukowa",
    "det_classic": "Klasyczny kryminał"
  }
}
```

Rules:

- UTF-8 JSON;
- `schemaVersion`: current version is `2`; v1 remains readable with diagnostics/fallback;
- `code`: ISO-like language code (`pl`, `de`, `pt-br`, etc.);
- `translations`: source Ukrainian UI text -> translated UI text;
- `genres`: stable FB2 genre code -> localized display name;
- missing UI or genre keys safely fall back to source/catalog text;
- invalid/newer unsupported catalogues are ignored and reported rather than crashing startup;
- genre IDs stored in the database never change when UI language changes.

On every scan MyHomeLib writes `config/available-languages.txt` and `config/language-diagnostics.txt`. The latter reports schema issues and missing shipped keys so language-pack authors can find gaps. `config/language.txt` stores the selected language.
