# Спецификации ADB SSH Tunnel

Требования и сценарии пишутся по-русски. Английские заголовки `Purpose`,
`Requirements`, `Requirement`, `Scenario` и ключевые слова `SHALL`, `WHEN`,
`THEN` сохранены как синтаксис OpenSpec. Контракт описывает поведение,
а наличие сценария не доказывает испытание на каждом устройстве.

| Свойство | Android | Linux |
|---|---|---|
| Назначение | Виртуальное `127.0.0.1:5555` → проверенный adbd этого устройства | Только `127.0.0.1:5037` → SDK ADB server с подключёнными устройствами |
| Клиент ADB | Протокол устройства, штатный pairing/авторизация | `adb -H 127.0.0.1 -P <local-port> devices -l`, список может быть пуст |
| Сетевая идентичность | Wi-Fi SSID, мобильный оператор, Android-разрешения | Wi-Fi SSID, Ethernet/mobile NetworkManager UUID |
| Привязка | Каждая допустимая физическая сеть отдельно, без IMS | Один разрешённый IPv4 физического NM-профиля default route |
| Ключи RSA | Не менее 2048 бит; до 32 ключей и 65536 входных символов | Не менее 3072 бит; parser Linux, лимиты Android не переносятся |
| Жизненный цикл | Android-приложение, служба и плитка; беспроводной/root режимы | Пользовательская systemd-служба; закрытие UI не останавливает включённый туннель |
| Общее | SSH по ключам, только forwarding, отзыв запрещённого доступа | Тот же принцип, собственные реализация и тесты |

| Контракт | Исходники | Проверка |
|---|---|---|
| [Android-туннель](specs/android-tunnel/spec.md) | `app/src/main/`, `tools/tunnel-*.h` | Java/native регрессии, `docs/USAGE.md` |
| [Сети Android](specs/android-network-policy/spec.md) | NetworkPolicy/NetworkMonitor/NetworkSettings | NetworkPolicyTest, NetworkDiagnosticsTest, `docs/network-policy-acceptance.md` |
| [Ключи](specs/key-security/spec.md) | KeyPolicy/HostKeyPublic, Linux core | KeyPolicyTest, TestHostKeyPublic, Linux protocol tests |
| [Linux-туннель](specs/linux-tunnel/spec.md) | `linux/adb_ssh_tunnel/`, `linux/packaging/` | `linux/tests/`, `linux/README.md` |
| [Разработка](specs/development-workflow/spec.md) | CI, `tools/`, npm lockfile | `tools/test-openspec.py`, строгая валидация, Android CI |

## Работа с изменением

1. Прочитай спецификации затронутой платформы до изменения реализации.
2. Для нового поведения создай `openspec/changes/<change-id>/` с русскими
   `proposal.md`, `design.md`, `tasks.md` и delta
   `specs/<capability>/spec.md`. Используй заголовки `## ADDED Requirements`,
   `## MODIFIED Requirements` или `## REMOVED Requirements`.
   Небольшая правка может обновить основной spec; при сохранении поведения
   опиши сохраняемый контракт и сценарий регрессии.
3. Обновляй соответствующий spec или delta в том же PR. Workflow-spec не
   покрывает код Android/Linux; архив и удалённый spec не дают покрытия.
   Активной delta нужны proposal, design и tasks в том же каталоге change.
4. Выполни проверки и адресные продуктовые тесты. Завершённую change архивируй
   CLI с применением delta к основным specs; закоммить полученные обновления.

## Проверки

Нужны Node >=22.12 и Python >=3.11. Установка закреплена lockfile, без
lifecycle-скриптов зависимостей:

```sh
npm ci --ignore-scripts --no-audit --no-fund
npm run spec:validate
python tools/test-openspec.py
python tools/check-openspec.py --base origin/main
```

`--snapshot` проверяет checkout без diff и используется при ручном запуске.
PR/push используют точный base SHA события; отсутствующий или неверный base
вызывает ошибку. Перенос учитывает оба пути.

Существующий `android` запускает gate до native-компиляции, Linux-упаковки и
Android-тестов/сборки, без фильтра путей и необязательных ошибок.
Main требует `android` и `OpenSpec required`. Финальный статус на изолированном
GitHub-hosted Ubuntu без checkout/кэша принимает только успешный Android job;
skipped, cancelled и failure отклоняются. Этот runner относится к ADB SSH,
а не к NetMode с его отдельным контрактом runner.

Структурная валидация и покрытие файлов не доказывают смысловое соответствие:
проверяй diff, адресные тесты и device acceptance затронутой платформы.
