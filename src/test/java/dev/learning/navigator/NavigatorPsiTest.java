package dev.learning.navigator;

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.util.io.FileUtil;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Exercises real Java PSI resolution; executed by Gradle test, not the offline HTTP runner. */
public final class NavigatorPsiTest extends LightJavaCodeInsightFixtureTestCase {
  public void testExactOverloadAndExplicitMismatch() throws Exception {
    myFixture.addClass("package demo; public class Worker { public void call(int n) {} public void call(String s) {} }");
    var exact = resolve(new NavigationRequest("demo.Worker", "call", List.of("int"), null));
    assertEquals(1, exact.size());
    assertTrue(label(exact.getFirst()).contains("demo.Worker#call(int)"));
    assertEmpty(resolve(new NavigationRequest("demo.Worker", "call", List.of("boolean"), null)));
  }

  public void testUnspecifiedOverloadPreservesCandidates() throws Exception {
    myFixture.addClass("package demo; public class Worker { public void call(int n) {} public void call(boolean b) {} }");
    assertEquals(2, resolve(new NavigationRequest("demo.Worker", "call", null, null)).size());
  }

  public void testOverridePreferredOverParent() throws Exception {
    myFixture.addClass("package demo; public class Parent { public void call(int n) {} }");
    myFixture.addClass("package demo; public class Child extends Parent { @Override public void call(int n) {} }");
    var choices = resolve(new NavigationRequest("demo.Child", "call", List.of("int"), null));
    assertEquals(1, choices.size());
    assertTrue(label(choices.getFirst()).contains("demo.Child#call(int)"));
  }

  public void testInheritedMethodUsesDeclaringClass() throws Exception {
    myFixture.addClass("package demo; public class Parent { public void call(int n) {} }");
    myFixture.addClass("package demo; public class Child extends Parent {}");
    var choices = resolve(new NavigationRequest("demo.Child", "call", List.of("int"), null));
    assertEquals(1, choices.size());
    assertTrue(label(choices.getFirst()).contains("demo.Parent#call(int)"));
  }

  public void testQualifiedClassAndCandidateLocationRequired() throws Exception {
    myFixture.addClass("package demo; public class Worker {}");
    assertEmpty(resolve(new NavigationRequest("other.Worker", null, null, null)));
    assertEmpty(resolve(new NavigationRequest("demo.Worker", null, null, "file:///another/Worker.java")));
    assertEquals(1, resolve(new NavigationRequest("demo.Worker", null, null, null)).size());
  }

  public void testNavigationCompletesAfterOpeningDeclaration() throws Exception {
    var target = myFixture.addClass("package demo; public class Worker { public void call(int n) {} }");
    var manager = FileEditorManager.getInstance(getProject());
    for (var file : manager.getOpenFiles()) manager.closeFile(file);
    var service = new NavigatorService(getProject());
    var task = new NavigationTask(Duration.ofSeconds(10));
    try {
      navigate(service, new NavigationRequest("demo.Worker", "call", List.of("int"), null), task);
      PlatformTestUtil.waitWithEventsDispatching("Navigation did not complete", task.result::isDone, 15);
      assertEquals("opened", task.result.join().get("status"));
      assertContainsElements(List.of(manager.getSelectedFiles()), target.getContainingFile().getVirtualFile());
    } finally {
      service.dispose();
    }
  }

  public void testMissingDeclarationCompletesWithoutOpeningEditor() throws Exception {
    var manager = FileEditorManager.getInstance(getProject());
    for (var file : manager.getOpenFiles()) manager.closeFile(file);
    var service = new NavigatorService(getProject());
    var task = new NavigationTask(Duration.ofSeconds(10));
    try {
      navigate(service, new NavigationRequest("demo.Absent", null, null, null), task);
      PlatformTestUtil.waitWithEventsDispatching("Missing declaration did not complete", task.result::isDone, 15);
      assertEquals("not_found", task.result.join().get("status"));
      assertEmpty(manager.getOpenFiles());
    } finally {
      service.dispose();
    }
  }

