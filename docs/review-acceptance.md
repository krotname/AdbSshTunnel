# PR #1: регрессии и приёмка

Для всех десяти замечаний в PR head
`819e3608a7e3be1543ce1994afd309cce4f9e04a` уже есть защитные проверки.
База — `22d61b1cc37627084c8d8c459659aebc0645f331`; merge этого `main` в head
не требует дополнительных изменений. Возраст обсуждения и флаг outdated
не доказывают исправление. Ниже приведены конкретные пути исполнения и
ожидаемые результаты приёмки; анализ исходников не заменяет CI и устройства.

## Замечания и подтверждение по коду

Числовые ID — комментарии [PR #1](https://github.com/krotname/AdbSshTunnel/pull/1).

| Приоритет / комментарий | Защита | Ожидаемый результат приёмки |
|---|---|---|
| P1 / 4203924542 | `ci.yml` требует владельца в actor и triggering actor. Dispatch/push допускаются только на `refs/heads/main`; PR — от владельца с head в том же repo. | В текущем workflow dispatch владельца на main запускается; чужой actor, feature ref и fork не получают ARC job. Ограничения доверия на стороне runner должны также блокировать изменённый workflow произвольной ветки; из этого repo они не подтверждены. |
| P1 / 4203924548 | `AdbDiscovery` проверяет адрес телефона и loopback CNXN/STLS через `AdbTlsProbe.verify`; `startForPort` повторяет обмен перед запуском SSH. | HTTP, SSH, plaintext ADB и некорректный STLS, объявленные как wireless ADB, не становятся целью. Настоящий Wireless Debugging работает. |
| P2 / 4203924559 | Discovery callback сверяет `discoveryGeneration`, enabled и root mode. Root callback также сверяет поколение и режим; `stopNative` отменяет старые native probes. | Поздний wireless callback после перехода в root не запускает старый endpoint. Цикл wireless/root/wireless не оживляет старый discovery. |
| P2 / 4203924564 | `validateEd25519` декодирует канонические точки RFC 8032 и отвергает неверные кодировки и точки порядка, делящего восемь. | RFC-ключи принимаются; all-ff, all-zero, identity, noncanonical, off-curve и invalid-sign отвергаются до сохранения и включения. |
| P2 / 4203924570 | Плитка вызывает `SshdService.select`, который проверяет весь сохранённый набор до записи enabled. Ошибка сбрасывает enabled, останавливает SSH и публикуется. `onStartCommand` повторяет проверку и возвращает non-sticky при ошибке. | Пустой, comment-only или смешанный неверный набор оставляет плитку и switch выключенными. Boot/package replacement не повторяют запуск. После сохранения верного ключа следующий клик включает туннель. |
| P1 / 4203924574 | Manifest подключает `backup_rules` и `data_extraction_rules`; file/sharedpref и device-protected domains исключены из cloud backup и device transfer. | Миграция не переносит host identity, доверенных клиентов и enabled. Принимающая установка выключена и требует импорта ключей и физической проверки новой host identity. |
| P2 / 4204215025 | Root mode вызывает `RootHelper.verifyGuard`, затем `verifyPlain` требует AUTH или device CNXN с ограниченным размером, корректными header/magic/payload. | Посторонний loopback service на 5555 не открывает SSH даже при успешном guard. Корректные AUTH и старый/новый CNXN принимаются; повреждённые и обрезанные фреймы отвергаются. |
| P1 / 4204215035 | Объявлен `CHANGE_WIFI_MULTICAST_STATE`; discovery держит non-reference-counted lock при API < 33 или T extension < 7 и освобождает его при close, остановке и ошибке старта. | Android 11/12 и Android 13 ниже T extension 7 обнаруживают connect service. Выключение, смена режима и ошибка discovery освобождают lock. |
| P2 / 4204215044 | `positiveMpint` требует положительные канонические RSA exponent/modulus до проверки длины и нечётности. | Положительный канонический 2048-bit fixture принимается. Отрицательные high-bit mpints, пропущенный sign byte, лишние нули, нулевые и пустые поля отвергаются без замены сохранённых ключей. |
| P2 / 4204215050 | Native waiter сверяет PID и generation. Неожиданный выход вызывает `failAndStop`: enabled сбрасывается, native descendants уничтожаются, состояние публикуется, служба останавливается. Ошибка readiness/start использует тот же путь. | Выход listener закрывает каналы, сбрасывает enabled и завершает foreground service. Следующий клик плитки повторяет запуск. Старый waiter не выключает заменивший его listener. |

## Плитка и native lifecycle

После выхода listener предусмотрено явное повторное включение.
`START_STICKY` не используется как гарантия перезапуска дочернего процесса.
На подписанном кандидате записать commit/APK и точные device/API в существующий
checkpoint приёмки:

1. Чистая установка без ключей: дважды нажать плитку. Оба раза включение
   отклонено, `enabled=false`, видна ошибка ключей, SSH listener отсутствует.
   Повторить после пересоздания процесса и package replacement.
2. Сохранить comment-only input и затем верный ключ вместе с неверным.
   Обе попытки отклонены атомарно; прежний верный набор сохранён.
   При верных ключах и разрешённой сети один клик запускает SSH.
3. Завершить текущий native listener после readiness в активном SSH/ADB-сеансе.
   Старые каналы закрыты, `enabled=false`, switch и плитка согласованы,
   foreground notification исчезло. Один клик запускает новый listener после
   повторной проверки endpoint и сети.
4. Завершить listener до конца readiness probe; отдельно занять порт 19191.
   Обе ошибки выключают и останавливают туннель; поздний readiness callback
   не публикует активное состояние.
5. Выключить туннель или сменить режим/сеть/ключи при ожидающем probe/waiter.
   Старое поколение не открывает SSH, не сбрасывает enabled нового listener
   и не публикует старые ready/error. Прежние SSH/ADB-каналы отозваны.
6. Поставить callback разрешённого wireless endpoint в очередь, переключиться
   в root и завершить старый callback. Запускается только защищённый,
   проверенный по протоколу root endpoint. Повторить со сменой двух wireless
   discovery instances.

## Серверный CI

Использовать существующий `Android CI` на ревизии кандидата со штатной
проверкой контракта ARC cache. Native и Java проверки workflow:

```sh
arc-cache check-workflow --file .github/workflows/ci.yml --job "$GITHUB_JOB"
cc -std=c11 -Wall -Wextra -Werror tools/test-tunnel-parent.c -o "$RUNNER_TEMP/tunnel-parent-test"
"$RUNNER_TEMP/tunnel-parent-test"
git clone --no-checkout https://github.com/tfonteyn/Sshd4a.git vendor/Sshd4a
git -C vendor/Sshd4a checkout --detach 897f9064a7279bff89538ec87729c43888f3b83d
python3 tools/prepare-native.py
python3 tools/check-source.py
mkdir -p "$RUNNER_TEMP/host-key-check"
javac -d "$RUNNER_TEMP/host-key-check" app/src/main/java/name/krot/adbsshtunnel/HostKeyPublic.java tools/TestHostKeyPublic.java
java -cp "$RUNNER_TEMP/host-key-check" TestHostKeyPublic
bash tools/ci-build.sh
```

Команды предполагают чистый checkout и настроенные в workflow Java 21,
Android SDK, ARC cache и Gradle environment. `ci-build.sh` выполняет
`testReleaseUnitTest lintRelease assembleRelease` на закреплённом toolchain.
Новые проверки смешанных наборов ключей, RSA sign/zero, root checksums и
ограниченных/обрезанных фреймов входят в `testReleaseUnitTest`.
Source/XML checks не доказывают Java-компиляцию, native behavior и приёмку.

## Release gates

Действуют существующие [профиль выпуска](RELEASE_PROFILE.md) и
[матрица сетевой приёмки](network-policy-acceptance.md).
Успешный CI не закрывает физические проверки:

1. Подписанный кандидат на Pixel по физически проверенному USB: pinned host
   identity, разрешённые/запрещённые SSH операции, ADB protocol/port changes,
   root guard success/denial, отзыв по сети и плитка/native lifecycle.
2. Multicast lifecycle на Android 11/12 либо Android 13 ниже T extension 7;
   cloud/device-transfer exclusions в поддерживаемом процессе миграции.
3. Обновление Fold без удаления данных: прежний host key, network policy,
   реальная mobile/WAN-достижимость и независимость WireGuard.
   Pixel без SIM не закрывает положительные mobile-сценарии.
4. Четыре реальные перезагрузки, сон/смена сети и три сопоставимых интервала
   батареи по 30 минут без зарядки из профиля выпуска. Результаты другого APK
   не закрывают эти проверки.

Security Review, CI на main, подпись и проверка скачиваемого/установленного
артефакта остаются обязательными условиями из профиля выпуска.
