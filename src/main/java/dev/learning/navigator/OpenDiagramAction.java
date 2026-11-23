package dev.learning.navigator;

import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import java.awt.Component;
import org.jetbrains.annotations.NotNull;

public final class OpenDiagramAction extends DumbAwareAction {
  @Override public void actionPerformed(@NotNull AnActionEvent event) {
    var project = event.getProject();
    if (project != null) chooseDiagrams(project, null);
  }

  static FileChooserDescriptor diagramChooserDescriptor() {
    return FileChooserDescriptorFactory.multiFiles()
        .withTitle("选择 HTML 图 / Select HTML Diagrams")
        .withExtensionFilter("html")
        .withFileFilter(file -> file.isInLocalFileSystem() && file.getName().endsWith(".html"));
  }

  static void chooseDiagrams(Project project, Component parent) {
    FileChooser.chooseFiles(diagramChooserDescriptor(), project, parent, null, files -> {
      if (files.isEmpty()) return;
      var paths = files.stream().map(VirtualFile::toNioPath).toList();
      ProgressManager.getInstance().run(new Task.Backgroundable(project, "打开图表导航", false) {
        @Override public void run(@NotNull ProgressIndicator indicator) {
          if (project.isDisposed()) return;
          try {
            var service = project.getService(NavigatorService.class);
            var urls = service.open(paths);
            ApplicationManager.getApplication().invokeLater(() -> {
              if (!project.isDisposed() && service.isCurrent(urls)) urls.forEach(BrowserUtil::browse);
            });
          } catch (Exception ex) {
            ApplicationManager.getApplication().invokeLater(() -> {
              if (!project.isDisposed()) Messages.showErrorDialog(project,
                  "无法启动图表导航：" + ex.getMessage(), "Diagram Source Navigator");
            });
          }
        }
      });
    });
  }
  @Override public void update(@NotNull AnActionEvent event) {
    event.getPresentation().setEnabledAndVisible(event.getProject() != null);
  }
  @Override public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.BGT;
  }
}
