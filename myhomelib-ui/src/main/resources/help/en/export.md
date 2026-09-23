# Export

Selected books can be exported in an already available local format or, when a compatible converter is available, converted through the shared `BookConverter` capability matrix. Built-in converters and optional Calibre `ebook-convert` are used only when they can accept the book's actual source format. For a book stored in an archive, MyHomeLib extracts that book's `archiveEntry` without manually unpacking the whole container. Filename and subfolder templates are supported.

The catalog list can be exported to HTML, TXT or RTF, and the current collection can be exported as INPX. Personal data — ratings, progress, reviews, groups, bookmarks, Reader positions and history — is exported separately from the catalog and linked by `LibID` so it can survive INPX refreshes.

Annotation Manager also provides an Obsidian/Joplin Markdown export: one file per book, configurable folder/file/body templates, optional YAML frontmatter and links back to MyHomeLib. By default, re-export replaces only the MyHomeLib-managed file for the same book; unrelated notes are not overwritten and numbered duplicate files are not created automatically.
