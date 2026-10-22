# Docker-лаборатория: наблюдай, предсказывай, проверяй

Это практикум для Java/backend-разработчика, который только начинает знакомство с Docker. Docker здесь не «магическая коробка для запуска приложения», а способ увидеть, какой процесс работает, что он видит, какие у него лимиты и как он общается с другими процессами.

Не старайся запомнить команды. Перед каждым шагом ответь: **что, по-моему, произойдёт и почему?** После команды сохрани вывод и сравни его с прогнозом. Ответы лежат отдельно в `solutions/README.md` — не открывай их заранее.

## Мини-словарь

- **Образ (image)** — шаблон файлов и настроек для запуска; сам по себе не работает.
- **Контейнер (container)** — запущенный экземпляр образа: процесс с изоляцией.
- **Dockerfile** — рецепт создания образа.
- **Compose** — `compose.yaml`: описание контейнеров, сети и данных одного проекта.
- **Порт** — «дверь» к процессу. Внутри приложения `8080`, с Mac доступ идёт через `8088`.
- **Том (volume)** — управляемое Docker хранилище, живущее дольше контейнера.

## Старт: просто запусти сервис

Нужны Docker Desktop и свободный хостовый порт `8088`. Выполняй команды из этой папки. Если порт занят, выбери другой: `HOST_PORT=8099 docker compose up -d`.

```bash
# Проверяем, что Docker доступен
docker version
docker compose version

# Собираем образ и запускаем контейнер в фоне
docker compose build
docker compose up -d

# Смотрим состояние и проверяем Java-сервис
docker compose ps
curl -i http://localhost:8088/health
curl http://localhost:8088/whoami
```

Ожидаемый минимум: `/health` вернёт `200` и `ok`, а `/whoami` — `pid=1` и пользователя `app`. Почему — разберём по ходу лабораторных.

После эксперимента останавливай только этот проект:

```bash
docker compose down
```

Не добавляй `-v`, пока задание прямо не просит: этот флаг удаляет тома с данными.

## Метод работы

Для каждого задания запиши три короткие строки:

1. **Прогноз:** что увижу?
2. **Факт:** что вывела команда?
3. **Причина:** каким механизмом Docker это объясняется?

Ошибочный прогноз — нормальная и полезная часть обучения.

---

## Лаба 01 — контейнер: изолированный процесс, а не виртуальная машина

**Зачем.** Понять базовую модель: контейнер использует общее ядро, но получает отдельный вид процессов, сети и файловой системы.

1. Запусти сервис и посмотри процессы:

   ```bash
   docker compose exec app ps -ef
   docker top $(docker compose ps -q app)
   ```

2. Посмотри первый процесс *внутри* контейнера:

   ```bash
   docker compose exec app cat /proc/1/status
   ```

   Почему Java — PID `1` здесь, но на хосте у неё другой PID?

3. Изучи ограничения ресурсов:

   ```bash
   docker compose exec app cat /proc/self/cgroup
   docker inspect $(docker compose ps -q app)
   docker stats --no-stream
   ```

4. Раздели на «изолировано» и «общее с хостом»: PID, hostname, сеть, файловая система, ядро, CPU и память.

## Лаба 02 — образ, слои и кеш сборки

**Зачем.** Docker не должен пересобирать всё после каждой правки — он повторно использует результаты шагов Dockerfile.

1. Посмотри историю слоёв:

   ```bash
   docker history docker-lab-app:local
   ```

2. Собери без изменений:

   ```bash
   docker compose build --progress=plain
   ```

   Найди `CACHED`. Почему эти шаги не запускались снова?

3. Измени только текст ответа в `src/main/java/dev/dockerlab/LabServer.java`, собери образ и найди **первый** шаг без кеша. Затем верни правку.

4. Измени `pom.xml` (достаточно пустой строки), пересобери. Почему снова выполняется загрузка зависимостей?

5. Посмотри Dockerfile: почему `pom.xml` копируется раньше `src`?

## Лаба 03 — multi-stage Dockerfile

**Зачем.** Для сборки Java нужны Maven, исходники и зависимости. Для запуска обычно нужны только JRE и JAR.

1. Проверь содержимое *финального* образа:

   ```bash
   docker run --rm --entrypoint sh docker-lab-app:local \
     -c 'id; ls -la /app; command -v mvn || true'
   ```

2. До команды ответь: есть ли в финальном образе Maven и исходники? Почему?

3. Найди `COPY --from=build`. Что переносится в этап запуска? Как это влияет на размер и поверхность атаки?

## Лаба 04 — PID 1 и корректная остановка

**Зачем.** Сервис должен уметь завершаться аккуратно: перестать принимать работу и закрыть соединения.

1. В первом терминале открой логи:

   ```bash
   docker compose logs -f app
   ```

2. Во втором терминале останови сервис:

   ```bash
   docker compose stop -t 10 app
   ```

   Найди `shutdown hook`. Почему Java успела его выполнить?

