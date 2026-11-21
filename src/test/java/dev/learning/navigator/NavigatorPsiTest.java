package dev.learning.navigator;

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.openapi.fileEditor.FileEditorManager;
import java.lang.reflect.Method;
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
