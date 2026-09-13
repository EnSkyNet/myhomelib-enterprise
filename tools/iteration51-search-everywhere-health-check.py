#!/usr/bin/env python3
from pathlib import Path
import json, sys
ROOT=Path(__file__).resolve().parents[1]
checks=[]
def check(name, cond, detail=''):
    checks.append((name, bool(cond), detail))

def text(rel): return (ROOT/rel).read_text(encoding='utf-8')

controller=text('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/search/SearchWorkspaceController.java')
fxml=text('myhomelib-ui/src/main/resources/view/search-workspace.fxml')
reader=text('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/reader/NewReaderWorkspaceController.java')
health=text('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/controller/IntegrityCheckController.java')
healthfxml=text('myhomelib-ui/src/main/resources/view/integrity-check.fxml')
port=text('myhomelib-application/src/main/java/com/myhomelibcorp/application/port/out/content/ContentIndexPort.java')
lucene=text('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/contentindex/LuceneContentIndexService.java')
maintenance=text('myhomelib-application/src/main/java/com/myhomelibcorp/application/content/maintenance/ContentIndexMaintenanceService.java')

check('MHL-304 modes', all(x in controller for x in ['METADATA("ui.search.mode.metadata")','CONTENTS("ui.search.mode.contents")','BOTH("ui.search.mode.both")']))
check('MHL-304 FXML results', all(x in fxml for x in ['searchModeChoice','contentSection','contentListView','contentCountLabel']))
check('MHL-304 cancellable', 'submitCancellable' in controller and 'task.cancel(true)' in controller)
check('MHL-304 snippets/offset', 'snippet' in lucene and 'matchOffset' in lucene and 'showContentHitInReader' in controller)
check('MHL-304 reader jump', 'jumpToContentSearchTarget' in reader and 'goToPosition(position)' in reader)
check('MHL-305 health fields', all(x in health for x in ['schemaVersion()','documentCount()','sizeBytes()','compatible()']))
check('MHL-305 UI progress', all(x in healthfxml for x in ['contentIndexProgress','contentIndexRebuildButton','contentIndexCancelButton']))
check('MHL-305 safe separate rebuild', 'Iterable<ContentIndexEntry>' in port and '.rebuild-' in lucene and 'swapDirectories' in lucene)
check('MHL-305 streamed rebuild', 'entries::iterator' in maintenance and 'books.streamAll()' in maintenance)
check('MHL-305 metadata/content separated', 'Database Tools' in health)
for lang in ('uk','en','bg'):
    root=json.loads(text(f'Lang/{lang}.json')).get('translations', {})
    bundled=json.loads(text(f'myhomelib-ui/src/main/resources/lang/default/{lang}.json')).get('translations', {})
    for key in ('ui.search.mode.metadata','ui.search.mode.contents','ui.search.mode.both','ui.search.contents.title','ui.search.contents.status'):
        check(f'i18n {lang} {key}', key in root and key in bundled)

failed=[c for c in checks if not c[1]]
for name, ok, detail in checks: print(('PASS' if ok else 'FAIL')+': '+name+((' — '+detail) if detail else ''))
print(f'RESULT: {len(checks)-len(failed)}/{len(checks)} PASS')
sys.exit(1 if failed else 0)
