#!/usr/bin/env bash
#
# Приводит рабочую копию репозитория в согласованное состояние с GitHub.
#
# Зачем это нужно: песочница или новый агент может получить воркспейс, где ветка
# существует, но указывает на служебный «Initial commit», а весь проект лежит
# в удалённой ветке. Сборка в таком воркспейсе невозможна: нет ни gradlew, ни
# app/, ни ключа подписи. Скрипт находит расхождение и подтягивает актуальное
# состояние, НЕ удаляя файлы из дерева.
#
# Использование:
#   bash tools/bootstrap_workspace.sh                  # синхронизация ветки
#   bash tools/bootstrap_workspace.sh --check          # только проверить, ничего не менять
#   bash tools/bootstrap_workspace.sh --branch main    # синхронизировать другую ветку
#   bash tools/bootstrap_workspace.sh --force          # жёстко: принести состояние GitHub
#                                                      # (локальные правки уходят в git stash)
#
# Что делает по умолчанию (безопасно):
#   git fetch origin <ветка>; git reset --mixed <состояние на GitHub>
#   — коммит и индекс приводятся к GitHub, а файлы в дереве остаются как есть,
#     поэтому незакоммиченная работа не теряется: она видна в git status.
#
# Код возврата: 0 — воркспейс готов к работе, 1 — есть проблемы (что именно, напечатано).

set -uo pipefail

REQUIRED_FILES=(
  "gradlew"
  "gradle/wrapper/gradle-wrapper.jar"
  "app/build.gradle.kts"
  "version.properties"
  "keystore/arena-release.p12"
  "tools/selfcheck.py"
  ".github/workflows/build-apk.yml"
)

CHECK_ONLY=0
FORCE=0
TARGET_BRANCH=""

while [ $# -gt 0 ]; do
  case "$1" in
    --check) CHECK_ONLY=1 ;;
    --force) FORCE=1 ;;
    --branch)
      shift
      TARGET_BRANCH="${1:-}"
      ;;
    -h|--help)
      sed -n '2,26p' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      echo "неизвестный параметр: $1 (см. --help)" >&2
      exit 1
      ;;
  esac
  shift
done

info() { printf '  %s\n' "$*"; }
ok() { printf '✓ %s\n' "$*"; }
warn() { printf '⚠ %s\n' "$*"; }
fail() { printf '✗ %s\n' "$*" >&2; PROBLEMS=$((PROBLEMS + 1)); }
PROBLEMS=0

if ! git rev-parse --git-dir >/dev/null 2>&1; then
  echo "✗ это не git-репозиторий: запустите скрипт из корня клона Arena" >&2
  exit 1
fi

ROOT=$(git rev-parse --show-toplevel)
cd "$ROOT" || exit 1
echo "Воркспейс: $ROOT"

CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "")
if [ -z "$TARGET_BRANCH" ]; then
  if [ "$CURRENT_BRANCH" = "HEAD" ] || [ -z "$CURRENT_BRANCH" ]; then
    echo "✗ воркспейс в состоянии detached HEAD — укажите ветку: --branch <имя>" >&2
    exit 1
  fi
  TARGET_BRANCH="$CURRENT_BRANCH"
fi
echo "Ветка: $TARGET_BRANCH"

if ! git remote get-url origin >/dev/null 2>&1; then
  echo "✗ нет удалённого репозитория origin — добавьте его: git remote add origin https://github.com/zigorminsk-debug/Arena.git" >&2
  exit 1
fi

echo "Проверяю доступ к GitHub…"
if ! git ls-remote --heads origin >/dev/null 2>&1; then
  fail "GitHub недоступен из этого окружения (нет сети или нет прав на репозиторий)"
  echo >> /dev/stdout
  echo "Итог: синхронизация невозможна — сначала восстановите доступ к GitHub." >&2
  exit 1
fi
ok "GitHub доступен"

# ------------------------------------------------------- служебный коммит-заглушка
HEAD_TREE=$(git ls-tree --name-only HEAD 2>/dev/null | tr '\n' ' ')
IS_STUB=0
case "$HEAD_TREE" in
  *"app"*) IS_STUB=0 ;;
  *) IS_STUB=1 ;;
esac
if [ "$IS_STUB" = "1" ]; then
  warn "локальный коммит не содержит проект (в дереве: ${HEAD_TREE:-пусто}) — похоже, это служебный Initial commit"
fi

# ------------------------------------------------------------------ синхронизация
echo "Забираю состояние ветки $TARGET_BRANCH с GitHub…"
git fetch origin "refs/heads/$TARGET_BRANCH:refs/remotes/origin/$TARGET_BRANCH" >/dev/null 2>&1 \
  || git fetch origin "$TARGET_BRANCH" >/dev/null 2>&1 \
  || true
git fetch --tags --force >/dev/null 2>&1 || true

REMOTE_SHA=$(git rev-parse "refs/remotes/origin/$TARGET_BRANCH" 2>/dev/null || echo "")
if [ -z "$REMOTE_SHA" ]; then
  fail "не удалось определить состояние ветки $TARGET_BRANCH на GitHub"
  exit 1
