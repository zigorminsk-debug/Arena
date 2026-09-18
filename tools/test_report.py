#!/usr/bin/env python3
"""Сводка по юнит-тестам: печатает список тестов, пишет отчёт в $GITHUB_STEP_SUMMARY.

Запуск:  python3 tools/test_report.py [маска-файлов]
Код возврата 1 — тесты не найдены или были провалы.
"""

from __future__ import annotations

import glob
import os
import sys
import xml.etree.ElementTree as ET

MARKS = {"ok": "✓", "failure": "✗", "error": "✗", "skipped": "−"}


def main(argv: list[str]) -> int:
    pattern = argv[1] if len(argv) > 1 else "app/build/test-results/testDebugUnitTest/*.xml"
    files = sorted(glob.glob(pattern))
    if not files:
        print(f"::error::не найдено результатов юнит-тестов ({pattern}) — тесты не запускались")
        return 1

    total = failures = errors = skipped = 0
    cases: list[tuple[str, str, str]] = []

    for path in files:
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as exc:
            print(f"::error::не читается отчёт {path}: {exc}")
            return 1

        total += int(root.get("tests") or 0)
        failures += int(root.get("failures") or 0)
        errors += int(root.get("errors") or 0)
        skipped += int(root.get("skipped") or 0)

        for case in root.iter("testcase"):
            if case.find("failure") is not None:
                status = "failure"
            elif case.find("error") is not None:
                status = "error"
            elif case.find("skipped") is not None:
                status = "skipped"
            else:
                status = "ok"
            cases.append(
                (case.get("classname") or "", case.get("name") or "", status)
            )

    print(f"Юнит-тесты: {total} (провалов {failures}, ошибок {errors}, пропущено {skipped})")
    for classname, name, status in cases:
        print(f"  {MARKS[status]} {classname.split('.')[-1]}: {name}")

    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        with open(summary_path, "a", encoding="utf-8") as out:
            out.write(
                f"### Юнит-тесты: {total}, провалов {failures}, ошибок {errors}, "
                f"пропущено {skipped}\n\n"
            )
            for classname, name, status in cases:
                out.write(f"* {MARKS[status]} `{classname.split('.')[-1]}`: {name}\n")
            out.write("\n")

    if failures or errors:
        print(f"::error::юнит-тесты не прошли: провалов {failures}, ошибок {errors}")
        return 1
    if total == 0:
        print("::error::тесты не выполнились (0 тестов)")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
