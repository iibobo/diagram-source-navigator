package dev.learning.navigator;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.TypeConversionUtil;
import com.intellij.pom.Navigatable;
import com.intellij.util.concurrency.AppExecutorUtil;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

public final class NavigatorService implements Disposable {
  private final Project project;
  private volatile List<NavigationServer> servers = List.of();
  private boolean disposed;
  public NavigatorService(Project project) { this.project = project; }

  public synchronized List<String> open(List<Path> htmlFiles) throws IOException {
    if (disposed || project.isDisposed()) throw new IllegalStateException("项目已关闭");
    if (htmlFiles.isEmpty()) return List.of();
    var next = new ArrayList<NavigationServer>();
    for (Path html : htmlFiles) {
      try {
        next.add(new NavigationServer(html, this::navigate));
      } catch (IOException | RuntimeException ex) {
        next.forEach(NavigationServer::close);
        throw new IOException(html.getFileName() + "：" + ex.getMessage(), ex);
      }
    }
    var previous = servers;
    servers = List.copyOf(next);
    previous.forEach(NavigationServer::close);
    return next.stream().map(NavigationServer::url).toList();
  }

  boolean isCurrent(List<String> urls) {
    return !urls.isEmpty() && urls.equals(servers.stream().map(NavigationServer::url).toList());
  }

  private record Choice(NavigationRequest request, String label, SmartPsiElementPointer<PsiElement> pointer) {}

  private void navigate(NavigationRequest request, NavigationTask done) {
    var promise = ReadAction.nonBlocking(() -> resolve(request)).inSmartMode(project).expireWith(this).expireWhen(() -> !done.isActive())
        .finishOnUiThread(ModalityState.nonModal(), choices -> done.runIfActive(() -> {
          if (project.isDisposed()) { done.accept(Map.of("status", "closed")); return; }
          if (choices.isEmpty()) { done.accept(Map.of("status", "not_found", "message", "当前项目与依赖中未找到匹配声明")); return; }
          if (choices.size() > 1) {
            done.accept(Map.of("status", "ambiguous", "candidates", choices.stream().map(c ->
                Map.of("label", c.label(), "target", c.request())).toList()));
            return;
          }
          var element = choices.getFirst().pointer().getElement();
          if (element == null || !element.isValid()) { done.accept(Map.of("status", "stale")); return; }
          var target = element.getNavigationElement();
          if (target instanceof Navigatable n && n.canNavigate()) {
            n.navigate(true);
            done.accept(Map.of("status", "opened", "target", choices.getFirst().label()));
          } else done.accept(Map.of("status", "not_navigatable"));
        })).submit(AppExecutorUtil.getAppExecutorService())
        .onError(error -> done.accept(Map.of("status", "error", "message", "源码导航任务已取消或失败")));
    done.onCancel(promise::cancel);
  }

  private List<Choice> resolve(NavigationRequest request) {
    var scope = GlobalSearchScope.allScope(project);
    String name = request.className().replace('$', '.');
    PsiClass[] classes = JavaPsiFacade.getInstance(project).findClasses(name, scope);
    var choices = new LinkedHashMap<String, Choice>();
    for (var clazz : classes) {
      if (request.fileUrl() != null && !request.fileUrl().equals(url(clazz))) continue;
      if (request.methodName() == null) add(choices, clazz, clazz);
      else {
        var matching = Arrays.stream(clazz.findMethodsByName(request.methodName(), false))
            .filter(m -> matches(m, request.parameterTypes())).toList();
        // Prefer the requested declaration over overridden parent declarations.
        if (matching.isEmpty()) matching = Arrays.stream(clazz.findMethodsByName(request.methodName(), true))
            .filter(m -> matches(m, request.parameterTypes())).toList();
        // Explicit signatures must never silently fall back to a different overload.
        for (var method : matching) add(choices, clazz, method);
      }
      if (choices.size() >= 64) break;
    }
    return choices.values().stream().limit(64).toList();
  }

  private static boolean matches(PsiMethod method, List<String> parameters) {
    if (parameters == null) return true;
    var actual = method.getParameterList().getParameters();
    if (actual.length != parameters.size()) return false;
    for (int i = 0; i < actual.length; i++) {
      String type = TypeConversionUtil.erasure(actual[i].getType()).getCanonicalText().replace("...", "[]").replace('$', '.');
      String requested = parameters.get(i).replace("...", "[]").replace('$', '.');
      if (!type.equals(requested) && !type.substring(type.lastIndexOf('.') + 1).equals(requested)) return false;
    }
    return true;
  }

  private void add(Map<String, Choice> choices, PsiClass searchedClass, PsiElement element) {
    if (searchedClass.getQualifiedName() == null) return;
    PsiMethod method = element instanceof PsiMethod m ? m : null;
    List<String> params = method == null ? null : Arrays.stream(method.getParameterList().getParameters())
        .map(p -> TypeConversionUtil.erasure(p.getType()).getCanonicalText()).toList();
    String signature = method == null ? "" : "#" + method.getName() + "(" + String.join(",", params) + ")";
    String key = url(element) + ":" + element.getTextOffset();
    String label = (method != null && method.getContainingClass() != null
        ? method.getContainingClass().getQualifiedName() : searchedClass.getQualifiedName()) + signature + " — " + url(element);
    PsiClass owner = method == null ? searchedClass : method.getContainingClass();
    var target = new NavigationRequest(owner.getQualifiedName(), method == null ? null : method.getName(), params, url(owner));
    choices.putIfAbsent(key, new Choice(target, label, SmartPointerManager.getInstance(project).createSmartPsiElementPointer(element)));
  }
  private static String url(PsiElement element) {
    var file = element.getContainingFile();
    return file == null || file.getVirtualFile() == null ? "" : file.getVirtualFile().getUrl();
  }
  @Override public synchronized void dispose() {
    disposed = true;
    var previous = servers;
    servers = List.of();
    previous.forEach(NavigationServer::close);
  }
}
