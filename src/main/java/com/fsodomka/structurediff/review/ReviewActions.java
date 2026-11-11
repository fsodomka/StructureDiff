package com.fsodomka.structurediff.review;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.NotNull;

import java.awt.datatransfer.StringSelection;
import java.util.List;

/** Actions on one finding, shared by the gutter icon and the tool window. */
final class ReviewActions {
	private ReviewActions() {
	}

	/** Also dismisses the findings that share the fingerprint of {@code finding}; its text then counts them. */
	static AnAction dismiss(Project project, Finding finding) {
		ReviewFindingsService service = ReviewFindingsService.getInstance(project);
		return action(findingsText("Dismiss", service.affectedCount(List.of(finding))), AllIcons.Actions.Cancel,
				() -> service.dismiss(finding));
	}

	/** "Dismiss Finding" for one finding or none, "Dismiss 2 Findings" for more. */
	static String findingsText(String verb, int count) {
		return count > 1 ? verb + ' ' + count + " Findings" : verb + " Finding";
	}

	static AnAction copy(Finding finding) {
		return action("Copy Finding", AllIcons.Actions.Copy,
				() -> CopyPasteManager.getInstance().setContents(new StringSelection(ReviewText.plain(finding))));
	}

	static AnAction showToolWindow(Project project) {
		return action("Show Review Findings", AllIcons.Actions.Preview, () -> {
			ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ReviewFindingsToolWindowFactory.ID);
			if (toolWindow != null) {
				toolWindow.activate(null);
			}
		});
	}

	private static AnAction action(String text, javax.swing.Icon icon, Runnable perform) {
		return new DumbAwareAction(text, null, icon) {
			@Override
			public void actionPerformed(@NotNull AnActionEvent e) {
				perform.run();
			}

			@Override
			public @NotNull ActionUpdateThread getActionUpdateThread() {
				return ActionUpdateThread.EDT;
			}
		};
	}
}