  public void testNavigationWaitsForIndexesThenOpensDeclaration() throws Exception {
    var target = myFixture.addClass("package demo; public class Indexed {}");
    var manager = FileEditorManager.getInstance(getProject());
    for (var file : manager.getOpenFiles()) manager.closeFile(file);
    var service = new NavigatorService(getProject());
    var task = new NavigationTask(Duration.ofSeconds(30));
    var indexing = com.intellij.testFramework.DumbModeTestUtils.startEternalDumbModeTask(getProject());
    try {
      navigate(service, new NavigationRequest("demo.Indexed", null, null, null), task);
      PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
      assertFalse("Navigation must wait while indexes are unavailable", task.result.isDone());
      assertEmpty(manager.getOpenFiles());
    } finally {
      com.intellij.testFramework.DumbModeTestUtils.endEternalDumbModeTaskAndWaitForSmartMode(getProject(), indexing);
    }
    try {
      PlatformTestUtil.waitWithEventsDispatching("Navigation did not resume", task.result::isDone, 15);
      assertEquals("opened", task.result.join().get("status"));
      assertContainsElements(List.of(manager.getSelectedFiles()), target.getContainingFile().getVirtualFile());
    } finally {
      service.dispose();
    }
  }

  public void testMultipleDiagramsKeepIndependentSessionsUntilReplaced() throws Exception {
    Path root = Files.createTempDirectory("navigator-batch");
    var service = new NavigatorService(getProject());
    try (var client = HttpClient.newHttpClient()) {
      Path first = writeDiagram(root.resolve("first"), "demo.First");
      Path second = writeDiagram(root.resolve("second"), "demo.Second");
      var batch = service.open(List.of(first, second));
      assertSize(2, batch);
      assertTrue(service.isCurrent(batch));
      assertTrue(symbolMap(client, batch.getFirst(), batch.getFirst()).body().contains("demo.First"));
      assertTrue(symbolMap(client, batch.getLast(), batch.getLast()).body().contains("demo.Second"));
      assertEquals(403, symbolMap(client, batch.getLast(), batch.getFirst()).statusCode());

      assertThrows(IOException.class, () -> service.open(List.of(first, root.resolve("missing.html"))));
      assertTrue(service.isCurrent(batch));
      assertEquals(200, symbolMap(client, batch.getFirst(), batch.getFirst()).statusCode());
      assertEquals(200, symbolMap(client, batch.getLast(), batch.getLast()).statusCode());

      var replacement = service.open(List.of(first));
      assertTrue(service.isCurrent(replacement));
      assertFalse(service.isCurrent(batch));
      for (String url : batch) {
        assertThrows(ConnectException.class, () -> symbolMap(client, url, url));
      }
      service.dispose();
      assertFalse(service.isCurrent(replacement));
      assertThrows(ConnectException.class, () -> symbolMap(client, replacement.getFirst(), replacement.getFirst()));
    } finally {
      service.dispose();
      FileUtil.deleteRecursively(root);
    }
  }

  public void testDiagramChooserSupportsMultipleFilesAndNativeHtmlFiltering() {
    var descriptor = OpenDiagramAction.diagramChooserDescriptor();
    assertTrue(descriptor.isChooseMultiple());
    assertFalse(descriptor.isChooseFolders());
    var extensionFilter = descriptor.getExtensionFilter();
    assertNotNull("The native chooser needs an extension filter to disable non-HTML files", extensionFilter);
    assertEquals(List.of("html"), extensionFilter.getSecond());
  }

  private static Path writeDiagram(Path directory, String className) throws IOException {
    Files.createDirectory(directory);
    return Files.writeString(directory.resolve("diagram.html"), """
        <html><head><script id="source-map" type="application/json">
        {"Target":{"className":"%s"}}
        </script></head></html>
        """.formatted(className));
  }

  private static HttpResponse<String> symbolMap(HttpClient client, String url, String tokenUrl) throws Exception {
    String bearer = URI.create(tokenUrl).getFragment().substring("navigator-token=".length());
    var request = HttpRequest.newBuilder(URI.create(url).resolve("/api/symbol-map"))
        .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + bearer).build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static void navigate(NavigatorService service, NavigationRequest request, NavigationTask task) throws Exception {
    Method method = NavigatorService.class.getDeclaredMethod("navigate", NavigationRequest.class, NavigationTask.class);
    method.setAccessible(true);
    method.invoke(service, request, task);
  }

  private List<?> resolve(NavigationRequest request) throws Exception {
    Method method = NavigatorService.class.getDeclaredMethod("resolve", NavigationRequest.class);
    method.setAccessible(true);
    return (List<?>) method.invoke(new NavigatorService(getProject()), request);
  }

  private static String label(Object choice) throws Exception {
    Method method = choice.getClass().getDeclaredMethod("label");
    method.setAccessible(true);
    return (String) method.invoke(choice);
  }
}
