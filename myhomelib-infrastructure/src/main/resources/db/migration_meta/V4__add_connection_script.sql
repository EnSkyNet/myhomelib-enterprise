-- v7.1: persist MyHomeLib online-library ConnectionScript verbatim (multiline UTF-8 text).
ALTER TABLE collections ADD COLUMN connection_script TEXT;
