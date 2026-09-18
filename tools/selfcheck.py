#!/usr/bin/env python3
"""Статическая самопроверка проекта без Gradle и Android SDK.

Ловит самые дорогие ошибки ещё до сборки: несуществующие id/строки/цвета,
опечатки в viewBinding, классы из манифеста без исходников, расхождения
переводов, невалидный XML.

Запуск:  python3 tools/selfcheck.py
Код возврата 0 — проблем нет, 1 — есть ошибки (печатаются списком).
"""

from __future__ import annotations

import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
APP = ROOT / "app" / "src" / "main"
RES = APP / "res"
KOTLIN = APP / "java"
MANIFEST = APP / "AndroidManifest.xml"

ANDROID_NS = "{http://schemas.android.com/apk/res/android}"

# ресурсы, которые приходят из Material/AppCompat, а не из этого модуля
LIBRARY_RESOURCES = {"appbar_scrolling_view_behavior", "bottom_sheet_behavior"}
errors: list[str] = []
warnings: list[str] = []


def err(msg: str) -> None:
    errors.append(msg)


def warn(msg: str) -> None:
    warnings.append(msg)


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


# ---------------------------------------------------------------- XML разбор

def parse_xml(path: Path) -> ET.Element | None:
    try:
        return ET.fromstring(read(path))
    except ET.ParseError as exc:
        err(f"XML сломан: {path.relative_to(ROOT)}: {exc}")
        return None
    except UnicodeDecodeError as exc:
        err(f"Файл не читается как UTF-8: {path.relative_to(ROOT)}: {exc}")
        return None


xml_files = sorted(RES.rglob("*.xml")) + [MANIFEST]
parsed: dict[Path, ET.Element] = {}
for f in xml_files:
    if not f.exists():
        err(f"нет файла {f.relative_to(ROOT)}")
        continue
    el = parse_xml(f)
    if el is not None:
        parsed[f] = el


def snake_to_camel(name: str) -> str:
    return "".join(part.capitalize() for part in name.split("_"))


# ------------------------------------------------------------------ ресурсы

resource_names: dict[str, set[str]] = {
    "string": set(),
    "color": set(),
    "drawable": set(),
    "menu": set(),
    "layout": set(),
    "bool": set(),
    "mipmap": set(),
    "style": set(),
    "array": set(),
    "plurals": set(),
    "dimen": set(),
}
value_dir_re = re.compile(r"^(values|values-[a-z]{2}(-[A-Za-z0-9]+)*)$")

for f in sorted(RES.rglob("*")):
    parent = f.parent.name
    if f.is_file() and f.suffix.lower() in {".png", ".webp", ".jpg", ".jpeg"}:
        # растровые drawable/mipmap: имя ресурса — имя файла без расширения
        raster_kind = parent.split("-")[0]
        if raster_kind in resource_names:
            resource_names[raster_kind].add(f.stem)
        continue
    if f.suffix != ".xml":
        continue
    if value_dir_re.match(parent):
        root = parsed.get(f)
        if root is None:
            continue
        for child in root:
            tag = child.tag
            if tag in resource_names and child.get("name"):
                resource_names[tag].add(child.get("name"))
    else:
        kind = parent.split("-")[0]
        if kind in resource_names:
            resource_names[kind].add(f.stem)

# id из разметок и меню
layout_ids: dict[str, set[str]] = {}
all_ids: set[str] = set()
for f in sorted(RES.rglob("*.xml")):
    parent = f.parent.name
    if not (parent.startswith("layout") or parent.startswith("menu")):
        continue
    root = parsed.get(f)
    if root is None:
        continue
    ids = {"root"}
    for node in root.iter():
        value = node.get(f"{ANDROID_NS}id") or node.get("id")
        if value and value.startswith("@+id/"):
            ids.add(value[len("@+id/"):])
        elif value and value.startswith("@id/"):
            ids.add(value[len("@id/"):])
    layout_ids[f.stem] = ids
    all_ids |= ids

# строки по локалям (для сверки переводов и плейсхолдеров)
def strings_of(path: Path) -> dict[str, str]:
    root = parsed.get(path)
    if root is None:
        return {}
    result = {}
    for child in root:
        if child.tag == "string" and child.get("name"):
            result[child.get("name")] = "".join(child.itertext())
    return result


strings_default = strings_of(RES / "values" / "strings.xml")
strings_ru = strings_of(RES / "values-ru" / "strings.xml")

for key in strings_default:
    if key not in strings_ru:
        err(f"перевод: строка {key!r} есть в values/, но нет в values-ru/")
for key in strings_ru:
    if key not in strings_default:
        err(f"перевод: строка {key!r} есть в values-ru/, но нет в values/")

placeholder_re = re.compile(r"%(\d+)\$[a-zA-Z]")


def placeholders(text: str) -> set[str]:
    return set(placeholder_re.findall(text))


for key, value in strings_default.items():
    if key in strings_ru and placeholders(value) != placeholders(strings_ru[key]):
        err(
            f"перевод: у строки {key!r} не совпадают плейсхолдеры "
            f"({sorted(placeholders(value))} против {sorted(placeholders(strings_ru[key]))})"
        )

