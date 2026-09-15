#!/usr/bin/env python3
from pathlib import Path
import json, sys
ROOT = Path(__file__).resolve().parents[1]
errors=[]
def need(cond,msg):
    if not cond: errors.append(msg)
def text(rel): return (ROOT/rel).read_text(encoding='utf-8')

enum=text('myhomelib-application/src/main/java/com/myhomelibcorp/application/filter/BookAnnotationPresenceFilter.java')
spec=text('myhomelib-application/src/main/java/com/myhomelibcorp/application/filter/BookFilterSpec.java')
sql=text('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/persistence/sqlite/helper/BookFilterSqlAdapter.java')
lucene=text('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/search/LuceneDocumentMapper.java')
activity=text('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/persistence/sqlite/SqliteBookActivityQueryAdapter.java')
fxml=text('myhomelib-ui/src/main/resources/view/book-table.fxml')
dialog=text('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/filter/BookFilterDialogService.java')
ci=text('.github/workflows/ci-pr.yml')
profile=text('tools/reader-memory-jfr-profile.sh')

for value in ('ANY','NOTES','HIGHLIGHTS','NOTES_OR_HIGHLIGHTS'):
    need(value in enum, f'annotation-presence enum missing {value}')
need('BookAnnotationPresenceFilter annotationPresence' in spec, 'BookFilterSpec annotationPresence missing')
need("annotation_type = 'NOTE'" in sql and "annotation_type = 'HIGHLIGHT'" in sql, 'SQLite annotation filters missing')
need('has_note' in lucene and 'has_highlight' in lucene and 'custom-fields-v2-activity' in lucene, 'Lucene activity schema missing')
need('GROUP BY book_id' in activity and 'bookmarks' in activity, 'bulk activity aggregation missing')
need('fx:id="activityColumn"' in fxml, 'catalog activity column missing')
need('annotationPresence' in dialog and 'ui.filter.annotation.notes' in dialog, 'filter dialog activity selector missing')
need('--min-tests 8 --min-suites 6' in ci, 'expanded JavaFX gate threshold missing')
need('for mb in 20 50 100' in profile and 'StartFlightRecording' in profile, '20/50/100 MB JFR profile gate missing')

for rel in ('Lang/uk.json','Lang/en.json','Lang/bg.json','myhomelib-ui/src/main/resources/lang/default/uk.json','myhomelib-ui/src/main/resources/lang/default/en.json','myhomelib-ui/src/main/resources/lang/default/bg.json'):
    data=json.loads((ROOT/rel).read_text(encoding='utf-8'))
    tr=data.get('translations',{})
    for key in ('ui.book.activity','ui.book.activity.tooltip','ui.filter.annotation.label','ui.filter.annotation.notes','ui.filter.annotation.highlights'):
        need(bool(tr.get(key)), f'{rel}: missing translation {key}')

if errors:
    print('ITERATION85_FINISH_POLISH_CHECK_FAILED')
    for e in errors: print(' -',e)
    sys.exit(1)
print('ITERATION85_FINISH_POLISH_CHECK_OK')
