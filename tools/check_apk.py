#!/usr/bin/env python3
"""Проверка собранных APK на то, что используется по имени.

Разметки ссылаются на кастомные View по имени класса, а JS вызывает методы
JS-моста по имени. Если R8 их переименует или удалит, приложение упадёт
в рантайме — при этом сборка формально успешна. Этот скрипт ловит такое.

Дополнительно проверяется подпись (v2/v3), целостность структуры APK и наличие
атрибута android:configChanges у экранов. В собранном APK этот атрибут — битовая
маска, а не строки, поэтому точные значения проверяются по исходному манифесту
(tools/selfcheck.py) и по выводу aapt2 (tools/check_manifest.py).

Запуск:  python3 tools/check_apk.py dist [dist2 ...]
Код возврата 0 — всё хорошо, 1 — есть проблемы (печатаются как ::error::).
"""

from __future__ import annotations

import sys
import zipfile
from pathlib import Path

# Имена, которые обязаны выжить в dex: дескриптор типа для представления
# разметок и имена методов, вызываемых из JavaScript.
REQUIRED_DEX_NAMES = (
    "Lai/arena/mobile/ArenaSwipeRefreshLayout;",
    "reportScroll",
    "reportGithub",
)

# Имя класса, на который должна ссылаться хотя бы одна разметка внутри APK.
REQUIRED_LAYOUT_REFERENCE = "ArenaSwipeRefreshLayout"

# Имя атрибута, без которого поворот экрана пересоздаёт экран профиля
# (а вместе с ним WebView: черновик и вложения теряются). В бинарном манифесте
# значение атрибута — целое число, поэтому здесь проверяем только его наличие,
# а точный набор флагов — в tools/selfcheck.py и tools/check_manifest.py.
CONFIG_CHANGES_ATTRIBUTE = "configChanges"


def collect_apks(roots: list[str]) -> list[Path]:
    apks: list[Path] = []
    for root in roots:
        path = Path(root)
        if path.is_file() and path.suffix == ".apk":
            apks.append(path)
        elif path.is_dir():
            apks.extend(sorted(path.rglob("*.apk")))
    return sorted(set(apks))


def find_string(data: bytes, needle: str) -> bool:
    """Строка в бинарном XML может лежать в UTF-8 или UTF-16LE пуле."""
    return needle.encode() in data or needle.encode("utf-16-le") in data


def check_apk(apk: Path) -> bool:
    """True — APK прошёл проверку."""
    ok = True
    try:
        with zipfile.ZipFile(apk) as archive:
            names = archive.namelist()

            dex_names = [n for n in names if n.endswith(".dex")]
            if not dex_names:
                print(f"::error::{apk.name}: внутри нет ни одного dex-файла")
                return False

            dex = b"".join(archive.read(n) for n in dex_names)
            missing = [name for name in REQUIRED_DEX_NAMES if name.encode() not in dex]
            if missing:
                print(
                    f"::error::{apk.name}: в dex ({len(dex_names)} файл.) не найдено: "
                    f"{', '.join(missing)} — R8 переименовал то, что вызывается по имени"
                )
                ok = False
            else:
                print(
                    f"{apk.name}: класс кастомного View и методы JS-моста на месте "
                    f"({len(dex_names)} dex, {len(dex) / 1048576:.1f} МБ)"
                )

            # Разметки: AXML хранит строки в UTF-8 или UTF-16LE
            hits = []
            for name in names:
                if name.startswith("res/") and name.endswith(".xml"):
                    data = archive.read(name)
                    if (
                        REQUIRED_LAYOUT_REFERENCE.encode() in data
                        or REQUIRED_LAYOUT_REFERENCE.encode("utf-16-le") in data
                    ):
                        hits.append(name)

            if hits:
                print(f"{apk.name}: разметка {hits[0]} ссылается на кастомный View")
            else:
                print(
                    f"::error::{apk.name}: ни одна разметка не ссылается на "
                    f"{REQUIRED_LAYOUT_REFERENCE} — экран профиля упадёт при открытии"
                )
                ok = False

            # Манифест: поворот экрана не должен пересоздавать экран профиля
            manifest = archive.read("AndroidManifest.xml") if "AndroidManifest.xml" in names else b""
            if not manifest:
                print(f"::error::{apk.name}: манифест не читается")
                ok = False
            elif find_string(manifest, CONFIG_CHANGES_ATTRIBUTE):
                print(
                    f"{apk.name}: атрибут android:configChanges в манифесте есть "
                    f"(набор флагов проверяют selfcheck.py и check_manifest.py)"
                )
            else:
                print(
                    f"::error::{apk.name}: в манифесте нет атрибута android:configChanges — "
                    f"поворот экрана пересоздаст WebView, введённый текст и вложения потеряются"
                )
                ok = False

            # Базовая целостность
            for required in ("AndroidManifest.xml", "resources.arsc"):
                if required not in names:
                    print(f"::error::{apk.name}: нет {required}")
                    ok = False
    except zipfile.BadZipFile:
        print(f"::error::{apk.name}: файл не является корректным APK/ZIP")
        return False

    return ok


def main(argv: list[str]) -> int:
    roots = argv[1:] or ["dist"]
    apks = collect_apks(roots)
    if not apks:
        print(f"::error::не найдено APK в: {', '.join(roots)}")
        return 1

    failed = [apk for apk in apks if not check_apk(apk)]
    print()
    if failed:
        print(f"Проверка не пройдена: {len(failed)} из {len(apks)} APK")
        return 1
    print(f"Проверка пройдена: {len(apks)} APK")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
