# MyHomeLib language files

UI translations and localized names of extended FB2 genres are loaded from standalone UTF-8 `*.json` files in this directory. No mandatory signing is required: a user can add or edit a language pack and refresh the language list.

Current schema:

```json
{
  "schemaVersion": 3,
  "code": "pl",
  "name": "Polski",
  "translations": {
    "Колекція": "Kolekcja",
    "Книги": "Książki"
  },
  "genreCatalog": {
    "source": "genres_fb2 + genres_fb2_uk.glst",
    "displayPolicy": "extended-with-parent-fallback",
    "entries": 335,
    "parentGroups": 23
  },
  "genres": {
    "sf_history": "Historia alternatywna",
    "sf_action": "Fantastyka akcji",
    "det_classic": "Klasyczny kryminał"
  },
  "genreAliases": {
    "0.1.1": "sf_history",
    "0.2.13": "det_classic"
  },
  "genreGroups": {
    "speculative": "Fantastyka",
    "detective": "Kryminały i thrillery"
  },
  "genreParents": {
    "sf_history": "speculative",
    "det_classic": "detective"
  },
  "legacyBaseAliases": {
    "0.1": "speculative",
    "0.2": "detective"
  }
}
```

Rules:

- UTF-8 JSON;
- `schemaVersion`: current version is `3`; v1/v2 remain readable with diagnostics;
- `code`: ISO-like language code (`pl`, `de`, `pt-br`, etc.);
- `translations`: source Ukrainian UI text -> translated UI text;
- `genres`: **stable extended FB2 genre code -> localized human-readable display name**;
- `genreGroups`: stable semantic parent key -> localized base-category label; base labels are fallback labels, not normal genre rows;
- `genreParents`: stable extended FB2 genre code -> semantic parent key; this mapping, not the numeric hierarchy, defines the parent category;
- `legacyBaseAliases`: legacy `genres_fb2.txt` base number -> semantic parent key, used only when an old numeric code has no exact extended alias;
- if an exact extended genre exists, only its localized name is shown; a base category is shown only as fallback when no exact subgenre can be resolved;
- internal identifiers such as `sf_fantasy`, `det_classic` and numeric IDs are never rendered to the user; they exist only for lookup/navigation identity;
- `genreAliases`: legacy numeric dictionary id -> stable extended genre code; these aliases are kept for compatibility with older collections;
- shipped `uk/en/bg` catalogues contain 335 extended genre codes: the union of `tools/reference/genres_fb2.txt` and `tools/reference/genres_fb2_uk.glst`;
- the supplemental `.glst` uses a different numeric hierarchy, therefore only its stable textual codes and semantic grouping are used; its conflicting numeric positions are never imported as global aliases;
- numeric hierarchy is never a canonical identity. `genreAliases` and `legacyBaseAliases` represent only the original `genres_fb2.txt` compatibility namespace;
- the database stores stable genre codes and never stores a language-dependent translation as the identity;
- reference genre files are not used as runtime fallbacks; they are kept only under `tools/reference/` for coverage verification;
- a genuinely custom genre absent from the canonical dictionary may display its imported human-readable name, but never its raw internal code;
- invalid/newer unsupported catalogues are ignored and reported rather than crashing startup.

On every scan MyHomeLib writes `config/available-languages.txt` and `config/language-diagnostics.txt`. The latter reports schema issues and missing shipped keys so language-pack authors can find gaps. `config/language.txt` stores the selected language.
