# Резервная копия постоянного ключа подписи

**Не удаляйте тег `signing-key-v1` и релиз с ним.** В них лежит постоянный ключ, которым
подписаны **все** APK приложения. Пока ключ тот же, новые версии ставятся поверх уже
установленных — с профилями, сессиями Arena и подключением GitHub. Если ключ потерять и
создать новый, обновления «поверх» перестанут устанавливаться до ручной переустановки.

Этот файл — тело релиза `signing-key-v1`; он же лежит в репозитории рядом с ключом.

## Где хранится ключ (четыре независимых места)

| Место | Как достать | Зачем |
|---|---|---|
| `keystore/arena-release.p12` в ветке | `git show <ветка>:keystore/arena-release.p12` | основной путь: сборка берёт ключ отсюда |
| тег `signing-key-v1` | `git fetch origin tag signing-key-v1` → `git show signing-key-v1:keystore/arena-release.p12` | работает, даже если ветка потеряна или изменена |
| релиз `signing-key-v1` | `gh release download signing-key-v1 -p arena-release.p12` | не нужен git вообще: только GitHub API |
| секреты репозитория `KEYSTORE_BASE64` и др. | Actions → Secrets | имеют приоритет, если заданы |

Постоянный ключ подписан с отпечатком:

```
F7:11:DD:93:7B:D6:A8:C7:A4:3A:8D:9C:C3:4F:54:E7:02:E9:4D:A6:4B:7F:17:5C:D1:C5:75:CD:11:4B:A4:AA
```

Без двоеточий, в нижнем регистре — ровно то, что сверяет CI:

```
f711dd937bd6a8c7a43a8d9cc34f54e702e94da64b7f175cd1c575cd114ba4aa
```

## Как восстановить ключ

1. **Из тега** (рекомендуется — работает в любом клоне, включая свежий клон `main`):

   ```bash
   git fetch origin "refs/tags/signing-key-v1:refs/tags/signing-key-v1" --depth=1
   mkdir -p keystore
   git show "signing-key-v1:keystore/arena-release.p12"   > keystore/arena-release.p12
   git show "signing-key-v1:keystore/keystore.properties" > keystore/keystore.properties
   ```

2. **Из релиза** (если git недоступен):

   ```bash
   gh release download signing-key-v1 -R zigorminsk-debug/Arena \
     -p "arena-release.p12" -p "keystore.properties" -D keystore
   ```

3. **Из файлов ветки** — просто `keystore/` в рабочей копии.

Проверить, что восстановлен именно постоянный ключ:

```bash
keytool -list -v -keystore keystore/arena-release.p12 -storepass arena-mobile -alias arena \
  | grep SHA256
apksigner verify --print-certs ArenaMobile-*.apk | grep "SHA-256"
```

## Что делает CI, чтобы ключ не потерялся

Шаг «Ключ подписи» в `.github/workflows/build-apk.yml` ищет ключ по порядку:

1. секреты репозитория `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`;
2. `keystore/arena-release.p12` в текущей ветке;
3. тег `signing-key-v1`;
4. ассеты релиза `signing-key-v1`;
5. **новый ключ — только при явном запуске workflow с `rotate_key = true`.**

Если ключа нет нигде, сборка **падает с ошибкой**, а не создаёт другой ключ молча.
После сборки CI сверяет отпечаток сертификата готового APK с постоянным и падает при
расхождении — подменить ключ незаметно нельзя. Дополнительно шаг «Резервная копия ключа
подписи» сам поддерживает ассеты релиза `signing-key-v1` в актуальном состоянии.

## Осознанная смена ключа

Это одноразовая переустановка приложения у всех пользователей: старую версию придётся
удалить, профили при этом сохранятся (они в файлах приложения, а не в APK).

1. Actions → **Build APK** → *Run workflow* → включите `rotate_key`.
2. CI создаст новый ключ, закоммитит его в `keystore/` и выпустит APK с новым отпечатком.
3. Обновите тег и релиз:

   ```bash
   git tag -f -a signing-key-v1 -m "Постоянный ключ подписи (новая версия)"
   git push -f origin signing-key-v1
   gh release upload signing-key-v1 keystore/arena-release.p12 keystore/keystore.properties --clobber
   ```

4. Обновите ожидаемый отпечаток в `.github/workflows/build-apk.yml`
   (`EXPECTED_FINGERPRINT`) и в `keystore/README.md` — иначе CI будет падать.
