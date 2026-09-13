#!/usr/bin/env python3
"""Offline guard for user-facing UI/use-case reachability in v7.1."""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
UI_JAVA = ROOT / 'myhomelib-ui/src/main/java'
UI_RES = ROOT / 'myhomelib-ui/src/main/resources'
APP_ROOT = ROOT / 'myhomelib-application/src/main/java/com/myhomelibcorp/application'
USECASE_ROOT = APP_ROOT / 'usecase'

FX = '{http://javafx.com/fxml/1}'
errors = []
handler_count = 0

java_text = {p: p.read_text(encoding='utf-8', errors='ignore') for p in UI_JAVA.rglob('*.java')}
by_class = {p.stem: (p, t) for p, t in java_text.items()}

for fxml in UI_RES.rglob('*.fxml'):
    try:
        root = ET.parse(fxml).getroot()
    except Exception as e:
        errors.append(f'{fxml.relative_to(ROOT)}: XML parse failed: {e}')
        continue
    controller = root.attrib.get(FX + 'controller', '')
    controller_name = controller.rsplit('.', 1)[-1] if controller else ''
    controller_text = by_class.get(controller_name, (None, ''))[1]
    for node in root.iter():
        # JavaFX Control.tooltip is a Tooltip object, not a String. A shortcut such as
        # tooltip="..." is valid XML but fails at runtime in FXMLLoader with BeanAdapter.coerce().
        if 'tooltip' in node.attrib:
            errors.append(
                f'{fxml.relative_to(ROOT)}: string tooltip attribute is not FXMLLoader-safe; '
                'use <tooltip><Tooltip text="..."/></tooltip>'
            )
        for event, value in node.attrib.items():
            if not value.startswith('#'):
                continue
            handler_count += 1
            handler = value[1:]
            if not controller_name:
                errors.append(f'{fxml.relative_to(ROOT)}: #{handler} has no fx:controller')
                continue
            if not re.search(r'\b' + re.escape(handler) + r'\s*\(', controller_text):
                errors.append(f'{fxml.relative_to(ROOT)}: {controller_name}.#{handler} not found')

# Every application use case must be reachable from an intended UI/MCP/OPDS entry area.
# Reachability is allowed through application-layer facades/services (for example LibraryHealthService
# -> DataIntegrityChecker) so the guard does not force UI controllers to bypass orchestration boundaries.
# This remains intentionally textual: failures are manual-review blockers, not automatic deletion signals.
entry_roots = {
    'UI': ROOT / 'myhomelib-ui/src/main',
    'MCP': ROOT / 'myhomelib-mcp/src/main',
    'OPDS': ROOT / 'myhomelib-opds/src/main',
}
entry_text = {}
for name, base in entry_roots.items():
    chunks = []
    if base.exists():
        for p in base.rglob('*'):
            if p.is_file() and p.suffix in {'.java', '.fxml', '.xml'}:
                chunks.append(p.read_text(encoding='utf-8', errors='ignore'))
    entry_text[name] = '\n'.join(chunks)

# Build a simple-name application dependency graph and walk from application classes directly
# referenced by an entry surface. This recognizes intentional application facades without making
# the guard dependent on Spring runtime wiring.
app_sources = {}
for p in APP_ROOT.rglob('*.java'):
    text = p.read_text(encoding='utf-8', errors='ignore')
    if re.search(r'\b(?:class|record|interface|enum)\s+' + re.escape(p.stem) + r'\b', text):
        app_sources[p.stem] = text

entry_corpus = '\n'.join(entry_text.values())
reachable_app = {name for name in app_sources
                 if re.search(r'\b' + re.escape(name) + r'\b', entry_corpus)}
changed = True
while changed:
    changed = False
    for owner in tuple(reachable_app):
        owner_text = app_sources.get(owner, '')
        for candidate in app_sources:
            if candidate in reachable_app:
                continue
            if re.search(r'\b' + re.escape(candidate) + r'\b', owner_text):
                reachable_app.add(candidate)
                changed = True

usecase_count = 0
for p in USECASE_ROOT.rglob('*.java'):
    name = p.stem
    if not (name.endswith('UseCase') or name.endswith('Checker')):
        continue
    text = p.read_text(encoding='utf-8', errors='ignore')
    if not re.search(r'\bclass\s+' + re.escape(name) + r'\b', text):
        continue
    usecase_count += 1
    if name not in reachable_app:
        errors.append(f'use case has no UI/MCP/OPDS-reachable application path: {name}')

# Known v7.1 dead bean must stay deleted; it created its own pool but had no operational caller.
if (UI_JAVA / 'com/myhomelibcorp/ui/service/BackgroundTaskService.java').exists():
    errors.append('dead BackgroundTaskService returned')

book_workspace = java_text.get(UI_JAVA / 'com/myhomelibcorp/ui/book/BookWorkspaceController.java', '')
if 'navigateToAuthor(null)' in book_workspace:
    errors.append('Book workspace back/delete path must not navigate to a null author')
if 'navigateBackOrToAllBooks()' not in book_workspace:
    errors.append('Book workspace must have a valid fallback when navigation history is empty')

if errors:
    print('UI FUNCTION REACHABILITY CHECK: FAIL')
    for e in errors:
        print(' -', e)
    sys.exit(1)
print('UI FUNCTION REACHABILITY CHECK: PASS')
print(f' - FXML handler references checked: {handler_count}')
print(f' - application use cases reachable from UI/MCP/OPDS: {usecase_count}')
print(' - dead BackgroundTaskService absent: PASS')
