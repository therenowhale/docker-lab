package dev.dockerlab;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** Небольшой HTTP-процесс, специально созданный для наблюдения за Docker. */
public final class LabServer {
    private static final AtomicBoolean ready = new AtomicBoolean(true);
    private static final CopyOnWriteArrayList<byte[]> retainedAllocations = new CopyOnWriteArrayList<>();

    private LabServer() { }

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", LabServer::dashboard);
        server.createContext("/health", exchange -> reply(exchange, 200, "ok\n"));
        server.createContext("/ready", exchange -> reply(exchange, ready.get() ? 200 : 503,
                ready.get() ? "готово\n" : "не готово\n"));
        server.createContext("/whoami", LabServer::whoami);
        server.createContext("/memory", LabServer::memory);
        server.createContext("/memory/clear", exchange -> {
            retainedAllocations.clear();
            reply(exchange, 200, "удерживаемая память освобождена\n");
        });
        server.createContext("/slow", LabServer::slow);
        server.createContext("/crash", exchange -> {
            reply(exchange, 202, "аварийное завершение через 100 мс\n");
            Thread.ofVirtual().start(() -> { sleep(100); System.exit(42); });
        });
        server.createContext("/not-ready", exchange -> {
            ready.set(false);
            reply(exchange, 200, "готовность отключена\n");
        });
        server.createContext("/ready-again", exchange -> {
            ready.set(true);
            reply(exchange, 200, "готовность включена\n");
        });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("shutdown hook: SIGTERM дошёл до Java; корректно останавливаемся");
            server.stop(2);
        }));
        System.out.printf("LabServer pid=%s started=%s port=%d%n",
                ProcessHandle.current().pid(), Instant.now(), port);
        server.start();
    }

    private static void whoami(HttpExchange exchange) throws IOException {
        String body = "pid=" + ProcessHandle.current().pid() + "\n"
                + "user=" + System.getProperty("user.name") + "\n"
                + "runtime=" + System.getProperty("java.runtime.version") + "\n"
                + "hostname=" + System.getenv().getOrDefault("HOSTNAME", "unknown") + "\n";
        reply(exchange, 200, body);
    }

    private static void dashboard(HttpExchange exchange) throws IOException {
        String page = """
                <!doctype html>
                <html lang="ru">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>Docker Lab · контейнер вживую</title>
                  <style>
                    :root { color-scheme: dark; font-family: Inter, ui-sans-serif, system-ui, sans-serif; background: #10141a; color: #edf2f7; }
                    * { box-sizing: border-box; } body { margin: 0; min-height: 100vh; background: radial-gradient(circle at 15% 5%, #1a3655, transparent 35rem), #10141a; }
                    main { max-width: 980px; margin: auto; padding: 48px 24px; } h1 { margin: 0; font-size: clamp(2rem, 5vw, 3.6rem); letter-spacing: -.05em; } p { color: #a9b7c6; font-size: 1.05rem; }
                    .eyebrow { color: #7dd3fc; font: 600 .8rem ui-monospace, monospace; letter-spacing: .14em; text-transform: uppercase; }
                    .grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 14px; margin: 30px 0; } .card { background: #18212cdd; border: 1px solid #2a3a4c; border-radius: 18px; padding: 20px; min-height: 142px; }
                    .label { color: #9aaabd; font-size: .82rem; text-transform: uppercase; letter-spacing: .09em; } .value { margin-top: 14px; font: 600 1.45rem ui-monospace, monospace; color: #f8fafc; word-break: break-word; }
                    .ready { color: #86efac; } .not-ready, .error { color: #fda4af; } .actions { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 18px; }
                    button { border: 1px solid #38516b; border-radius: 10px; padding: 10px 14px; background: #1e293b; color: #e5edf7; font: 600 .9rem ui-monospace, monospace; cursor: pointer; } button:hover { background: #2b3f57; } button.danger { border-color: #8b3a48; }
                    pre { min-height: 120px; overflow: auto; padding: 18px; border-radius: 14px; background: #090c10; border: 1px solid #253444; color: #cbd5e1; white-space: pre-wrap; }
                    @media (max-width: 680px) { main { padding: 32px 16px; } .grid { grid-template-columns: 1fr; } }
                  </style>
                </head>
                <body><main>
                  <div class="eyebrow">Docker learning lab · live view</div>
                  <h1>Контейнер работает<br>и это видно.</h1>
                  <p>Панель читает реальные эндпоинты запущенного Java-процесса — без моков.</p>
                  <section class="grid" aria-label="Состояние контейнера">
                    <article class="card"><div class="label">Health</div><div class="value" id="health">проверяем…</div></article>
                    <article class="card"><div class="label">Readiness</div><div class="value" id="ready">проверяем…</div></article>
                    <article class="card"><div class="label">Процесс</div><div class="value" id="process">проверяем…</div></article>
                  </section>
                  <div class="actions">
                    <button onclick="request('/health')">Проверить health</button>
                    <button onclick="request('/ready')">Проверить readiness</button>
                    <button onclick="request('/memory?mb=32')">Удержать 32 MiB</button>
                    <button onclick="request('/memory/clear')">Освободить память</button>
                    <button class="danger" onclick="request('/not-ready')">Сделать not ready</button>
                    <button onclick="request('/ready-again')">Вернуть ready</button>
                  </div>
                  <pre id="output" aria-live="polite">Загрузка данных…</pre>
                </main>
                <script>
                  const output = document.getElementById('output');
                  async function request(path) {
                    output.textContent = '→ ' + path + '\\n';
                    try { const response = await fetch(path); const text = await response.text(); output.textContent += response.status + ' ' + text; await refresh(); }
                    catch (error) { output.textContent += 'Ошибка сети: ' + error.message; }
                  }
                  async function check(path, element, transform) {
                    try { const response = await fetch(path); const text = await response.text(); element.textContent = transform(text, response.status); element.className = 'value ' + (response.ok ? 'ready' : 'not-ready'); return text; }
                    catch { element.textContent = 'нет связи'; element.className = 'value error'; return ''; }
                  }
                  async function refresh() {
                    await check('/health', document.getElementById('health'), (_, status) => status === 200 ? 'OK · 200' : 'ошибка');
                    await check('/ready', document.getElementById('ready'), (text, status) => status === 200 ? text.trim() : 'не готов · ' + status);
                    const whoami = await check('/whoami', document.getElementById('process'), text => (text.match(/pid=(\\d+)/) || [])[1] ? 'pid ' + text.match(/pid=(\\d+)/)[1] : 'unknown');
                    if (!output.textContent || output.textContent === 'Загрузка данных…') output.textContent = whoami;
                  }
                  refresh(); setInterval(refresh, 5000);
                </script>
                </body></html>
                """;
        reply(exchange, 200, page, "text/html; charset=utf-8");
    }

    private static void memory(HttpExchange exchange) throws IOException {
        long megabytes = Long.parseLong(query(exchange).getOrDefault("mb", "16"));
        byte[] allocation = new byte[Math.toIntExact(megabytes * 1024 * 1024)];
        allocation[0] = 1;
        retainedAllocations.add(allocation);
        reply(exchange, 200, "удерживаем " + megabytes + " MiB; освободи через /memory/clear\n");
    }

    private static void slow(HttpExchange exchange) throws IOException {
        sleep(Long.parseLong(query(exchange).getOrDefault("ms", "1000")));
        reply(exchange, 200, "завершено\n");
    }

    private static Map<String, String> query(HttpExchange exchange) {
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null || raw.isBlank()) return Map.of();
        return java.util.Arrays.stream(raw.split("&"))
                .map(item -> item.split("=", 2))
                .collect(java.util.stream.Collectors.toMap(pair -> pair[0], pair -> pair.length == 2 ? pair[1] : ""));
    }

    private static void sleep(long milliseconds) {
        try { Thread.sleep(milliseconds); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
    }

    private static void reply(HttpExchange exchange, int code, String body) throws IOException {
        reply(exchange, code, body, "text/plain; charset=utf-8");
    }

    private static void reply(HttpExchange exchange, int code, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
    }
}
