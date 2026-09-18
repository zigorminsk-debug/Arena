#!/usr/bin/env bash
# Генерация всех иконок приложения из двух мастер-файлов:
#   design/icon_source.png — квадратная картинка (legacy-иконка)
#   design/icon_mark.png   — знак на белом фоне (adaptive foreground)
# Требуется ImageMagick (convert). Запуск: ./scripts/gen_icons.sh
set -euo pipefail
cd "$(dirname "$0")/.."

SRC="design/icon_source.png"
MARK="design/icon_mark.png"
RES="app/src/main/res"

# ImageMagick 6 — convert, ImageMagick 7 — magick
if command -v magick >/dev/null 2>&1; then
  convert() { magick "$@"; }
elif ! command -v convert >/dev/null 2>&1; then
  echo "Нужен ImageMagick (convert или magick)" >&2
  exit 1
fi
[ -f "$SRC" ] || { echo "Нет $SRC" >&2; exit 1; }
[ -f "$MARK" ] || { echo "Нет $MARK" >&2; exit 1; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# --- 1. Legacy (квадратная и круглая) --------------------------------------
for spec in "mdpi 48" "hdpi 72" "xhdpi 96" "xxhdpi 144" "xxxhdpi 192"; do
  set -- $spec
  density="$1"; size="$2"
  mkdir -p "$RES/mipmap-$density"

  # обрезаем «рамку» исходной картинки и растягиваем на весь кадр
  convert "$SRC" -resize 1254x1254! -gravity center -crop 85%x85%+0+0 +repage \
    -resize "${size}x${size}!" "$RES/mipmap-$density/ic_launcher.png"

  # круглая версия
  convert -size "${size}x${size}" xc:none -fill white \
    -draw "circle $((size / 2)),$((size / 2)) $((size / 2)),0" "$TMP/mask.png"
  convert "$RES/mipmap-$density/ic_launcher.png" "$TMP/mask.png" \
    -alpha off -compose CopyOpacity -composite "$RES/mipmap-$density/ic_launcher_round.png"
done

# --- 2. Adaptive foreground / monochrome -----------------------------------
# Кадр 108dp, безопасная зона — центральные 72dp, поэтому знак ~60% кадра.
convert "$MARK" -fuzz 12% -transparent white -trim +repage "$TMP/mark.png"

for spec in "mdpi 108 65" "hdpi 162 97" "xhdpi 216 130" "xxhdpi 324 195" "xxxhdpi 432 260"; do
  set -- $spec
  density="$1"; canvas="$2"; content="$3"
  mkdir -p "$RES/mipmap-$density"
  convert "$TMP/mark.png" -resize "${content}x${content}" -background none \
    -gravity center -extent "${canvas}x${canvas}" \
    "$RES/mipmap-$density/ic_launcher_foreground.png"
  cp "$RES/mipmap-$density/ic_launcher_foreground.png" \
    "$RES/mipmap-$density/ic_launcher_monochrome.png"
done

echo "Готово: иконки обновлены в $RES/mipmap-*"
