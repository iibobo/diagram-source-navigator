package dev.learning.navigator;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.ide.BrowserUtil;
import java.nio.file.Path;
import org.jetbrains.annotations.NotNull;

public final class OpenDiagramAction extends AnAction {
  @Override public void actionPerformed(@NotNull AnActionEvent event) {
    var project = event.getProject();
    if (project == null) return;
    var descriptor = new FileChooserDescriptor(true, false, false, false, false, false)
        .withTitle("选择 HTML 类图 / Select HTML Class Diagram")
        .withFileFilter(file -> file.getName().endsWith(".html"));
    FileChooser.chooseFile(descriptor, project, null, file -> {
      Path path = Path.of(file.getPath());
      ProgressManager.getInstance().run(new Task.Backgroundable(project, "打开类图导航", false) {
        @Override public void run(@NotNull ProgressIndicator indicator) {
          if (project.isDisposed()) return;
          try {
            var service = project.getService(NavigatorService.class);
            String url = service.open(path);
            ApplicationManager.getApplication().invokeLater(() -> {
              if (!project.isDisposed() && service.isCurrent(url)) BrowserUtil.browse(url);
            });
          } catch (Exception ex) {
            ApplicationManager.getApplication().invokeLater(() -> {
              if (!project.isDisposed()) Messages.showErrorDialog(project,
                  "无法启动类图导航：" + ex.getMessage(), "Diagram Source Navigator");
            });
          }
        }
      });
    });
  }
  @Override public void update(@NotNull AnActionEvent event) {
    event.getPresentation().setEnabledAndVisible(event.getProject() != null);
  }
  @Override public @NotNull com.intellij.openapi.actionSystem.ActionUpdateThread getActionUpdateThread() {
    return com.intellij.openapi.actionSystem.ActionUpdateThread.BGT;
  }
}
