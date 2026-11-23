package dev.learning.navigator;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.regex.*;

/** Project-scoped, loopback-only server. No shell execution or arbitrary file navigation. */
final class NavigationServer implements AutoCloseable {
  private static final Gson JSON = new Gson();
  private static final int MAX_BODY = 16384;
  private final HttpServer server;
  private final ExecutorService workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
      new ArrayBlockingQueue<>(32), r -> {
        var t = new Thread(r, "diagram-navigator-http"); t.setDaemon(true); return t;
      }, new ThreadPoolExecutor.AbortPolicy());
  private final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(2, r -> {
    var t = new Thread(r, "diagram-navigator-deadlines"); t.setDaemon(true); return t;
  });
  private static final int MAX_FILE = 32 * 1024 * 1024;
  private final byte[] page;
  private final Map<String, String> symbols;
  private final Duration taskTimeout;
  private boolean closed;
  private final Path html, root;
  private final String token = UUID.randomUUID().toString() + UUID.randomUUID();
  private final String origin;
  private final BiConsumer<NavigationRequest, NavigationTask> navigator;
  private final Map<String, NavigationTask> tasks = new ConcurrentHashMap<>();
  private final Map<String, Long> cancelledIds = new ConcurrentHashMap<>();

  NavigationServer(Path html, BiConsumer<NavigationRequest, NavigationTask> navigator) throws IOException {
    this(html, navigator, Duration.ofSeconds(90));
  }

  NavigationServer(Path html, BiConsumer<NavigationRequest, NavigationTask> navigator, Duration timeout) throws IOException {
    taskTimeout = timeout;
    timer.setRemoveOnCancelPolicy(true);
    this.html = html.toRealPath();
    if (!Files.isRegularFile(this.html) || !this.html.toString().endsWith(".html")) throw new IOException("请选择 HTML 文件");
    root = this.html.getParent();
    this.navigator = navigator;
    page = readBounded(this.html, MAX_FILE);
    symbols = Collections.unmodifiableMap(symbolMap());
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
    origin = "http://127.0.0.1:" + server.getAddress().getPort();
    server.createContext("/", this::handle);
    server.setExecutor(command -> workers.execute(() -> runWithDeadline(command)));
    server.start();
    timer.scheduleAtFixedRate(() -> {
      tasks.values().forEach(NavigationTask::isActive);
      tasks.entrySet().removeIf(e -> System.nanoTime() - e.getValue().created > TimeUnit.MINUTES.toNanos(2));
      cancelledIds.entrySet().removeIf(e -> System.nanoTime() - e.getValue() > TimeUnit.MINUTES.toNanos(2));
    }, 1, 1, TimeUnit.SECONDS);
  }

  String url() {
    String name = URLEncoder.encode(html.getFileName().toString(), StandardCharsets.UTF_8).replace("+", "%20");
    return origin + "/" + name + "#navigator-token=" + token;
  }

  // Include HTTP header parsing, which happens before the exchange handler runs.
  private void runWithDeadline(Runnable command) {
    Thread owner = Thread.currentThread();
    Object guard = new Object();
    boolean[] finished = {false};
    var deadline = timer.schedule(() -> {
      synchronized (guard) {
        if (!finished[0]) owner.interrupt();
      }
    }, 10, TimeUnit.SECONDS);
    try { command.run(); }
    finally {
      synchronized (guard) { finished[0] = true; }
      deadline.cancel(false);
      Thread.interrupted(); // Do not carry cancellation into the next pooled request.
    }
  }

  private void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      exchange.getResponseHeaders().set("Cache-Control", "no-store");
      exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
      exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
      exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
      if (!("127.0.0.1:" + server.getAddress().getPort()).equals(exchange.getRequestHeaders().getFirst("Host"))) {
        json(exchange, 403, Map.of("error", "invalid_host")); return;
      }
      String path = exchange.getRequestURI().getPath();
      if (path.startsWith("/api/")) {
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        String requestOrigin = exchange.getRequestHeaders().getFirst("Origin");
        if (!MessageDigest.isEqual(("Bearer " + token).getBytes(StandardCharsets.UTF_8),
            String.valueOf(auth).getBytes(StandardCharsets.UTF_8)) || (requestOrigin != null && !origin.equals(requestOrigin))) {
          json(exchange, 403, Map.of("error", "unauthorized")); return;
        }
        api(exchange, path); return;
      }
      if (!exchange.getRequestMethod().equals("GET")) { json(exchange, 405, Map.of("error", "method_not_allowed")); return; }
      if (path.equals("/navigator-bridge.js")) {
        try (var in = NavigationServer.class.getResourceAsStream("/navigator-bridge.js")) {
          if (in == null) throw new IOException("Missing bridge resource");
          send(exchange, 200, "text/javascript; charset=utf-8", in.readAllBytes());
        }
        return;
      }
      Path file;
      try { file = root.resolve(path.substring(1)).normalize().toRealPath(); }
      catch (IOException | InvalidPathException ex) { json(exchange, 404, Map.of("error", "not_found")); return; }
      String name = file.getFileName().toString();
      // Only the chosen HTML and diagram assets, never source files, credentials, or directory listings.
      if (!file.startsWith(root) || !Files.isRegularFile(file) || Files.size(file) > 32 * 1024 * 1024
          || (!file.equals(html) && !name.matches("[^.].*\\.(js|css|svg|png|jpg|woff2?|md)"))
          || root.relativize(file).toString().matches("(^|.*[/\\\\])\\.[^/\\\\]+.*")) {
        json(exchange, 403, Map.of("error", "forbidden")); return;
      }
      byte[] bytes = file.equals(html) ? page : readBounded(file, MAX_FILE);
      if (file.equals(html)) {
        String page = new String(bytes, StandardCharsets.UTF_8);
        String bridge = "<script src=\"/navigator-bridge.js\" defer></script>";
        page = page.contains("</head>") ? page.replace("</head>", bridge + "</head>") : bridge + page;
        bytes = page.getBytes(StandardCharsets.UTF_8);
      }
      String type = file.equals(html) ? "text/html; charset=utf-8" : name.endsWith(".js") ? "text/javascript; charset=utf-8"
          : name.endsWith(".css") ? "text/css" : name.endsWith(".svg") ? "image/svg+xml" : Files.probeContentType(file);
      send(exchange, 200, type == null ? "application/octet-stream" : type, bytes);
    }
  }

  private void api(HttpExchange exchange, String path) throws IOException {
    if (path.equals("/api/symbol-map") && exchange.getRequestMethod().equals("GET")) {
      json(exchange, 200, symbols); return;
    }
    if (path.startsWith("/api/result/")) {
      NavigationTask task = tasks.get(path.substring("/api/result/".length()));
      if (exchange.getRequestMethod().equals("DELETE")) {
        String id = path.substring("/api/result/".length());
        if (!validRequestId(id)) { json(exchange, 400, Map.of("error", "invalid_request_id")); return; }
        synchronized (this) {
          if (cancelledIds.size() >= 256 && !cancelledIds.containsKey(id)) {
            if (task != null) task.cancel("cancelled");
            json(exchange, 429, Map.of("error", "too_many_cancellations")); return;
          }
          cancelledIds.put(id, System.nanoTime());
          if (task != null) { task.cancel("cancelled"); tasks.remove(id, task); }
        }
        json(exchange, 200, Map.of("status", "cancelled"));
        return;
      }
      if (!exchange.getRequestMethod().equals("GET")) {
        json(exchange, 405, Map.of("error", "method_not_allowed")); return;
      }
      if (task != null) task.isActive();
      Map<String, Object> snapshot = task == null ? Map.of("status", "expired")
          : task.result.getNow(Map.of("status", "pending"));
      json(exchange, task == null ? 404 : 200, snapshot);
      if (task != null && !"pending".equals(snapshot.get("status"))) tasks.remove(path.substring("/api/result/".length()), task);
      return;
    }
    if (!path.equals("/api/navigate")) { json(exchange, 404, Map.of("error", "not_found")); return; }
    if (!exchange.getRequestMethod().equals("POST")) { json(exchange, 405, Map.of("error", "method_not_allowed")); return; }
    if (!String.valueOf(exchange.getRequestHeaders().getFirst("Content-Type")).split(";", 2)[0].trim().equalsIgnoreCase("application/json")) {
      json(exchange, 415, Map.of("error", "expected_json")); return;
    }
    byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
    if (body.length > MAX_BODY) { json(exchange, 413, Map.of("error", "request_too_large")); return; }
    NavigationRequest request;
    try {
      var payload = JSON.fromJson(new String(body, StandardCharsets.UTF_8), JsonObject.class);
      if (payload == null) throw new IllegalArgumentException();
      var line = payload.get("line");
      if (line != null && !line.isJsonNull()) {
        if (!line.isJsonPrimitive() || !line.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException();
        Integer.parseInt(line.getAsString());
      }
      request = JSON.fromJson(payload, NavigationRequest.class);
      if (request == null) throw new IllegalArgumentException();
    } catch (RuntimeException ex) { json(exchange, 400, Map.of("error", "invalid_request")); return; }
    String suppliedId = exchange.getRequestHeaders().getFirst("X-Navigator-Request-ID");
    String id = suppliedId == null ? UUID.randomUUID().toString() : suppliedId;
    if (!validRequestId(id)) { json(exchange, 400, Map.of("error", "invalid_request_id")); return; }
    var task = new NavigationTask(taskTimeout);
    synchronized (this) {
      if (closed) { json(exchange, 503, Map.of("error", "closed")); return; }
      if (cancelledIds.containsKey(id)) { json(exchange, 410, Map.of("error", "cancelled")); return; }
      if (tasks.containsKey(id)) { json(exchange, 409, Map.of("error", "duplicate_request_id")); return; }
      if (tasks.size() >= 128) { json(exchange, 429, Map.of("error", "too_many_requests")); return; }
      tasks.put(id, task);
    }
    task.runIfActive(() -> {
      try { navigator.accept(request, task); }
      catch (RuntimeException ex) { task.accept(Map.of("status", "error")); }
    });
    json(exchange, 202, Map.of("status", "accepted", "requestId", id));
  }

  private Map<String, String> symbolMap() throws IOException {
    var result = new LinkedHashMap<String, String>();
    var matcher = Pattern.compile("<script[^>]*id=[\"']source-map[\"'][^>]*>(.*?)</script>", Pattern.DOTALL).matcher(new String(page, StandardCharsets.UTF_8));
    if (!matcher.find()) return result;
    try {
      var entries = JsonParser.parseString(matcher.group(1)).getAsJsonObject();
      for (var entry : entries.entrySet()) {
        try {
          var value = entry.getValue().getAsJsonObject();
          if (value.has("line") && !value.has("file")) continue;
          if (value.has("className")) { result.put(entry.getKey(), value.get("className").getAsString()); continue; }
          if (!value.has("file")) continue;
          Path source;
          try { source = root.resolve(value.get("file").getAsString()).normalize().toRealPath(); }
          catch (IOException | InvalidPathException ex) { continue; }
          if (!source.startsWith(root) || !source.toString().endsWith(".java") || Files.size(source) > 2 * 1024 * 1024) continue;
          String text = new String(readBounded(source, 2 * 1024 * 1024), StandardCharsets.UTF_8);
          var pkg = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;").matcher(text);
          // Generated bundles may hash Java filenames, so read the public top-level declaration.
          var type = Pattern.compile("(?m)^public\\s+(?:(?:abstract|final|sealed|non-sealed)\\s+)*(?:class|interface|record|enum)\\s+(\\w+)").matcher(text);
          if (pkg.find() && type.find()) {
            String outer = type.group(1), name = entry.getKey();
            result.put(name, pkg.group(1) + "." + outer + (name.equals(outer) ? "" : "." + name));
          }
        } catch (RuntimeException | IOException ex) { /* Skip only the malformed entry. */ }
      }
    } catch (RuntimeException ex) { return result; }
    return result;
  }

  private static boolean validRequestId(String id) {
    try { return UUID.fromString(id).toString().equals(id); }
    catch (IllegalArgumentException ex) { return false; }
  }

  private static byte[] readBounded(Path file, int limit) throws IOException {
    try (var input = Files.newInputStream(file)) {
      byte[] bytes = input.readNBytes(limit + 1);
      if (bytes.length > limit) throw new IOException("Diagram resource exceeds size limit");
      return bytes;
    }
  }

  private static void json(HttpExchange exchange, int status, Object object) throws IOException {
    send(exchange, status, "application/json; charset=utf-8", JSON.toJson(object).getBytes(StandardCharsets.UTF_8));
  }
  private static void send(HttpExchange exchange, int status, String type, byte[] bytes) throws IOException {
    exchange.getResponseHeaders().set("Content-Type", type);
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
  }
  @Override public synchronized void close() {
    if (closed) return;
    closed = true;
    tasks.values().forEach(t -> t.cancel("closed"));
    server.stop(0); workers.shutdownNow(); timer.shutdownNow(); tasks.clear(); cancelledIds.clear();
  }
}
