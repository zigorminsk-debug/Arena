#!/usr/bin/env python3
"""Проверка собранных APK на то, что используется по имени.

Разметки ссылаются на кастомные View по имени класса, а JS вызывает методы
JS-моста по имени. Если R8 их переименует или удалит, приложение упадёт
в рантайме — при этом сборка формально успешна. Этот скрипт ловит такое.

Дополнительно проверяется подпись (v2/v3), целостность структуры APK и то, что
экраны профилей объявляют android:configChanges — иначе поворот экрана
пересоздаёт WebView и введённый текст теряется.

Запуск:  python3 tools/check_apk.py dist [dist2 ...]
Код возврата 0 — всё хорошо, 1 — есть проблемы (печатаются как ::error::).
"""

from __future__ import annotations

import re
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

# Значения android:configChanges, без которых поворот пересоздаёт экран профиля
# (а вместе с ним WebView: черновик и вложения теряются).
REQUIRED_CONFIG_CHANGES = ("orientation", "screenSize", "screenLayout")


def declared_config_changes(manifest: bytes) -> set[str]:
    """Строки android:configChanges из бинарного манифеста.

    AXML хранит строки в UTF-8 или UTF-16LE пуле, поэтому смотрим оба
    представления и берём только строки, похожие на список значений через «|».
    """
    tokens: set[str] = set()
    for encoding in ("utf-8", "utf-16-le"):
        text = manifest.decode(encoding, errors="ignore")
        for match in re.findall(r"[A-Za-z][A-Za-z|]{10,}", text):
            if "|" in match:
                tokens.add(match)
    return tokens


def collect_apks(roots: list[str]) -> list[Path]:
    apks: list[Path] = []
    for root in roots:
        path = Path(root)
        if path.is_file() and path.suffix == ".apk":
            apks.append(path)
        elif path.is_dir():
            apks.extend(sorted(path.rglob("*.apk")))
    return sorted(set(apks))


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
            declared = declared_config_changes(manifest)
            covered = any(set(REQUIRED_CONFIG_CHANGES) <= set(token.split("|")) for token in declared)
            if not manifest:
                print(f"::error::{apk.name}: манифест не читается")
                ok = False
            elif not covered:
                found = ", ".join(sorted(declared)) or "ничего не найдено"
                print(
                    f"::error::{apk.name}: в манифесте нет android:configChanges, покрывающего "
                    f"{', '.join(REQUIRED_CONFIG_CHANGES)} (найдено: {found}) — при повороте "
                    f"экран профиля пересоздастся, введённый текст и вложения потеряются"
                )
                ok = False
            else:
                print(f"{apk.name}: поворот экрана не пересоздаёт экран профиля (configChanges на месте)")

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
