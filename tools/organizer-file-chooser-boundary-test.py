#!/usr/bin/env python3
from pathlib import Path
import sys
root=Path(__file__).resolve().parents[1]
p=root/'myhomelib-ui/src/main/java/com/myhomelibcorp/ui/organizer/LibraryOrganizerUiService.java'
text=p.read_text(encoding='utf-8')
problems=[]
for token in ('import javafx.stage.DirectoryChooser;', 'import javafx.stage.FileChooser;', 'new DirectoryChooser(', 'new FileChooser('):
    if token in text: problems.append('LibraryOrganizerUiService bypasses FileChooserService via '+token)
if 'FileChooserService' not in text:
    problems.append('LibraryOrganizerUiService missing FileChooserService boundary')
if problems:
    print('FAIL: organizer native chooser boundary')
    for x in problems: print(' -',x)
    sys.exit(1)
print('PASS: Organizer uses FileChooserService boundary')
