package dev.learning.navigator;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import java.awt.FlowLayout;
import javax.swing.JButton;
import javax.swing.JPanel;
import org.jetbrains.annotations.NotNull;

public final class DiagramToolWindowFactory implements ToolWindowFactory, DumbAware {
  @Override public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
    var open = new JButton("Open HTML Diagrams…");
    open.addActionListener(event -> OpenDiagramAction.chooseDiagrams(project, open));
    var panel = new JPanel(new FlowLayout(FlowLayout.LEADING));
    panel.add(open);
    var manager = toolWindow.getContentManager();
    var content = manager.getFactory().createContent(panel, "", false);
    content.setPreferredFocusableComponent(open);
    manager.addContent(content);
  }
}