fi
LOCAL_SHA=$(git rev-parse HEAD 2>/dev/null || echo "")
info "на GitHub: $(git log -1 --format='%h %s' "$REMOTE_SHA" | cut -c1-70)"

if [ "$LOCAL_SHA" = "$REMOTE_SHA" ]; then
  ok "коммит ветки совпадает с GitHub"
elif git merge-base --is-ancestor "$LOCAL_SHA" "$REMOTE_SHA" 2>/dev/null; then
  BEHIND=$(git rev-list --count "$LOCAL_SHA..$REMOTE_SHA")
  if [ "$CHECK_ONLY" = "1" ]; then
    fail "локальная копия отстала от GitHub на $BEHIND коммит(ов) — запустите скрипт без --check"
  elif [ "$FORCE" = "1" ]; then
    echo "Жёсткая синхронизация: локальные правки уезжают в git stash…"
    STASHED=0
    if [ -n "$(git status --porcelain)" ]; then
      if git stash push --include-untracked -m "bootstrap: автосохранение $(date -u +%Y-%m-%dT%H:%M:%SZ)" >/dev/null 2>&1; then
        STASHED=1
      fi
    fi
    if git reset --hard "$REMOTE_SHA" >/dev/null 2>&1; then
      ok "ветка переведена на состояние GitHub ($(git log -1 --format='%h %s' | cut -c1-55))"
    else
      fail "не удалось перевести ветку на $REMOTE_SHA"
    fi
    if [ "$STASHED" = "1" ]; then
      info "локальные правки сохранены: git stash list → git stash pop (если они нужны)"
    fi
  else
    echo "Подтягиваю проект с GitHub (локальная копия отстала на $BEHIND коммит(ов))…"
    info "режим без потерь: файлы в дереве не трогаю, привожу к GitHub коммит и индекс"
    if git reset --mixed "$REMOTE_SHA" >/dev/null 2>&1; then
      ok "ветка указывает на состояние GitHub ($(git log -1 --format='%h %s' | cut -c1-55))"
      DIRTY_AFTER=$(git status --porcelain | wc -l | tr -d ' ')
      if [ "$DIRTY_AFTER" != "0" ]; then
        info "локальных отличий от GitHub: $DIRTY_AFTER (это ваша незакоммиченная работа или файлы снапшота)"
        info "посмотреть: git status; отправить: git add -A && git commit && git push origin $TARGET_BRANCH"
      fi
    else
      fail "не удалось привести коммит к состоянию GitHub"
    fi
  fi
elif git merge-base --is-ancestor "$REMOTE_SHA" "$LOCAL_SHA" 2>/dev/null; then
  ok "локальная копия не старше GitHub"
  if [ "$LOCAL_SHA" != "$REMOTE_SHA" ]; then
    info "есть локальные коммиты, которых нет на GitHub — отправьте их: git push origin $TARGET_BRANCH"
    if [ "$CHECK_ONLY" = "1" ]; then
      PROBLEMS=$((PROBLEMS + 1))
    fi
  fi
else
  warn "локальная ветка и GitHub разошлись — автоматически не сливаю, чтобы ничего не сломать"
  info "посмотрите разницу: git log --oneline --left-right HEAD...refs/remotes/origin/$TARGET_BRANCH"
  PROBLEMS=$((PROBLEMS + 1))
fi

# ------------------------------------------------------------------- проверка
echo "Проверяю состав проекта…"
MISSING=0
for file in "${REQUIRED_FILES[@]}"; do
  if [ -e "$file" ]; then
    info "✓ $file"
  else
    fail "нет файла $file"
    MISSING=1
  fi
done

if [ "$MISSING" = "0" ] && [ -f tools/selfcheck.py ]; then
  echo "Статическая проверка проекта…"
  if python3 tools/selfcheck.py >/tmp/bootstrap_selfcheck.log 2>&1; then
    ok "$(grep -E '^(Проверено|OK)' /tmp/bootstrap_selfcheck.log | tail -1)"
  else
    warn "tools/selfcheck.py нашёл проблемы (подробности в /tmp/bootstrap_selfcheck.log):"
    grep -E '^  ✗' /tmp/bootstrap_selfcheck.log | head -5 | sed 's/^/    /'
    PROBLEMS=$((PROBLEMS + 1))
  fi
fi

# ---------------------------------------------------------------------- итог
echo
if [ "$PROBLEMS" = "0" ]; then
  echo "Воркспейс готов: ветка $TARGET_BRANCH синхронизирована, проект на месте."
  echo "Сборка: запушьте ветку — GitHub Actions соберёт APK (Actions → Build APK, сборки для main и тегов становятся релизами)."
  echo "Локально: ./scripts/build_apk_host.sh (нужны JDK 17 и Android SDK)."
  exit 0
fi

echo "Проблем: $PROBLEMS — см. сообщения выше." >&2
exit 1
