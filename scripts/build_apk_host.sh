#!/usr/bin/env bash
# Локальная сборка APK на компьютере (Linux/macOS).
#
# Что нужно:
#   * JDK 17+ (java/javac в PATH)
#   * Android SDK: переменная ANDROID_HOME / ANDROID_SDK_ROOT либо sdk.dir в local.properties
#     (нужны platform android-34 и build-tools)
#
# Использование:
#   ./scripts/build_apk_host.sh            # release APK (сам создаст ключ подписи, если нет)
#   ./scripts/build_apk_host.sh debug      # debug APK
#
# Готовые файлы появятся в dist/
set -euo pipefail
cd "$(dirname "$0")/.."

MODE="${1:-release}"
KS_FILE="${KS_FILE:-release.jks}"
KS_PROPS="keystore.properties"

info() { printf '\033[1;36m==>\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31mОшибка:\033[0m %s\n' "$*" >&2; exit 1; }

# --- 1. Проверки окружения --------------------------------------------------
command -v java >/dev/null 2>&1 || fail "не найден java. Установите JDK 17+: https://adoptium.net/"
JAVA_MAJOR=$(java -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')
info "Java $JAVA_MAJOR"
[ "${JAVA_MAJOR:-0}" -ge 17 ] || fail "нужен JDK 17 или новее (найден $JAVA_MAJOR)"

if [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ]; then
  if [ ! -f local.properties ] || ! grep -q '^sdk.dir=' local.properties; then
    fail "не найден Android SDK. Задайте ANDROID_HOME или создайте local.properties со строкой sdk.dir=/путь/к/sdk"
  fi
fi
info "Android SDK: ${ANDROID_HOME:-${ANDROID_SDK_ROOT:-см. local.properties}}"

[ -f ./gradlew ] || fail "нет gradlew — запускайте скрипт из корня репозитория"
chmod +x ./gradlew

# --- 2. Иконки (если есть ImageMagick) -------------------------------------
if command -v convert >/dev/null 2>&1 && [ -f design/icon_source.png ]; then
  info "Обновляю иконки из design/"
  ./scripts/gen_icons.sh
else
  info "ImageMagick не найден — использую уже сгенерированные иконки"
fi

# --- 3. Ключ подписи --------------------------------------------------------
VERSION_NAME=$(grep -E '^versionName=' version.properties | cut -d= -f2)
VERSION_CODE=$(date +%y%m%d%H%M | cut -c1-9)   # монотонно растущий номер сборки

if [ "$MODE" = "release" ]; then
  if [ ! -f "$KS_FILE" ]; then
    command -v keytool >/dev/null 2>&1 || fail "нет keytool (входит в JDK) — не могу создать ключ подписи"
    info "Создаю ключ подписи $KS_FILE (пароль: arena-mobile)"
    keytool -genkeypair -v \
      -keystore "$KS_FILE" \
      -alias arena \
      -keyalg RSA -keysize 2048 -validity 10950 \
      -storepass arena-mobile -keypass arena-mobile \
      -dname "CN=Arena Mobile, OU=Mobile, O=Arena Mobile, L=, S=, C=RU" >/dev/null
  fi
  {
    echo "storeFile=$KS_FILE"
    echo "storePassword=arena-mobile"
    echo "keyAlias=arena"
    echo "keyPassword=arena-mobile"
  } > "$KS_PROPS"
  info "Подпись: $KS_FILE (alias arena)"
fi

# --- 4. Сборка --------------------------------------------------------------
if [ "$MODE" = "debug" ]; then
  info "Собираю debug APK…"
  ./gradlew --no-daemon :app:assembleDebug -PversionName="$VERSION_NAME" -PversionCode="$VERSION_CODE"
  SRC="app/build/outputs/apk/debug/app-debug.apk"
  OUT="dist/ArenaMobile-${VERSION_NAME}-${VERSION_CODE}-debug.apk"
else
  info "Собираю release APK…"
  ./gradlew --no-daemon :app:assembleRelease -PversionName="$VERSION_NAME" -PversionCode="$VERSION_CODE"
  SRC="app/build/outputs/apk/release/app-release.apk"
  [ -f "$SRC" ] || SRC="app/build/outputs/apk/release/app-release-unsigned.apk"
  OUT="dist/ArenaMobile-${VERSION_NAME}-${VERSION_CODE}.apk"
fi

[ -f "$SRC" ] || fail "APK не найден: $SRC"
mkdir -p dist
cp "$SRC" "$OUT"

info "Готово: $OUT ($(du -h "$OUT" | cut -f1))"
info "Установка: adb install -r \"$OUT\""
