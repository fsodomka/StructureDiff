package com.fsodomka.structurediff.history;

import com.fsodomka.structurediff.StructureDiffComputer;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.content.ContentManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** The Member History tool window: one tab per member, shown once a history is opened. */
public final class MemberHistoryToolWindowFactory implements ToolWindowFactory, DumbAware {
	static final String ID = "Member History";

	/**
	 * Opens a tab with the history of the member at {@code keyPath} in {@code path}, or selects its open tab.
	 * {@code localChange} is the file's uncommitted change when {@code keyPath} is the member's key in the working copy.
	 */
	public static void show(Project project, FilePath path, List<String> keyPath, @Nullable Change localChange) {
		ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ID);
		if (toolWindow == null || keyPath.isEmpty()) {
			return;
		}
		toolWindow.setAvailable(true);
		ContentManager contents = toolWindow.getContentManager();
		for (Content content : contents.getContents()) {
			if (content.getComponent() instanceof MemberHistoryPanel panel && panel.shows(path, keyPath)) {
				contents.setSelectedContent(content);
				toolWindow.activate(null);
				return;
			}
		}
		MemberHistoryPanel panel = new MemberHistoryPanel(project, path, keyPath, localChange);
		Content content = ContentFactory.getInstance().createContent(panel,
				StructureDiffComputer.name(keyPath.getLast()), false);
		content.setDescription(panel.title());
		content.setDisposer(panel);
		contents.addContent(content);
		contents.setSelectedContent(content);
		toolWindow.activate(null);
	}

	@Override
	public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
		// tabs are added by show
	}

	@Override
	public boolean shouldBeAvailable(@NotNull Project project) {
		return false;
	}
}
