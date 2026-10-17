package com.fsodomka.structurediff.review;

import com.fsodomka.structurediff.review.FindingMembers.MemberFinding;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class ReviewFindingsToolWindowFactory implements ToolWindowFactory, DumbAware {
	static final String ID = "Review Findings";

	/** Activates the tool window, selects {@code found} in it and jumps to the source of the first, the most severe. */
	public static void show(Project project, List<MemberFinding> found) {
		ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ID);
		if (toolWindow == null) {
			return;
		}
		Set<Finding> findings = found.stream().map(MemberFinding::finding).collect(Collectors.toSet());
		// the content is created on the first activation
		toolWindow.activate(() -> {
			for (Content content : toolWindow.getContentManager().getContents()) {
				if (!findings.isEmpty() && content.getComponent() instanceof ReviewFindingsPanel panel) {
					panel.select(findings, found.getFirst().finding());
				}
			}
		});
	}

	@Override
	public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
		Content content = ContentFactory.getInstance().createContent(null, null, false);
		ReviewFindingsPanel panel = new ReviewFindingsPanel(project, content);
		content.setComponent(panel);
		content.setDisposer(panel);
		toolWindow.getContentManager().addContent(content);
	}
}
