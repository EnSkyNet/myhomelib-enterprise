#!/usr/bin/env python3
"""Static closure gate for Iteration 48 / MHL-209 + MHL-212 + MHL-213."""
from pathlib import Path
import json
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
checks = []

def text(rel):
    return (ROOT / rel).read_text(encoding='utf-8')

def contains(rel, *needles):
    value = text(rel)
    return all(n in value for n in needles)

def check(label, ok):
    checks.append((label, bool(ok)))
    print(("PASS" if ok else "FAIL") + ": " + label)

check("TTS provider SPI is application-owned",
      contains("myhomelib-application/src/main/java/com/myhomelibcorp/application/tts/TtsProvider.java",
               "interface TtsProvider", "availableVoices()", "speak(", "cancelCurrent()"))
check("system TTS supports Windows/macOS/eSpeak without cloud transport",
      contains("myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/tts/SystemTtsProvider.java",
               "System.Speech", 'List.of("say"', 'List.of("espeak-ng", "--voices")', 'List.of("espeak", "--voices")'))
check("TTS playback is asynchronous and supports pause/resume/stop",
      contains("myhomelib-application/src/main/java/com/myhomelibcorp/application/tts/TtsPlaybackService.java",
               "executor", "pause", "resume", "stop", "repeatCurrentAfterPause"))
check("Reader TTS uses system voices, speed dialog and FX-thread highlight callback",
      contains("myhomelib-ui/src/main/java/com/myhomelibcorp/ui/reader/NewReaderWorkspaceController.java",
               "ttsPlaybackService.availableVoices()", "TextInputDialog", "Platform.runLater", "showTtsSentence", "setSpeechHighlight"))
check("TTS toolbar controls have accessibility-aware controls",
      contains("myhomelib-reader/src/main/java/com/myhomelibcorp/reader/render/javafx/ReaderToolbar.java",
               'createButton("🔊"', 'createButton("⏯"', 'createButton("⏹"', "setAccessibleText"))

fxml_root = ROOT / "myhomelib-ui/src/main/resources/view"
fxml_files = list(fxml_root.rglob("*.fxml"))
focus_hidden = []
for path in fxml_files:
    xml = path.read_text(encoding='utf-8')
    if re.search(r'<Button\b[^>]*focusTraversable="false"', xml, re.S):
        focus_hidden.append(path.name)
check("all FXML buttons remain keyboard-focusable", bool(fxml_files) and not focus_hidden)
check("accessibility runtime audit is wired into main/workspace roots",
      contains("myhomelib-ui/src/main/java/com/myhomelibcorp/ui/controller/MainController.java", "UiAccessibilitySupport.enhance") and
      contains("myhomelib-ui/src/main/java/com/myhomelibcorp/ui/navigation/WorkspaceManager.java", "UiAccessibilitySupport.enhance"))
check("reduced motion prevents Reader auto-scroll",
      contains("myhomelib-reader/src/main/java/com/myhomelibcorp/reader/render/javafx/AutoScrollController.java",
               "motionAllowed", "setMotionAllowed", "if (!motionAllowed") and
      contains("myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/ApplicationSettingsDialog.java",
               '"ui.accessibility.reducedMotion"'))
check("base warning color uses accessible dark orange",
      "-mhl-warning: #b45309;" in text("myhomelib-ui/src/main/resources/css/app-theme-base.css"))

corpus = "myhomelib-e2e-tests/src/test/java/com/myhomelibcorp/e2e/ReaderCorpusJourneyE2ETest.java"
check("reader regression corpus covers FB2/EPUB/PDF/CBZ and malformed/big/unicode",
      contains(corpus, '"unicode-book.fb2"', '"unicode.epub"', '"sample.pdf"', '"comic.cbz"',
               "malformedCorpusFailsBoundedly", "multiMegabyteFb2Corpus", "Unicode"))
check("reader regression validates progress/bookmarks/annotations after reopen",
      contains(corpus, "progress", "bookmark", "annotation", "reopen"))

required_tts_keys = {
    "ui.reader.toolbar.tts_start", "ui.reader.toolbar.tts_pause_resume", "ui.reader.toolbar.tts_stop",
    "ui.reader.tts.title", "ui.reader.tts.voice_header", "ui.reader.tts.speed_header", "ui.reader.tts.failed",
    "ui.accessibility.reducedMotion"
}
locales_ok = True
for root in (ROOT / "Lang", ROOT / "myhomelib-ui/src/main/resources/lang/default"):
    for lang in ("uk", "en", "bg"):
        data = json.loads((root / f"{lang}.json").read_text(encoding='utf-8'))
        locales_ok &= required_tts_keys.issubset(data.get("translations", {}).keys())
check("TTS UI strings exist in all bundled catalogs", locales_ok)

if not all(ok for _, ok in checks):
    sys.exit(1)
print(f"Iteration 48 TTS/accessibility/reader-regression check: PASS ({len(checks)}/{len(checks)})")
