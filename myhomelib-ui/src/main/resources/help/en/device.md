# Send to device and converters

In Settings configure the device folder, filename/subfolder templates, recommended Calibre `ebook-convert`, and optional special-purpose external converter templates. When conversion is not needed, the book is copied or extracted from its archive in the original format.

Calibre is optional and is not bundled with MyHomeLib. When `ebook-convert` is available, registered target formats include EPUB, MOBI, AZW3, PDF, FB2, TXT, DOCX, HTMLZ, KEPUB, LIT, LRF, PDB, PMLZ, RB, RTF, SNB, TCR, TXTZ and ZIP. A particular target is available only when the source format and an active conversion provider support it; FB2.ZIP is also available as MyHomeLib-owned packaging.

After export, MyHomeLib can run an explicitly configured post-send command. External templates can use placeholders such as `%DEST%`, `%TMP%`, `%FILE%`, `%FILENAME%`, `%TITLE%`, `%AUTHOR%`, `%SERIES%` and `%BOOKID%`. Commands run only when the user configures them explicitly.
