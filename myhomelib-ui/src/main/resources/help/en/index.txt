# MyHomeLib 8.0.0 — Help

F1 opens this help. MyHomeLib manages multiple local and online collections, imports common ebook/document formats and archives, supports INPX, search, groups, ratings, progress, reviews, built-in readers, external readers, conversion and device export.

Main sections:
• Collections — creation, INPX, online addresses, switching and backup.
• Import — individual files, directories, INPX and archives.
• Search — normal and advanced; `%text%`, `="exact phrase"`, `OR`, field clauses and comparisons.
• Reader — FB2/FBD, EPUB, TXT/Markdown, PDF, CBZ/CBR, MP3/M4B and supported DRM-free MOBI/PRC/AZW/AZW3; TOC, search, bookmarks, notes and position persistence depend on the format.
• Online access — download a missing book/archive and cancel an active download.
• Export — original files, FB2.ZIP, book lists and conversion. Optional Calibre `ebook-convert` adds EPUB, MOBI, AZW3, PDF, FB2, TXT, DOCX, HTMLZ, KEPUB, LIT, LRF, PDB, PMLZ, RB, RTF, SNB, TCR, TXTZ and ZIP targets; actual availability depends on the source format and available converter.
• Portable mode — create an empty `myhomelib2.ini` beside the application; data, configuration and indexes will be stored beside the program.
• MCP — a separate read-only server; see `MCP_README.md` in the release root.

## Feature availability

• **Desktop UI:** collections, import, local folder synchronization, search and Smart Collections, book workflows, Reader, export, backup/restore, maintenance and OPDS management.
• **Sync, plugins and AI:** the built-in Integration Center configures WebDAV user-data synchronization, conflict review/resolution, plugin installation and lifecycle, and AI providers. Network/secret operations require explicit configuration and user consent. Do not confuse WebDAV sync with **Synchronize folder**, which works with a local file directory.
• **Web / OPDS:** separate server access channels. Their presence does not imply a dedicated desktop button for every server capability.
• **MCP:** a separate read-only server for an external MCP client.
