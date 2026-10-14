package com.fsodomka.structurediff;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

public final class StructureDiffToolWindowFactory implements ToolWindowFactory, DumbAware {
	@Override
	public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
		Content content = ContentFactory.getInstance().createContent(null, null, false);
		StructureDiffContent panel = new StructureDiffContent(project, content);
		content.setComponent(panel);
		content.setDisposer(panel);
		toolWindow.getContentManager().addContent(content);
	}
}
