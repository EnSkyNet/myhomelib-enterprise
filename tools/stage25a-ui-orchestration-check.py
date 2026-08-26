#!/usr/bin/env python3
from pathlib import Path
import sys
ROOT=Path(__file__).resolve().parents[1]
errors=[]
def need(c,m):
    if not c: errors.append(m)
def text(rel): return (ROOT/rel).read_text(encoding='utf-8')

main=text('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/controller/MainController.java')
workspace=text('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/navigation/WorkspaceManager.java')
history=text('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/NavigationHistoryService.java')
defnav=text('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/DefaultNavigationService.java')
details=text('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/details/BookDetailsController.java')
navcoord=text('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/navigation/MainNavigationCoordinator.java')
bookcoord=text('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/MainBookCommandCoordinator.java')
test=text('myhomelib-ui/src/test/java/com/myhomelibcorp/ui/navigation/WorkspaceManagerNavigationStateTest.java')

need(len(main.splitlines()) <= 660, f'MainController still too large: {len(main.splitlines())} lines')
need('setMainController(' not in main, 'MainController must not wire callback cycles')
need('MainController' not in workspace, 'WorkspaceManager must not depend on MainController')
need('MainController' not in history, 'NavigationHistoryService must not depend on MainController')
need('MainController' not in defnav, 'DefaultNavigationService must not depend on MainController')
need('MainController' not in details, 'BookDetailsController must not depend on MainController')
need('ReadOnlyBooleanProperty canGoBackProperty()' in workspace and 'ReadOnlyBooleanProperty canGoForwardProperty()' in workspace,
     'WorkspaceManager observable history state missing')
need('disableProperty().bind(workspaceManager.canGoBackProperty().not())' in main, 'back button not property-bound')
need('disableProperty().bind(workspaceManager.canGoForwardProperty().not())' in main, 'forward button not property-bound')
for marker in ['authors()','updates()','populateRecentBooksMenu','clearHistory']:
    need(marker in navcoord, f'MainNavigationCoordinator missing {marker}')
for marker in ['openInternal()','openExternal()','removeLocalCopy','editMetadata','deleteBook']:
    need(marker in bookcoord, f'MainBookCommandCoordinator missing {marker}')
need('exposesBackForwardStateWithoutMainControllerCallbacks' in test, 'WorkspaceManager state regression test missing')
need('docs/performance-baseline.json' in [str(p.relative_to(ROOT)) for p in ROOT.glob('docs/performance-baseline.json')], 'Stage24 baseline missing before refactor')

# No production UI class other than MainController itself may import the concrete shell after Stage25A.
for p in (ROOT/'myhomelib-ui/src/main/java').rglob('*.java'):
    if p.name == 'MainController.java': continue
    if 'import com.myhomelibcorp.ui.controller.MainController;' in p.read_text(encoding='utf-8'):
        errors.append(f'concrete MainController dependency remains: {p.relative_to(ROOT)}')

if errors:
    print('STAGE 25A UI ORCHESTRATION CHECK: FAILED')
    for e in errors: print(' -',e)
    sys.exit(1)
print('STAGE 25A UI ORCHESTRATION CHECK: PASS')
print(f' - MainController reduced to {len(main.splitlines())} lines with FXML shell compatibility')
print(' - WorkspaceManager owns observable back/forward state; callback cycle removed')
print(' - navigation/book command orchestration extracted; concrete MainController fan-in removed')
print(' - history-state JUnit regression fixture: PRESENT')