3. Перезапусти сервис и сравни с принудительным завершением:

   ```bash
   docker kill --signal=KILL $(docker compose ps -q app)
   ```

   Чем это отличается от `stop`? Какой сигнал обычно посылается при штатной остановке?

4. Посмотри JSON-форму `ENTRYPOINT`. Почему shell-обёртка без `exec` может помешать доставке сигналов до Java?

## Лаба 05 — сеть и DNS между контейнерами

**Зачем.** Контейнеры одного Compose-проекта общаются по имени сервиса, а не по IP и не через опубликованный порт.

1. Запусти учебный контейнер-клиент:

   ```bash
   docker compose --profile client up -d
   ```

2. Найди сервис по имени и сделай запрос из клиента:

   ```bash
   docker compose exec client getent hosts app
   docker compose exec client wget -qO- http://app:8080/whoami
   ```

3. Почему работает `app:8080`, но `localhost:8080` внутри `client` указывает на сам `client`?

4. Посмотри состав сети:

   ```bash
   docker network inspect docker-lab_lab-net
   ```

   Если ты задавал `COMPOSE_PROJECT_NAME`, префикс сети будет другим.

## Лаба 06 — named volume и bind mount

**Зачем.** Контейнер одноразовый, а данные часто должны жить дольше него.

1. Запиши файл в Docker-том:

   ```bash
   docker compose exec app sh -c 'echo persisted > /data/marker.txt'
   docker compose exec app cat /data/marker.txt
   ```

2. Выполни `docker compose down`, затем `docker compose up -d` и снова прочитай файл. Почему он остался?

3. Выполни `docker compose down -v`, запусти сервис и снова проверь файл. Почему он исчез? Здесь `-v` нужен намеренно.

4. Создай на Mac `scratch/host-note.txt`, затем смонтируй папку напрямую:

   ```bash
   docker run --rm --mount type=bind,src="$PWD/scratch",dst=/mnt/host,readonly \
     alpine:3.22 cat /mnt/host/host-note.txt
   ```

   Сравни bind mount и named volume: кто управляет данными, как переносятся проект и права доступа.

## Лаба 07 — лимиты CPU/памяти и JVM

**Зачем.** Лимит контейнера — не то же самое, что Java heap: JVM должна оставить запас под metaspace, потоки и native-память.

1. Проверь итоговую конфигурацию Compose:

   ```bash
   docker compose config
   ```

2. Сервис умеет удерживать память для опыта:

   ```bash
   curl 'http://localhost:8088/memory?mb=128'
   docker stats --no-stream
   curl http://localhost:8088/memory/clear
   ```

3. Временно поменяй `256M` на `64M` в `compose.yaml`, пересоздай контейнер и проверь результат:

   ```bash
   docker compose up -d --force-recreate
   curl 'http://localhost:8088/memory?mb=128'
   docker compose ps
   docker compose logs app
   ```

   Не угадывай: запиши факт. Верни `256M` после опыта.

4. Где лежит ограничение: Java, Dockerfile, Compose или ядро? Как cgroups связаны с реальной памятью JVM?

## Лаба 08 — healthcheck и поиск неисправностей

**Зачем.** «Контейнер запущен» ещё не значит «сервис готов принимать запросы».

1. Сразу после запуска наблюдай:

   ```bash
   docker compose ps
   ```

   Найди `starting`, затем `healthy`.

2. Сделай приложение неготовым:

   ```bash
   curl http://localhost:8088/not-ready
   curl -i http://localhost:8088/ready
   docker compose ps
   ```

   Почему Docker всё ещё может показать `healthy`? Посмотри, какой URL использует `HEALTHCHECK` в Dockerfile.

3. Намеренно заверши приложение:

   ```bash
   curl http://localhost:8088/crash
   docker compose ps
   docker compose logs app
   docker inspect $(docker compose ps -q app) --format '{{.State.ExitCode}}'
   ```

4. Построй порядок диагностики для случаев: порт занят, ошибка сборки, DNS не работает, процесс завершился, healthcheck не проходит. Начинай с `docker compose ps`, потом логи и `docker inspect`. Не удаляй чужие контейнеры, чтобы «починить» проблему.

## Лаба 09 — базовая безопасность: non-root

**Зачем.** У скомпрометированного приложения не должно быть больше прав, чем необходимо.

1. Проверь пользователя:

   ```bash
   docker compose exec app id
   curl http://localhost:8088/whoami
   ```

2. Открой shell и попробуй создать файл в `/app`, а затем в `/tmp`:

   ```bash
   docker compose exec app sh
   ```

3. Найди `USER app:app` в Dockerfile. От чего это защищает, а от чего — нет? Выбери одну следующую меру: read-only filesystem, удаление capabilities, secret mounts, pin образа по digest или сканирование образа. Назови её минус.

## Очистка

```bash
docker compose --profile client down
docker image rm docker-lab-app:local   # необязательно: удалит только образ этой лабораторной
```

Сделай сначала Лабу 01. Пришли мне свой прогноз и вывод команд — разберём вместе, не раскрывая ответы к следующим лабораторным.
