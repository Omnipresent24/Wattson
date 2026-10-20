"""WATTSON fork: turn failed unit tests and lint errors into GitHub annotations.

Annotations show up on the run page and are readable through the check-runs API,
which is easier to get at than the raw logs. Usage: python3 report_failures.py
"""
import glob
import xml.etree.ElementTree as ET


def esc(text: str) -> str:
    return text.replace("%", "%25").replace("\r", "").replace("\n", "%0A")


def emit(title: str, message: str) -> None:
    print(f"::error title={esc(title)[:200]}::{esc(message)[:3000]}")


failures = []
for path in glob.glob("app/build/test-results/**/*.xml", recursive=True):
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError:
        continue
    for case in root.iter("testcase"):
        for tag in ("failure", "error"):
            node = case.find(tag)
            if node is not None:
                cls = case.get("classname", "").rsplit(".", 1)[-1]
                msg = (node.get("message") or node.text or "").strip()
                failures.append((f"{cls}.{case.get('name')}", msg[:1500]))

lint = []
for path in glob.glob("app/build/reports/lint-results*.xml"):
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError:
        continue
    for issue in root.iter("issue"):
        if issue.get("severity") in ("Error", "Fatal"):
            loc = issue.find("location")
            where = ""
            if loc is not None:
                where = f"{loc.get('file', '').split('/src/')[-1]}:{loc.get('line', '?')}"
            lint.append((issue.get("id"), f"{where} {issue.get('message')}"))

print(f"failed tests: {len(failures)}, lint errors: {len(lint)}")
for name, msg in failures[:8]:
    emit(f"TEST {name}", msg)
if len(failures) > 8:
    emit("TESTS (more)", "\n".join(n for n, _ in failures[8:60]))
for lid, msg in lint[:8]:
    emit(f"LINT {lid}", msg)
if len(lint) > 8:
    emit("LINT (more)", "\n".join(f"{i}: {m}" for i, m in lint[8:60]))
