package com.myhomelibcorp.reader.renderer;

import com.myhomelibcorp.reader.model.BookDocument;
import com.myhomelibcorp.reader.model.BookMetadata;
import com.myhomelibcorp.reader.model.Chapter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DocumentToHtmlConverter {

    public String convert(BookDocument document) {
        long startTime = System.currentTimeMillis();

        StringBuilder html = new StringBuilder(1024 * 100);

        BookMetadata metadata = document.getMetadata();

        html.append("<!DOCTYPE html>\n");
        html.append("<html>\n");
        html.append("<head>\n");
        html.append("    <meta charset=\"UTF-8\"/>\n");
        html.append("    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\"/>\n");
        html.append("    <title>").append(escapeHtml(metadata.getTitle())).append("</title>\n");
        html.append("    <style>\n");
        html.append(getDefaultStyles());
        html.append("    </style>\n");
        html.append("</head>\n");
        html.append("<body>\n");

        // Заголовок книги
        html.append("<div class=\"book-title\">").append(escapeHtml(metadata.getTitle())).append("</div>\n");

        // Автори
        if (metadata.getAuthors() != null && !metadata.getAuthors().isEmpty()) {
            html.append("<div class=\"authors\">");
            html.append(String.join(", ", metadata.getAuthors().stream().map(this::escapeHtml).toArray(String[]::new)));
            html.append("</div>\n");
        }

        // Анотація
        if (metadata.getAnnotation() != null && !metadata.getAnnotation().isEmpty()) {
            html.append("<div class=\"annotation\">");
            html.append(escapeHtml(metadata.getAnnotation()));
            html.append("</div>\n");
        }

        // Серія
        if (metadata.getSeries() != null) {
            html.append("<div class=\"series-info\">Серія: ").append(escapeHtml(metadata.getSeries()));
            if (metadata.getSequenceNumber() != null && metadata.getSequenceNumber() > 0) {
                html.append(" #").append(metadata.getSequenceNumber());
            }
            html.append("</div>\n");
        }

        html.append("<hr class=\"book-divider\"/>\n");

        // Розділи
        for (Chapter chapter : document.getChapters()) {
            html.append(renderChapter(chapter, 1));
        }

        html.append("</body>\n");
        html.append("</html>");

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("HTML generated: {} chars, {} ms", html.length(), elapsed);

        return html.toString();
    }

    private String renderChapter(Chapter chapter, int level) {
        StringBuilder sb = new StringBuilder(1024);
        String tag = "h" + Math.min(level, 6);

        sb.append("<div class=\"chapter\">\n");
        if (chapter.getTitle() != null && !chapter.getTitle().isEmpty()) {
            sb.append("<").append(tag).append(" class=\"chapter-title\">")
                    .append(escapeHtml(chapter.getTitle()))
                    .append("</").append(tag).append(">\n");
        }
        if (chapter.getContent() != null && !chapter.getContent().isEmpty()) {
            sb.append("<div class=\"chapter-content\">\n");
            sb.append(chapter.getContent());
            sb.append("</div>\n");
        }
        for (Chapter child : chapter.getChildren()) {
            sb.append(renderChapter(child, level + 1));
        }
        sb.append("</div>\n");
        return sb.toString();
    }

    private String getDefaultStyles() {
        return """
                body {
                    font-family: 'Georgia', 'Times New Roman', serif;
                    font-size: 18px;
                    line-height: 1.6;
                    padding: 20px 5%;
                    width: 100%;
                    max-width: 100%;
                    box-sizing: border-box;
                    background-color: #ffffff;
                    color: #000000;
                    margin: 0;
                    word-wrap: break-word;
                    overflow-wrap: break-word;
                    white-space: normal;
                }
                .book-title {
                    text-align: center;
                    font-size: 28px;
                    font-weight: bold;
                    margin-bottom: 10px;
                    word-wrap: break-word;
                    overflow-wrap: break-word;
                    white-space: normal;
                }
                .authors {
                    text-align: center;
                    font-size: 18px;
                    font-style: italic;
                    margin-bottom: 20px;
                    word-wrap: break-word;
                    overflow-wrap: break-word;
                    white-space: normal;
                }
                .annotation {
                    font-style: italic;
                    color: #555;
                    margin: 20px 0;
                    padding: 15px;
                    background-color: #f5f5f5;
                    border-radius: 5px;
                    word-wrap: break-word;
                    overflow-wrap: break-word;
                    white-space: normal;
                }
                .series-info {
                    text-align: center;
                    font-size: 14px;
                    color: #666;
                    margin-bottom: 20px;
                    word-wrap: break-word;
                    overflow-wrap: break-word;
                    white-space: normal;
                }
                .book-divider {
                    border: 1px solid #ddd;
                    margin: 30px 0;
                }
                .chapter {
                    margin-bottom: 20px;
                    word-wrap: break-word;
                    overflow-wrap: break-word;
                    white-space: normal;
                }
                .chapter-title {
                    font-weight: bold;
                    margin-top: 25px;
                    margin-bottom: 15px;
                    word-wrap: break-word;
                    overflow-wrap: break-word;
                    white-space: normal;
                }
                .chapter-content {
                    margin-left: 10px;
                    word-wrap: break-word;
                    overflow-wrap: break-word;
                    white-space: normal;
                }
                h1 { font-size: 24px; word-wrap: break-word; overflow-wrap: break-word; white-space: normal; }
                h2 { font-size: 22px; word-wrap: break-word; overflow-wrap: break-word; white-space: normal; }
                h3 { font-size: 20px; word-wrap: break-word; overflow-wrap: break-word; white-space: normal; }
                h4 { font-size: 18px; word-wrap: break-word; overflow-wrap: break-word; white-space: normal; }
                h5 { font-size: 16px; word-wrap: break-word; overflow-wrap: break-word; white-space: normal; }
                h6 { font-size: 14px; word-wrap: break-word; overflow-wrap: break-word; white-space: normal; }
                p {
                    margin: 0.8em 0;
                    text-align: justify;
                    text-indent: 1.5em;
                    word-wrap: break-word;
                    overflow-wrap: break-word;
                    white-space: normal;
                }
                .poem {
                    margin: 15px 0;
                    padding-left: 20px;
                    font-family: 'Courier New', monospace;
                    word-wrap: break-word;
                    overflow-wrap: break-word;
                    white-space: normal;
                }
                .stanza {
                    margin: 8px 0;
                    word-wrap: break-word;
                    overflow-wrap: break-word;
                    white-space: normal;
                }
                .verse-line {
                    margin: 2px 0;
                    word-wrap: break-word;
                    overflow-wrap: break-word;
                    white-space: normal;
                }
                blockquote {
                    margin: 15px 30px;
                    padding: 10px 20px;
                    background-color: #f9f9f9;
                    border-left: 4px solid #ccc;
                    font-style: italic;
                    word-wrap: break-word;
                    overflow-wrap: break-word;
                    white-space: normal;
                }
                .epigraph {
                    font-style: italic;
                    margin: 20px 40px;
                    color: #555;
                    word-wrap: break-word;
                    overflow-wrap: break-word;
                    white-space: normal;
                }
                .book-image {
                    max-width: 100%;
                    height: auto;
                    display: block;
                    margin: 15px auto;
                }
                .footnote-mark {
                    color: #0066cc;
                    cursor: pointer;
                }
                .footnotes {
                    margin-top: 40px;
                    padding-top: 20px;
                    border-top: 2px solid #ddd;
                    font-size: 14px;
                    word-wrap: break-word;
                    overflow-wrap: break-word;
                    white-space: normal;
                }
                .footnote {
                    margin: 5px 0;
                    word-wrap: break-word;
                    overflow-wrap: break-word;
                    white-space: normal;
                }
                .subchapter {
                    margin-left: 20px;
                    border-left: 2px solid #eee;
                    padding-left: 15px;
                    word-wrap: break-word;
                    overflow-wrap: break-word;
                    white-space: normal;
                }
                @media (prefers-color-scheme: dark) {
                    body { background-color: #1a1a1a; color: #e0e0e0; }
                    .annotation { background-color: #2a2a2a; color: #ccc; }
                    blockquote { background-color: #2a2a2a; border-left-color: #555; }
                    .footnotes { border-top-color: #444; }
                    .subchapter { border-left-color: #444; }
                }
                """;
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("\n", "<br/>");
    }
}