# ------------------------------------------------------------------- Kotlin

kotlin_files = sorted(KOTLIN.rglob("*.kt"))
if not kotlin_files:
    err("не найдено ни одного .kt файла")

kotlin_classes: set[str] = set()
kotlin_sources: dict[Path, str] = {}
for f in kotlin_files:
    text = read(f)
    kotlin_sources[f] = text
    for match in re.finditer(r"^\s*(?:abstract\s+|open\s+|sealed\s+|data\s+)*(?:class|interface|object|enum class)\s+([A-Za-z_]\w*)", text, re.M):
        kotlin_classes.add(match.group(1))
    # баланс скобок — грубая проверка на обрезанный файл
    for opener, closer, label in (("{", "}", "фигурные"), ("(", ")", "круглые")):
        if text.count(opener) != text.count(closer):
            err(
                f"{f.relative_to(ROOT)}: несбалансированные {label} скобки "
                f"({text.count(opener)} против {text.count(closer)})"
            )

binding_class_re = re.compile(r"\b(\w+Binding)\b")
binding_member_re = re.compile(r"\bbinding\.([A-Za-z_]\w*)")
kotlin_ref_re = re.compile(r"(?<![\w.])R\.(\w+)\.(\w+)\b")

for f, text in kotlin_sources.items():
    rel = f.relative_to(ROOT)

    # 1. viewBinding: имена полей должны существовать в разметке
    classes = set(binding_class_re.findall(text))
    layout_for_binding: dict[str, str] = {}
    for cls in classes:
        if cls == "DataBindingComponent":
            continue
        layout = cls[: -len("Binding")]
        layout = re.sub(r"(?<!^)(?=[A-Z])", "_", layout).lower()
        layout_for_binding[cls] = layout
        if layout not in layout_ids and layout != "activity_profile":
            err(f"{rel}: нет разметки res/layout/{layout}.xml для {cls}")

    declared = re.search(r"lateinit var binding:\s*(\w+Binding)", text)
    if declared:
        layout = layout_for_binding.get(declared.group(1))
        if layout:
            for member in binding_member_re.findall(text):
                if member not in layout_ids.get(layout, set()):
                    err(f"{rel}: binding.{member} отсутствует в res/layout/{layout}.xml")

    # 2. ссылки на ресурсы R.type.name
    for kind, name in kotlin_ref_re.findall(text):
        if kind in resource_names:
            if name not in resource_names[kind]:
                err(f"{rel}: R.{kind}.{name} не найден")
        elif kind in {"id", "xml"}:
            if name not in all_ids:
                err(f"{rel}: R.id.{name} отсутствует во всех layout/menu")
        elif kind not in {"string", "color", "drawable", "layout", "menu", "mipmap", "bool"}:
            warn(f"{rel}: неизвестный тип ресурса R.{kind}.{name}")

    # 3. core-ktx свойства расширений должны быть импортированы
    for ext, imp in (
        ("isVisible", "androidx.core.view.isVisible"),
        ("isGone", "androidx.core.view.isGone"),
        ("isInvisible", "androidx.core.view.isInvisible"),
    ):
        if re.search(r"\.\s*" + ext + r"\b", text) and f"import {imp}" not in text:
            err(f"{rel}: используется .{ext}, но нет импорта {imp}")

    # 4. импорты databinding
    for imp in re.findall(r"import\s+ai\.arena\.mobile\.databinding\.(\w+)", text):
        layout = re.sub(r"(?<!^)(?=[A-Z])", "_", imp[: -len("Binding")]).lower()
        if layout not in layout_ids:
            err(f"{rel}: импорт {imp}, но нет res/layout/{layout}.xml")

# ------------------------------------------------------- разметки и атрибуты

app_attr_re = re.compile(r"\{http://schemas.android.com/apk/res-auto\}([\w]+)")
for f, root in parsed.items():
    if f.parent.name.startswith(("layout", "menu")):
        text = read(f)
        has_app_usage = "app:" in text
        if has_app_usage and "xmlns:app=" not in text and root.tag != "menu":
            err(f"{f.relative_to(ROOT)}: используются app:-атрибуты без xmlns:app")
        if "tools:" in text and "xmlns:tools=" not in text:
            err(f"{f.relative_to(ROOT)}: используются tools:-атрибуты без xmlns:tools")
        # ссылки @string/... @color/... внутри разметки
        for match in re.finditer(r"@(\w+)/([A-Za-z0-9_.]+)", text):
            kind, name = match.group(1), match.group(2)
            if "." in name or name in LIBRARY_RESOURCES:
                continue  # стили и строки из Material/AppCompat
            if kind in resource_names and name not in resource_names[kind]:
                err(f"{f.relative_to(ROOT)}: ссылка на @{kind}/{name}, которого нет")

# ---------------------------------------------------------------- манифест

