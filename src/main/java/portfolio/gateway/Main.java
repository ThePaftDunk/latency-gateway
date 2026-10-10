package portfolio.gateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Main {
    public static void main(String[] args) throws Exception {
        int port = setting("PORT", 8080), bulkhead = setting("MAX_IN_FLIGHT", 64), timeout = setting("UPSTREAM_TIMEOUT_MS", 120);
        int ttl = setting("CACHE_TTL_MS", 2000), capacity = setting("CACHE_CAPACITY", 256), mockDelay = setting("MOCK_DELAY_MS", 30);
        if (port < 1 || port > 65535 || bulkhead < 1 || timeout < 1 || ttl < 0 || capacity < 1 || mockDelay < 0) throw new IllegalArgumentException("invalid configuration");
        CircuitBreaker breaker = new CircuitBreaker(5, Duration.ofSeconds(2), System::nanoTime);
        String upstream = System.getenv("UPSTREAM_URL");
        QuoteService.Backend backend;
        if (upstream == null || upstream.isBlank()) {
            backend = symbol -> {
                if (symbol.startsWith("SLOW")) Thread.sleep(timeout * 3L); else Thread.sleep(mockDelay);
                if (symbol.startsWith("FAIL")) throw new IllegalStateException("synthetic failure");
                int cents = 10000 + Math.floorMod(symbol.hashCode(), 5000);
                return "{\"symbol\":\"" + symbol + "\",\"priceCents\":" + cents + ",\"synthetic\":true}";
            };
        } else {
            URI base = URI.create(upstream);
            if (!base.getScheme().equals("http") && !base.getScheme().equals("https")) throw new IllegalArgumentException("UPSTREAM_URL must be HTTP(S)");
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeout)).build();
            backend = symbol -> {
                HttpRequest request = HttpRequest.newBuilder(URI.create(upstream + (upstream.contains("?") ? "&" : "?") + "symbol=" + symbol)).timeout(Duration.ofMillis(timeout)).GET().build();
                HttpResponse<String> response = client.send(request, new BoundedBodyHandler(65536));
                if (response.statusCode() != 200) throw new IllegalStateException("upstream response rejected");
                return response.body();
            };
        }
        QuoteService service = new QuoteService(backend, breaker, Duration.ofMillis(timeout), Duration.ofMillis(ttl), capacity, System::nanoTime);
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 128);
        ExecutorService handlers = Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(handlers);
        Semaphore permits = new Semaphore(bulkhead);
        AtomicBoolean ready = new AtomicBoolean(true);
        Metrics metrics = new Metrics();
        server.createContext("/health/live", x -> respond(x, 200, "{\"status\":\"up\"}", "application/json"));
        server.createContext("/health/ready", x -> respond(x, ready.get() ? 200 : 503, "{\"ready\":" + ready.get() + "}", "application/json"));
        server.createContext("/metrics", x -> respond(x, 200, metrics.render(service), "text/plain; version=0.0.4"));
        server.createContext("/quotes", x -> {
            long start = System.nanoTime(); boolean acquired = false;
            try {
                if (!x.getRequestURI().getPath().equals("/quotes")) { respond(x, 404, "{\"error\":\"not found\"}", "application/json"); return; }
                if (!x.getRequestMethod().equals("GET")) { x.getResponseHeaders().set("Allow", "GET"); respond(x, 405, "{\"error\":\"GET required\"}", "application/json"); return; }
                String symbol = symbol(x.getRequestURI().getRawQuery());
                if (symbol == null || !symbol.matches("[A-Z0-9_]{1,16}")) { respond(x, 400, "{\"error\":\"symbol must match [A-Z0-9_]{1,16}\"}", "application/json"); return; }
                acquired = ready.get() && permits.tryAcquire();
                if (!acquired) { metrics.rejected.increment(); metrics.unavailable.increment(); x.getResponseHeaders().set("Retry-After", "1"); respond(x, 503, "{\"error\":\"capacity unavailable\"}", "application/json"); return; }
                QuoteService.Result result = service.resolve(symbol); metrics.ok.increment();
                x.getResponseHeaders().set("X-Cache", result.source()); respond(x, 200, result.json(), "application/json");
            } catch (QuoteService.Unavailable e) {
                metrics.unavailable.increment(); x.getResponseHeaders().set("Retry-After", "1"); respond(x, 503, "{\"error\":\"" + e.getMessage() + "\"}", "application/json");
            } catch (IllegalArgumentException e) { respond(x, 400, "{\"error\":\"malformed query\"}", "application/json"); }
            finally { if (acquired) permits.release(); metrics.observe(System.nanoTime() - start); x.close(); }
        });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            ready.set(false); server.stop(5); service.close(); handlers.shutdownNow();
        }));
        server.start(); System.out.println("Latency Gateway listening on http://localhost:" + port + " (" + (upstream == null ? "synthetic" : "HTTP upstream") + ")");
    }
    private static int setting(String name, int fallback) { String value = System.getenv(name); return value == null ? fallback : Integer.parseInt(value); }
    private static String symbol(String query) {
        if (query == null || query.length() > 256) return null;
        String value = null;
        for (String item : query.split("&")) {
            String[] parts = item.split("=", 2);
            if (parts[0].equals("symbol")) { if (value != null) return null; value = parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : ""; }
        }
        return value;
    }
    private static void respond(HttpExchange x, int status, String body, String contentType) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8); x.getResponseHeaders().set("Content-Type", contentType);
        x.sendResponseHeaders(status, bytes.length); try (var output = x.getResponseBody()) { output.write(bytes); } finally { x.close(); }
    }
}
