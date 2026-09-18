#!/usr/bin/env python3
"""Проверка собранного манифеста по выводу ``aapt2 dump xmltree``.

В APK атрибут ``android:configChanges`` хранится как битовая маска, поэтому
просто искать строки «orientation»/«screenSize» в файле нельзя — нужен разбор
бинарного XML. Его и делает aapt2, а этот скрипт проверяет результат: у каждого
``<activity>`` набор флагов должен покрывать поворот экрана, иначе Activity
пересоздаётся вместе с WebView, и введённый текст с вложениями теряется.

Запуск:
    aapt2 dump xmltree --file AndroidManifest.xml app.apk > manifest.txt
    python3 tools/check_manifest.py manifest.txt

Код возврата 0 — всё хорошо, 1 — есть проблемы.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

# ActivityInfo: ORIENTATION 0x80, SCREEN_LAYOUT 0x100, SCREEN_SIZE 0x400
CONFIG_ORIENTATION = 0x0080
CONFIG_SCREEN_LAYOUT = 0x0100
CONFIG_SCREEN_SIZE = 0x0400
REQUIRED_MASK = CONFIG_ORIENTATION | CONFIG_SCREEN_LAYOUT | CONFIG_SCREEN_SIZE

MASK_NAMES = (
    (CONFIG_ORIENTATION, "orientation"),
    (CONFIG_SCREEN_LAYOUT, "screenLayout"),
    (CONFIG_SCREEN_SIZE, "screenSize"),
)

ACTIVITY_RE = re.compile(r"^\s*E:\s*activity\b")
CONFIG_RE = re.compile(r"android:configChanges[^=]*=\s*(?:\(type\s+0x[0-9a-fA-F]+\))?\s*(0x[0-9a-fA-F]+|\d+)")
NAME_RE = re.compile(r'android:name[^=]*=\s*"([^"]+)"')


def parse_tree(text: str) -> list[tuple[str, int | None]]:
    """Возвращает пары (имя activity, маска configChanges или None)."""
    activities: list[tuple[str, int | None]] = []
    current: str | None = None
    mask: int | None = None

    def flush() -> None:
        nonlocal current, mask
        if current is not None:
            activities.append((current, mask))
        current = None
        mask = None

    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith("E: ") or stripped.startswith("C: "):
            # новый элемент — закрываем предыдущий activity
            if ACTIVITY_RE.match(line):
                flush()
                name_match = NAME_RE.search(line)
                current = name_match.group(1) if name_match else "?"
            else:
                flush()
            continue

        if current is None:
            continue

        # имя и флаги лежат в строках атрибутов
        if "configChanges" in stripped:
            match = CONFIG_RE.search(stripped)
            if match:
                raw = match.group(1)
                mask = int(raw, 16) if raw.lower().startswith("0x") else int(raw)
        elif current == "?" and "android:name" in stripped:
            name_match = NAME_RE.search(stripped)
            if name_match:
                current = name_match.group(1)

    flush()
    return activities


def check(text: str) -> list[str]:
    """Список проблем; пустой список — манифест в порядке."""
    problems: list[str] = []
    activities = parse_tree(text)

    if not activities:
        problems.append(
            "в выводе aapt2 не найдено ни одного <activity> — манифест не разобран"
        )
        return problems

    for name, mask in activities:
        if mask is None:
            problems.append(f"{name}: нет android:configChanges — поворот пересоздаст WebView")
            continue
        if mask & REQUIRED_MASK != REQUIRED_MASK:
            missing = [title for bit, title in MASK_NAMES if not mask & bit]
            problems.append(
                f"{name}: configChanges={mask:#06x} не покрывает {', '.join(missing)}"
            )
    return problems


def main(argv: list[str]) -> int:
    source = argv[1] if len(argv) > 1 else "-"
    if source == "-":
        text = sys.stdin.read()
    else:
        path = Path(source)
        if not path.exists():
            print(f"::error::файл {path} не найден")
            return 1
        text = path.read_text(encoding="utf-8", errors="ignore")

    problems = check(text)
    if problems:
        for problem in problems:
            print(f"::error::{problem}")
        return 1

    activities = parse_tree(text)
    print(f"Поворот экрана: configChanges проверен у {len(activities)} activity в собранном APK")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