if MANIFEST.exists():
    manifest_root = parsed.get(MANIFEST)
    if manifest_root is not None:
        for node in manifest_root.iter():
            name = node.get(f"{ANDROID_NS}name")
            if not name:
                continue
            if node.tag in {"activity", "service", "receiver", "provider"} and name.startswith("."):
                cls = name[1:]
                if cls not in kotlin_classes:
                    err(f"манифест: класс {name} не найден в исходниках")
        # process-имена профилей должны быть уникальны
        processes = [
            node.get(f"{ANDROID_NS}process")
            for node in manifest_root.iter()
            if node.get(f"{ANDROID_NS}process")
            and node.tag in {"activity", "service", "provider"}
        ]
        dupes = {p for p in processes if processes.count(p) > 1}
        if dupes:
            warn(f"манифест: повторяющиеся process: {sorted(dupes)}")

# ------------------------------------------------- workflows GitHub Actions

workflow_dir = ROOT / ".github" / "workflows"
for workflow in sorted(workflow_dir.glob("*.y*ml")):
    text = read(workflow)
    rel = workflow.relative_to(ROOT)

    # GitHub не компилирует workflow, если в выражении ${{ }} есть не-ASCII символы.
    for expression in re.findall(r"\$\{\{(.+?)\}\}", text, re.S):
        bad = [ch for ch in expression if ord(ch) > 127]
        if bad:
            err(
                f"{rel}: нелатинские символы в выражении ${{{{{expression.strip()[:50]}}}}}: "
                f"{''.join(sorted(set(bad)))}"
            )

    # Имена outputs, выставляемые через $GITHUB_OUTPUT, тоже должны быть ASCII-идентификаторами.
    for name in re.findall(r'echo\s+"([^"=]+)=', text):
        if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_-]*", name):
            err(f"{rel}: некорректное имя output в GITHUB_OUTPUT: {name!r}")

    # На каждый вызов uses: путь должен быть с версией (@vN или SHA)
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith("uses:"):
            value = stripped.split("uses:", 1)[1].split("#")[0].strip()
            if "@" not in value:
                err(f"{rel}: у действия {value} не указана версия (@vN)")

# ------------------------------------------------------- JS в assets (node)

js_dir = APP / "assets" / "js"
node = shutil.which("node") or shutil.which("nodejs")
js_files = sorted(js_dir.glob("*.js")) if js_dir.exists() else []

if not js_files:
    warn("в app/src/main/assets/js нет скриптов — страница останется без подсказок")
elif node is None:
    warn("node не найден — синтаксис JS в assets/js не проверен")
else:
    for js in js_files:
        try:
            result = subprocess.run(
                [node, "--check", str(js)],
                capture_output=True,
                text=True,
                timeout=30,
            )
        except Exception as exc:  # noqa: BLE001
            warn(f"{js.relative_to(ROOT)}: не удалось проверить ({exc})")
            continue
        if result.returncode != 0:
            detail = (result.stderr or result.stdout).strip().splitlines()
            err(f"{js.relative_to(ROOT)}: синтаксическая ошибка JS — {detail[-1] if detail else 'без деталей'}")

    # скрипты вызываются из Kotlin по имени: проверяем, что имена совпадают
    scripts_kt = APP / "java" / "ai" / "arena" / "mobile" / "Scripts.kt"
    if scripts_kt.exists():
        declared = set(re.findall(r'const val [A-Z_]+ = "([^"]+\.js)"', read(scripts_kt)))
        present = {js.name for js in js_files}
        for missing in sorted(declared - present):
            err(f"Scripts.kt ссылается на assets/js/{missing}, которого нет")
        for unused in sorted(present - declared):
            warn(f"assets/js/{unused} не упомянут в Scripts.kt")

# ------------------------------------------------------------ прочие файлы

for required in (
    ROOT / "gradlew",
    ROOT / "gradle" / "wrapper" / "gradle-wrapper.jar",
    ROOT / "gradle" / "wrapper" / "gradle-wrapper.properties",
    ROOT / "settings.gradle.kts",
    ROOT / "build.gradle.kts",
    ROOT / "app" / "build.gradle.kts",
    ROOT / "version.properties",
    ROOT / ".github" / "workflows" / "build-apk.yml",
):
    if not required.exists():
        err(f"нет обязательного файла {required.relative_to(ROOT)}")

for density in ("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"):
    for icon in ("ic_launcher.png", "ic_launcher_round.png", "ic_launcher_foreground.png"):
        path = RES / f"mipmap-{density}" / icon
        if not path.exists():
            err(f"нет иконки {path.relative_to(ROOT)}")

# ------------------------------------------------------------------ вывод

print(f"Проверено: {len(kotlin_files)} Kotlin-файлов, {len(xml_files)} XML, "
      f"{len(layout_ids)} разметок, {len(all_ids)} id, {len(strings_default)} строк.")
if warnings:
    print(f"\nПредупреждения ({len(warnings)}):")
    for w in warnings:
        print(f"  • {w}")
if errors:
    print(f"\nОшибки ({len(errors)}):")
    for e in errors:
        print(f"  ✗ {e}")
    sys.exit(1)
print("\nOK: проблемы не найдены ✨")
