#!/usr/bin/env python3
"""Static audit for end-user UI wiring and context-help coverage.

This is intentionally structural: it proves that declared JavaFX entry points are wired,
core commands are registered, and every workspace state has a context-help route. It does
not replace behavioral/E2E or platform acceptance tests.
"""
from pathlib import Path
import json
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / "myhomelib-ui"
RES = UI / "src/main/resources"
JAVA = UI / "src/main/java"
FXNS = "http://javafx.com/fxml/1"


def fail(message: str) -> None:
    print(f"USER-FACING FUNCTIONAL AUDIT: FAIL - {message}")
    sys.exit(1)


bindings = []
missing_handlers = []
controllers = set()
for fxml in sorted(RES.rglob("*.fxml")):
    try:
        tree = ET.parse(fxml)
    except ET.ParseError as exc:
        fail(f"invalid FXML {fxml.relative_to(ROOT)}: {exc}")
    root = tree.getroot()
    controller = root.attrib.get(f"{{{FXNS}}}controller", "").strip()
    if controller:
        controllers.add(controller)
        controller_file = JAVA / Path(*controller.split(".")).with_suffix(".java")
        if not controller_file.exists():
            fail(f"missing controller source for {fxml.relative_to(ROOT)}: {controller}")
        source = controller_file.read_text(encoding="utf-8")
    else:
        controller_file = None
        source = ""
    for element in tree.iter():
        for attr in ("onAction", "onMouseClicked", "onKeyPressed"):
            value = element.attrib.get(attr, "")
            if not value.startswith("#"):
                continue
            handler = value[1:]
            bindings.append((str(fxml.relative_to(RES)).replace("\\", "/"), controller, attr, handler))
            if not controller or not re.search(r"\b" + re.escape(handler) + r"\s*\(", source):
                missing_handlers.append((fxml, controller, handler))

if missing_handlers:
    fail(f"unresolved FXML handlers: {missing_handlers[:10]}")

core_source = (JAVA / "com/myhomelibcorp/ui/action/CoreActions.java").read_text(encoding="utf-8")
main_source = (JAVA / "com/myhomelibcorp/ui/controller/MainController.java").read_text(encoding="utf-8")
core_actions = re.findall(r"public static final ActionDefinition\s+(\w+)\s*=", core_source)
registrations = re.findall(r"actionRegistry\.register\(CoreActions\.(\w+)", main_source)
if set(core_actions) != set(registrations) or len(registrations) != len(set(registrations)):
    fail(f"core action registry mismatch: defined={core_actions}, registered={registrations}")

workspace_source = (JAVA / "com/myhomelibcorp/ui/navigation/WorkspaceManager.java").read_text(encoding="utf-8")
registry_source = (JAVA / "com/myhomelibcorp/ui/service/HelpTopicRegistry.java").read_text(encoding="utf-8")
workspace_types = set(re.findall(r'push\("([^"]+)"', workspace_source))
workspace_types.discard("search-results")  # normalized explicitly by currentHelpTopic().
workspace_map_section = registry_source.split("private static final Map<String, String> CONTEXTS", 1)[0]
workspace_mappings = dict(re.findall(r'Map\.entry\("([^"]+)",\s*"([^"]+)"\)', workspace_map_section))
unmapped = sorted(workspace_types - set(workspace_mappings))
if unmapped:
    fail(f"workspace help mappings missing: {unmapped}")

topic_match = re.search(r"private static final Set<String> TOPICS = Set\.of\((.*?)\);", registry_source, re.S)
if not topic_match:
    fail("cannot parse HelpTopicRegistry.TOPICS")
topics = set(re.findall(r'"([^"]+)"', topic_match.group(1)))
missing_help = sorted(topic for topic in topics if not any((RES / "help" / f"{topic}{ext}").exists() for ext in (".md", ".txt", ".html")))
if missing_help:
    fail(f"bundled Ukrainian/default help resources missing: {missing_help}")

programmatic_classes = 0
programmatic_actions = 0
for source_file in list(JAVA.rglob("*.java")) + list((ROOT / "myhomelib-reader/src/main/java").rglob("*.java")):
    text = source_file.read_text(encoding="utf-8", errors="ignore")
    count = len(re.findall(r"\.setOnAction\s*\(", text))
    if count:
        programmatic_classes += 1
        programmatic_actions += count

result = {
    "fxml_files": len(list(RES.rglob("*.fxml"))),
    "fxml_event_bindings": len(bindings),
    "unique_controller_handlers": len({(controller, handler) for _, controller, _, handler in bindings}),
    "controllers": len(controllers),
    "missing_handler_references": 0,
    "core_actions_defined": len(core_actions),
    "core_actions_registered": len(registrations),
    "workspace_help_routes": len(workspace_types),
    "workspace_help_unmapped": 0,
    "known_help_topics": len(topics),
    "missing_default_help_resources": 0,
    "programmatic_action_classes": programmatic_classes,
    "programmatic_set_on_action_calls": programmatic_actions,
}
print("USER-FACING FUNCTIONAL AUDIT: PASS")
for key, value in result.items():
    print(f" - {key}: {value}")
