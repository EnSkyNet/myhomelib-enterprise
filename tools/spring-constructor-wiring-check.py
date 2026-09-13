#!/usr/bin/env python3
"""Fail closed for Spring stereotype classes with ambiguous constructor wiring.

Spring can infer a single constructor without @Autowired and can use an explicit no-arg
constructor. When a stereotype has multiple constructors, no no-arg constructor and no
explicit @Autowired constructor, runtime component scanning can fall back to no-arg
instantiation and fail only on a real host. This gate catches that source shape.
"""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
STEREOTYPE = re.compile(r"@(Component|Service|Repository|Controller|Configuration)\b")
CLASS = re.compile(r"\bclass\s+(\w+)")


def constructor_params(source: str, class_name: str):
    pattern = re.compile(
        r"(?ms)^\s*(?:(?:public|protected|private)\s+)?"
        + re.escape(class_name)
        + r"\s*\((.*?)\)\s*\{"
    )
    return [m.group(1).strip() for m in pattern.finditer(source)]


def main() -> int:
    violations = []
    inspected = 0
    for path in sorted(ROOT.glob("*/src/main/java/**/*.java")):
        source = path.read_text(encoding="utf-8", errors="strict")
        if not STEREOTYPE.search(source):
            continue
        match = CLASS.search(source)
        if not match:
            continue
        inspected += 1
        class_name = match.group(1)
        constructors = constructor_params(source, class_name)
        if len(constructors) <= 1:
            continue
        has_no_arg = any(not params for params in constructors)
        has_explicit_autowired = "@Autowired" in source
        if not has_no_arg and not has_explicit_autowired:
            violations.append(
                f"{path.relative_to(ROOT)}: {class_name} has {len(constructors)} constructors, "
                "no no-arg constructor and no explicit @Autowired injection constructor"
            )

    if violations:
        print("Spring constructor wiring check: FAIL")
        for violation in violations:
            print(f" - {violation}")
        return 1

    print(f"Spring constructor wiring check: PASS ({inspected} stereotype classes inspected)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
