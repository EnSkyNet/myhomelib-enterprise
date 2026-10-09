# Reader

MyHomeLib includes several built-in reading modes. The JavaFX Canvas text Reader does not use WebView for book pages and supports FB2/FBD, FB2.ZIP/ZIP, EPUB, TXT/Markdown, plus DRM-free MOBI/PRC/AZW/AZW3 when the content is uncompressed or PalmDOC; HUFF/CDIC requires conversion. Text formats provide TOC, search, rich text, footnotes, internal links, bookmarks, selection/copy, themes, margins, fonts, justification, hyphenation, tap/swipe navigation, autoscroll and saved position.

PDF opens in the built-in paged viewer; an encrypted PDF may request a password, which the Reader does not persist. CBZ/CBR open in the built-in comic viewer with lazy page loading. MP3/M4B open in the built-in audio mode with saved position. Search, annotation and navigation capabilities vary by document type.

For other formats configure an external application under **Settings → External reading**.

In the text Reader, select text with an ordinary left-mouse drag. The selection menu immediately offers Highlight / Note / Dictionary / Translate / Copy. On touch devices, a long press selects a word while swipe gestures remain available for page navigation.

## Reader setting profiles

Reader settings can be stored globally, for a format, for a series, or only for the current book. The effective profile is resolved with the strict precedence **book → series → format → global**. The settings dialog shows the source of the active profile and offers Apply globally, Save for this format, Save for this series, Save only for this book, and Clear override. Clearing removes only the currently active override and reveals the next lower scope; other profiles are left unchanged.
