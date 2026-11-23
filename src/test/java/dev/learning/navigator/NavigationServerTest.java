package dev.learning.navigator;

import com.google.gson.*;
import java.net.*;
import java.io.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Real HTTP tests with an injected navigator; does not pretend to test the IDE PSI runtime. */
public final class NavigationServerTest {
  private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  private static int checks;
  public static void main(String[] args) throws Exception {
    Path root = Files.createTempDirectory("navigator-test-");
    var seen = new AtomicReference<NavigationRequest>();
    try {
      Files.writeString(root.resolve("diagram.html"), """
          <html><head></head><body>
          <script id="source-map" type="application/json">
          {"Worker":{"file":"hashed.java","line":2},"Spec":{"file":"hashed.java","line":2},
           "Step":{"className":"demo.Worker","line":2}}
          </script></body></html>
          """);
      Files.writeString(root.resolve("hashed.java"), "package demo;\npublic interface Worker { interface Spec {} }");
      Files.writeString(root.resolve("asset.js"), "// fixture");
      Files.writeString(root.resolve(".env"), "DO_NOT_SERVE");
      try (var server = new NavigationServer(root.resolve("diagram.html"), (request, done) -> {
        seen.set(request); done.accept(Map.of("status", "opened"));
      })) {
        URI entry = URI.create(server.url());
        String origin = entry.getScheme() + "://" + entry.getAuthority();
        String token = entry.getFragment().substring("navigator-token=".length());
        var page = get(origin + "/diagram.html", null);
        check(page.statusCode() == 200 && page.body().contains("/navigator-bridge.js"), "injected viewer");
        check(!page.body().contains(token), "token not embedded in public HTML");
        check(get(origin + "/navigator-bridge.js", null).statusCode() == 200, "packaged bridge resource available");
        check(get(origin + "/api/symbol-map", "wrong-token").statusCode() == 403, "wrong token denied");
        try (var socket = new Socket("127.0.0.1", entry.getPort())) {
          socket.setSoTimeout(3000);
          socket.getOutputStream().write("GET /api/symbol-map HTTP/1.1\r\nHost: attacker.invalid\r\nConnection: close\r\n\r\n".getBytes());
          check(new BufferedReader(new InputStreamReader(socket.getInputStream())).readLine().contains("403"), "invalid Host denied");
        }
        check(get(origin + "/asset.js", null).statusCode() == 200, "asset served");
        check(get(origin + "/.env", null).statusCode() == 403, "dotfile denied");
        check(get(origin + "/hashed.java", null).statusCode() == 403, "source downloads denied");
        check(get(origin + "/", null).statusCode() == 403, "directory listing denied");
        check(get(origin + "/api/symbol-map", null).statusCode() == 403, "token required");
        var map = get(origin + "/api/symbol-map", token);
        check(map.statusCode() == 200 && map.body().contains("demo.Worker.Spec"), "hashed filename and nested class resolved");
        check(!JsonParser.parseString(map.body()).getAsJsonObject().has("Step"), "line-only mappings stay in the page bridge");
        var badOrigin = HttpRequest.newBuilder(URI.create(origin + "/api/symbol-map"))
            .header("Authorization", "Bearer " + token).header("Origin", "https://attacker.invalid").GET().build();
        check(CLIENT.send(badOrigin, HttpResponse.BodyHandlers.ofString()).statusCode() == 403, "cross-origin denied");
        check(post(origin, token, "null").statusCode() == 400, "null rejected");
        check(post(origin, token, "{}").statusCode() == 400, "missing class rejected");
        check(post(origin, token, "{broken").statusCode() == 400, "malformed JSON rejected");
        check(post(origin, token, "{\"className\":\"../bad\"}").statusCode() == 400, "invalid class rejected");
        check(post(origin, token, "x".repeat(17000)).statusCode() == 413, "oversized body rejected");
        var accepted = post(origin, token, "{\"className\":\"demo.Worker\",\"methodName\":\"call\",\"parameterTypes\":[\"java.lang.String\"]}");
        check(accepted.statusCode() == 202, "asynchronous navigation accepted");
        String id = JsonParser.parseString(accepted.body()).getAsJsonObject().get("requestId").getAsString();
        check(get(origin + "/api/result/" + id, token).body().contains("opened"), "completion reported");
        check(seen.get().parameterTypes().equals(List.of("java.lang.String")), "signature preserved");
        var lineRequest = post(origin, token, "{\"className\":\"demo.Worker\",\"line\":22}");
        check(lineRequest.statusCode() == 202, "class and line navigation accepted");
        String lineId = JsonParser.parseString(lineRequest.body()).getAsJsonObject().get("requestId").getAsString();
        check(get(origin + "/api/result/" + lineId, token).body().contains("opened"), "line navigation completion reported");
        check(Integer.valueOf(22).equals(seen.get().line()), "one-based line preserved");
        for (String invalidLine : List.of("0", "-1", "1.2", "2147483648", "4294967297", "\"22\"", "true", "[]", "{}")) {
          check(post(origin, token, "{\"className\":\"demo.Worker\",\"line\":" + invalidLine + "}").statusCode() == 400,
              "invalid line rejected: " + invalidLine);
        }
        check(post(origin, token, "{\"className\":\"demo.Worker\",\"line\":22,\"methodName\":\"call\"}").statusCode() == 400,
            "an absolute source line cannot be combined with a method name");
        check(get(origin + "/api/result/missing", token).statusCode() == 404, "unknown task rejected");
        check(get(origin + "/api/result/" + id, token).statusCode() == 404, "completed task released after delivery");
        for (int n = 0; n < 140; n++) {
          var next = post(origin, token, "{\"className\":\"demo.Worker\"}");
          check(next.statusCode() == 202, "sequential audit task accepted");
          String nextId = JsonParser.parseString(next.body()).getAsJsonObject().get("requestId").getAsString();
          check(get(origin + "/api/result/" + nextId, token).body().contains("opened"), "sequential audit task completed");
        }
        Path outside = Files.createTempFile("outside-navigator-", ".js");
        try {
          Files.createSymbolicLink(root.resolve("escape.js"), outside);
          check(get(origin + "/escape.js", null).statusCode() == 403, "symlink escape denied");
        } finally { Files.deleteIfExists(outside); }
      }
      Path named = root.resolve("a + 中文.html");
      Files.writeString(named, "<html><head></head><body>name</body></html>");
      try (var server = new NavigationServer(named, (request, done) -> {})) {
        check(get(server.url().split("#")[0], null).statusCode() == 200, "spaces, plus and Unicode filenames");
      }
      lifecycleChecks(root);
      System.out.println("PASS " + checks + " HTTP/navigation-contract checks");
    } finally {
      try (var files = Files.walk(root)) {
        for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
      }
    }
  }
  private static void lifecycleChecks(Path root) throws Exception {
    Path html = root.resolve("lifecycle.html");
    Files.writeString(html, """
        <html><head><script id="source-map" type="application/json">
        {"Bad":null,"Good":{"className":"demo.Good"}}
        </script></head></html>
        """);
    var taskRef = new AtomicReference<NavigationTask>();
    var cancellations = new AtomicInteger();
    try (var server = new NavigationServer(html, (request, task) -> {
      taskRef.set(task); task.onCancel(cancellations::incrementAndGet);
    })) {
      var uri = URI.create(server.url());
      String origin = "http://" + uri.getAuthority(), token = uri.getFragment().substring(16);
      check(get(origin + "/api/symbol-map", token).body().contains("demo.Good"), "bad mapping does not discard later valid entry");
      Files.writeString(html, "changed after opening");
      check(get(origin + "/lifecycle.html", null).body().contains("demo.Good"), "HTML and mappings use same immutable snapshot");
      String first = requestId(post(origin, token, "{\"className\":\"demo.Good\"}"));
      check(get(origin + "/api/result/" + first, token).body().contains("pending"), "truly asynchronous pending result");
      var deletion = HttpRequest.newBuilder(URI.create(origin + "/api/result/" + first))
          .header("Authorization", "Bearer " + token).DELETE().build();
      check(CLIENT.send(deletion, HttpResponse.BodyHandlers.ofString()).statusCode() == 200, "cancellation accepted");
      check(post(origin, token, "{\"className\":\"demo.Good\"}", first).statusCode() == 410,
          "cancelled ID cannot be resubmitted");
      var sideEffects = new AtomicInteger();
      taskRef.get().runIfActive(sideEffects::incrementAndGet);
      taskRef.get().accept(Map.of("status", "opened"));
      check(sideEffects.get() == 0 && cancellations.get() == 1, "cancelled task cannot navigate or complete again");
      check(get(origin + "/api/result/" + first, token).statusCode() == 404, "acknowledged cancellation releases task slot");
      try (var slow = new Socket("127.0.0.1", uri.getPort())) {
        slow.setSoTimeout(15000);
        String headers = "POST /api/navigate HTTP/1.1\r\nHost: " + uri.getAuthority()
            + "\r\nAuthorization: Bearer " + token + "\r\nContent-Type: application/json\r\nContent-Length: 100\r\n\r\n{";
        slow.getOutputStream().write(headers.getBytes());
        check(slow.getInputStream().read() == -1, "incomplete request body connection closed by deadline");
        check(get(origin + "/api/symbol-map", token).statusCode() == 200, "server available after slow request");
      }
      var partialHeaders = new ArrayList<Socket>();
      try {
        for (int n = 0; n < 4; n++) {
          var socket = new Socket("127.0.0.1", uri.getPort());
          partialHeaders.add(socket);
          socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: ".getBytes());
        }
        check(get(origin + "/api/symbol-map", token).statusCode() == 200, "partial headers expire and queued request recovers");
      } finally {
        for (var socket : partialHeaders) socket.close();
      }
      String earlyId = UUID.randomUUID().toString();
      var earlyCancellation = HttpRequest.newBuilder(URI.create(origin + "/api/result/" + earlyId))
          .header("Authorization", "Bearer " + token).DELETE().build();
      check(CLIENT.send(earlyCancellation, HttpResponse.BodyHandlers.ofString()).statusCode() == 200,
          "cancellation can precede POST registration");
      check(post(origin, token, "{\"className\":\"demo.Good\"}", earlyId).statusCode() == 410,
          "late POST cannot revive cancelled client request");
      check(post(origin, token, "{\"className\":\"demo.Good\"}", "invalid").statusCode() == 400,
          "invalid client request identifier rejected");
      var requests = new ArrayList<CompletableFuture<HttpResponse<String>>>();
      for (int n = 0; n < 132; n++) {
        var request = HttpRequest.newBuilder(URI.create(origin + "/api/navigate"))
            .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"className\":\"demo.Good\"}")).build();
        // Bound transport concurrency independently of the server's navigation task capacity.
        requests.add(CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString()));
        if (requests.size() % 4 == 0) CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).join();
      }
      CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).join();
      check(requests.stream().filter(f -> f.join().statusCode() == 202).count() == 128, "concurrent admission respects exact task limit");
      check(requests.stream().filter(f -> f.join().statusCode() == 429).count() == 4, "excess tasks rejected");
    }
    check(!taskRef.get().isActive(), "session close cancels pending task");
    try (var server = new NavigationServer(html, (request, task) -> taskRef.set(task), Duration.ofMillis(30))) {
      var uri = URI.create(server.url());
      String origin = "http://" + uri.getAuthority(), token = uri.getFragment().substring(16);
      String id = requestId(post(origin, token, "{\"className\":\"demo.Good\"}"));
      Thread.sleep(60);
      check(get(origin + "/api/result/" + id, token).body().contains("timed_out"), "deadline enforced");
      var sideEffects = new AtomicInteger();
      taskRef.get().runIfActive(sideEffects::incrementAndGet);
      check(sideEffects.get() == 0, "expired task cannot execute delayed navigation");
    }
    Path large = root.resolve("large.html");
    try (var file = new RandomAccessFile(large.toFile(), "rw")) { file.setLength(32L * 1024 * 1024 + 1); }
    try {
      new NavigationServer(large, (request, task) -> {});
      throw new AssertionError("oversized HTML accepted");
    } catch (IOException expected) { check(true, "oversized HTML rejected before startup"); }
  }

  private static String requestId(HttpResponse<String> response) {
    check(response.statusCode() == 202, "navigation accepted");
    return JsonParser.parseString(response.body()).getAsJsonObject().get("requestId").getAsString();
  }

  private static HttpResponse<String> get(String url, String token) throws Exception {
    var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15));
    if (token != null) request.header("Authorization", "Bearer " + token);
    return CLIENT.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
  }
  private static HttpResponse<String> post(String origin, String token, String body) throws Exception {
    return post(origin, token, body, null);
  }
  private static HttpResponse<String> post(String origin, String token, String body, String id) throws Exception {
    var request = HttpRequest.newBuilder(URI.create(origin + "/api/navigate")).timeout(Duration.ofSeconds(15))
        .header("Authorization", "Bearer " + token).header("Content-Type", "application/json");
    if (id != null) request.header("X-Navigator-Request-ID", id);
    return CLIENT.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
  }
  private static void check(boolean valid, String description) {
    if (!valid) throw new AssertionError(description);
    checks++;
  }
}
