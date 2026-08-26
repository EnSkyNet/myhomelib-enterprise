#!/usr/bin/env python3
from pathlib import Path
import re, sqlite3, xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
def text(p): return (ROOT / p).read_text(encoding='utf-8')
def require(cond,msg):
    if not cond: raise AssertionError(msg)

book = text('myhomelib-application/src/main/java/com/myhomelibcorp/application/dto/BookDto.java')
mapper = text('myhomelib-application/src/main/java/com/myhomelibcorp/application/mapper/BookMapper.java')
analysis = text('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/details/BookDetailsAnalysisService.java')
controller = text('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/details/BookDetailsController.java')
fxml = text('myhomelib-ui/src/main/resources/view/details.fxml')
source = text('myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/book/ResolveBookContentUseCase.java')
inspection = text('myhomelib-reader/src/main/java/com/myhomelibcorp/reader/inspection/BookInspectionService.java')
binary = text('myhomelib-reader/src/main/java/com/myhomelibcorp/reader/inspection/BinaryMetadataInspector.java')
cover = text('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/cover/CoverReaderImpl.java')

for token in ['List<AuthorDto> authors','List<GenreDto> genreItems','String translators','String sourceUrl','int libraryRate']:
    require(token in book, f'BookDto missing rich-details field: {token}')
for token in ['toAuthorDtos(book)','toGenreDtos(book)','book.getTranslators()','book.getSourceUrl()']:
    require(token in mapper, f'BookMapper missing full detail mapping: {token}')
require('loadBookByIdUseCase.execute' in analysis, 'details still trusts partial table BookDto')
require('backgroundExecutor.submit' in controller and 'loadGeneration' in controller, 'details analysis is not async/stale-safe')
for token in ['navigateToAuthor','navigateToSeriesByName','navigateToGenre','navigateToKeyword','navigateToPublisher']:
    require(token in controller, f'missing detail deep-link: {token}')
for token in ['tocPreviewBox','Pagination','openImage','wordCount','sourceLanguageLabel','translatorsLabel','reviewArea']:
    require(token in controller or token in fxml, f'rich panel missing {token}')
require('ResolveBookContentUseCase' in analysis and 'ArchiveSafetyLimits.MAX_ENTRY_BYTES' in source, 'archive materialization is not bounded behind application use case')
require('DETAILS_EXTENSIONS' in source and all(x in source for x in ['"mobi"','"pdf"','"djvu"']), 'extra formats not resolvable')
for token in ['countWords','flattenToc','scanFb2SourceLanguage','DocumentImageInfo','resources().open']:
    require(token in inspection, f'reader inspection missing {token}')
for token in ['inspectMobi','inspectPdf','inspectDjvu','EXTH','pdfField(latin, "Title")']:
    require(token in binary, f'extra metadata inspector missing {token}')
for token in ['EpubCoverParser','MobiCoverParser','PdfCoverParser','FallbackCoverRenderer','extractFromDocument']:
    require(token in cover, f'cover dispatch missing {token}')

# All view FXML must remain well formed.
fxml_files=list((ROOT/'myhomelib-ui/src/main/resources/view').glob('*.fxml'))
for p in fxml_files: ET.parse(p)

# Existing migration chain must remain intact.
mig_dir=ROOT/'myhomelib-infrastructure/src/main/resources/db/migration'
migs=sorted(mig_dir.glob('V*.sql'), key=lambda p:int(re.match(r'V(\d+)',p.name).group(1)))
con=sqlite3.connect(':memory:')
for p in migs: con.executescript(p.read_text(encoding='utf-8'))
con.close()

# Large-library invariant: no reintroduction of full author materialization.
hits=[]
for p in ROOT.glob('myhomelib-*/src/main/**/*.java'):
    s=p.read_text(encoding='utf-8',errors='ignore')
    if 'authorRepository.findAll()' in s or 'dictionaryCache.loadAuthors' in s: hits.append(str(p.relative_to(ROOT)))
require(not hits, f'eager author materialization reintroduced: {hits}')

# Regression tests for the new pipeline must be present.
for p in [
    'myhomelib-reader/src/test/java/com/myhomelibcorp/reader/inspection/BinaryMetadataInspectorTest.java',
    'myhomelib-reader/src/test/java/com/myhomelibcorp/reader/inspection/BookInspectionServiceTest.java',
    'myhomelib-infrastructure/src/test/java/com/myhomelibcorp/infrastructure/image/RichCoverParsersTest.java']:
    require((ROOT/p).exists(), f'missing regression test {p}')

print('STAGE 10+11 RICH DETAILS CHECK: PASS')
print(f' - {len(fxml_files)} FXML files parsed: PASS')
print(f' - {len(migs)} SQLite migrations applied: PASS')
print(' - full BookDto reload + deep links: PASS')
print(' - bounded FB2/EPUB TOC/word/image inspection: PASS')
print(' - MOBI/PDF/DjVu metadata fallback: PASS')
print(' - EPUB/MOBI/PDF/DjVu cover dispatch: PASS')
print(' - no eager author-table materialization: PASS')
