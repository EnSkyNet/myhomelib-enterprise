#!/usr/bin/env python3
"""Offline guard for user-facing UI/use-case reachability in v7.1."""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
UI_JAVA = ROOT / 'myhomelib-ui/src/main/java'
UI_RES = ROOT / 'myhomelib-ui/src/main/resources'
USECASE_ROOT = ROOT / 'myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase'

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

# Every application use case must have an intended direct entry area. This is intentionally textual:
# Spring/FXML/reflection classes are not deleted from this result alone; failures are manual-review blockers.
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

usecase_count = 0
for p in USECASE_ROOT.rglob('*.java'):
    name = p.stem
    if not (name.endswith('UseCase') or name.endswith('Checker')):
        continue
    text = p.read_text(encoding='utf-8', errors='ignore')
    if not re.search(r'\bclass\s+' + re.escape(name) + r'\b', text):
        continue
    usecase_count += 1
    if not any(re.search(r'\b' + re.escape(name) + r'\b', corpus) for corpus in entry_text.values()):
        errors.append(f'use case has no direct UI/MCP/OPDS entry reference: {name}')

# Known v7.1 dead bean must stay deleted; it created its own pool but had no operational caller.
if (UI_JAVA / 'com/myhomelibcorp/ui/service/BackgroundTaskService.java').exists():
    errors.append('dead BackgroundTaskService returned')

if errors:
    print('UI FUNCTION REACHABILITY CHECK: FAIL')
    for e in errors:
        print(' -', e)
    sys.exit(1)
print('UI FUNCTION REACHABILITY CHECK: PASS')
print(f' - FXML handler references checked: {handler_count}')
print(f' - application use cases with direct UI/MCP/OPDS entry: {usecase_count}')
print(' - dead BackgroundTaskService absent: PASS')
