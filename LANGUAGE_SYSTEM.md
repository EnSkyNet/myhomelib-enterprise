# File-based localization

MyHomeLib UI localization is fully catalogue-driven.

## Files

- `Lang/<code>.json` — one independent UTF-8 translation catalogue per language.
- `config/language.txt` — selected language code.
- `config/available-languages.txt` — auto-generated list of catalogues currently detected.

The three shipped catalogues are `uk.json`, `en.json`, and `bg.json`. Their fallback copies also live in `myhomelib-ui/src/main/resources/lang/default/` so a packaged application can materialize external editable catalogues on the first run.

## Discovery lifecycle

1. On first startup, if `available-languages.txt` does not exist, MyHomeLib creates the language directory and copies any missing bundled default catalogues into it.
2. It scans every `*.json` file in the language directory.
3. Valid catalogues are loaded and `available-languages.txt` is rewritten to represent the languages currently available.
4. The language menu is built dynamically from that detected list.
5. Every time the language menu is opened, the directory is scanned again. A newly added language file therefore appears automatically without code changes.
6. The selected language is stored in `language.txt`; changing UI language still uses restart semantics for already-created windows.

Language-directory resolution is predictable: an explicitly configured `-Dmyhomelib.langDir=/path/to/Lang` wins; an existing `<launch-dir>/Lang` is used next; portable mode creates/uses `<launch-dir>/Lang`; otherwise normal installed mode uses `<data-dir>/Lang`. The exact active path is written into the header of `config/available-languages.txt`.
