package dev.learning.navigator;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** One navigation request; cancellation and the final UI side effect share a lock. */
final class NavigationTask implements Consumer<Map<String, Object>> {
  final CompletableFuture<Map<String, Object>> result = new CompletableFuture<>();
  final long created = System.nanoTime();
  private final long deadline;
  private boolean active = true;
  private Runnable cancellation = () -> {};

  NavigationTask(Duration timeout) { deadline = created + timeout.toNanos(); }

  synchronized boolean isActive() {
    if (active && System.nanoTime() - deadline >= 0) cancel("timed_out");
    return active;
  }

  synchronized void onCancel(Runnable callback) {
    cancellation = callback;
    if (!active) callback.run();
  }

  synchronized void runIfActive(Runnable action) {
    if (isActive()) action.run();
  }

  @Override public synchronized void accept(Map<String, Object> value) {
    if (!isActive()) return;
    active = false;
    result.complete(Map.copyOf(value));
  }

  synchronized void cancel(String status) {
    if (!active) return;
    active = false;
    result.complete(Map.of("status", status));
    cancellation.run();
  }
}
