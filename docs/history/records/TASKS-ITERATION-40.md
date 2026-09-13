# MyHomeLib — Iteration 40 — PDF closure

**Дата:** 11.09.2026  
**Статус:** DONE — MHL-206/207 accepted; MHL-210/211 regression tail accepted.  
**База:** Iteration 39 WIP  
**Правило:** усі source/docs/test changes завершуються до першого compile/test запуску; після ручної ревізії дерево заморожується і виконується один фінальний acceptance/regression цикл.

## 1. Offline PDF dependency closure

- Використати надані source releases `pdfbox-3.0.8-src.zip` та `jbig2-imageio-3.0.5-src.zip` лише для побудови зовнішнього offline dependency repository.
- Source release MyHomeLib не містить Maven, `.mvn/`, `mvnw*`, PDFBox/JBIG2 JAR або інші dependency binaries.
- `pom.xml` описує PDFBox 3.0.8 та JBIG2 ImageIO 3.0.5 як нормальні Maven dependencies.
- Не виконувати downgrade до PDFBox 1.x/2.x.

## 2. MHL-206 — final acceptance closure

- Повторно виконати Reader/UI acceptance після появи PDFBox 3.0.8 в offline repo.
- Підтвердити open/render/page navigation/zoom/fit/continuous/thumbnails/page restore/cache/cancel/error paths.
- Підтвердити FB2/EPUB Reader regressions.

## 3. MHL-207 — PDF TOC / search / bookmarks / annotation contract

### Scope
- PDF Outline/TOC через Reader-neutral `PdfOutlineEntry`.
- Jump по TOC.
- Text-layer search із bounded result count, page + snippet і cancellation.
- Jump до search result.
- Page bookmarks через існуючий bookmark persistence path.
- Image-only/scanned PDF: Reader працює, search повертає явний no-text-layer стан; OCR не запускається.
- Application contract `PdfAnnotationSelectionData` із явно page-local text offsets для майбутньої PDF selection/annotation інтеграції.
- PDFBox не імпортується UI/Application/Domain.

### Acceptance
- Outline hierarchy і page mapping коректні.
- Search case-insensitive, повертає page + snippet і обмежує кількість результатів.
- Search cancellation не дозволяє stale result після close/switch.
- Blank/image-only PDF не помилково запускає OCR.
- PDF bookmark save/load/jump використовує існуючий persistence service.
- Annotation contract не маскує page-local offsets як global Reader offsets.

## 4. Iteration 39 tail

У тому самому фінальному циклі завершити regressions MHL-210/MHL-211, які вже мають targeted acceptance 22/22.

## 5. Final cycle

1. dependency/offline reactor `test-compile`;
2. MHL-206/MHL-207 targeted Reader tests;
3. MHL-210/MHL-211 targeted tests;
4. application/reader/UI regressions;
5. architecture/localization/static gates;
6. infrastructure regression groups;
7. OPDS/bootstrap/MCP/E2E tail;
8. release-tree cleanliness;
9. source ZIP integrity, **без Maven і dependency binaries**;
10. SHA-256.
