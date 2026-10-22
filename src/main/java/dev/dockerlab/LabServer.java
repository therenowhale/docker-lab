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
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
    }
}
