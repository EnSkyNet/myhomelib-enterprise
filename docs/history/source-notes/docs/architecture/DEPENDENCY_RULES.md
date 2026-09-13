# Dependency Rules — Quick Reference

```text
shared          -> -
domain          -> shared
application     -> shared, domain
reader          -> shared
infrastructure  -> shared, domain, application
ui              -> shared, domain, application, reader
bootstrap       -> shared, domain, application, infrastructure, ui
mcp             -> shared
```

Hard prohibitions:

- Domain: no Spring, JavaFX, JDBC, Lucene or outer product modules.
- Application: no Infrastructure, UI, Reader, MCP, JavaFX, JDBC or Lucene.
- Infrastructure: no UI, Reader or JavaFX.
- UI: no Infrastructure, JDBC or Lucene.
- Reader: no desktop product modules, Spring, JDBC or Lucene.
- Reader portable packages: no JavaFX.
- MCP: no desktop product modules, Spring or JavaFX.

Validation:

```bash
python3 tools/architecture-check.py
./mvnw -pl myhomelib-architecture-tests -am test
```
