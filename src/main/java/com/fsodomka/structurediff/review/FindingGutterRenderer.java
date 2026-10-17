package com.fsodomka.structurediff.review;

import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;
import java.util.Objects;

/** Severity icon with the full finding as tooltip; click shows the tool window, right-click dismisses or copies. */
final class FindingGutterRenderer extends GutterIconRenderer implements DumbAware {
	private final Project project;
	private final Finding finding;
	private final ReviewAnchor.Status status;

	FindingGutterRenderer(Project project, Finding finding, ReviewAnchor.Status status) {
		this.project = project;
		this.finding = finding;
		this.status = status;
	}

	@Override
	public @NotNull Icon getIcon() {
		return finding.severity().icon;
	}

	@Override
	public String getTooltipText() {
		return ReviewText.html(finding, status);
	}

	@Override
	public AnAction getClickAction() {
		return ReviewActions.showToolWindow(project);
	}

	@Override
	public ActionGroup getPopupMenuActions() {
		return new DefaultActionGroup(ReviewActions.dismiss(project, finding), ReviewActions.copy(finding));
	}

	@Override
	public boolean isNavigateAction() {
		return true;
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof FindingGutterRenderer other && other.finding.equals(finding) && other.status == status;
	}

	@Override
	public int hashCode() {
		return Objects.hash(finding, status);
	}
}
