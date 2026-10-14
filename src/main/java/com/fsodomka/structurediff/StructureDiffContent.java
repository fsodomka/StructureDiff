package com.fsodomka.structurediff;

import com.intellij.execution.runners.ExecutionUtil;
import com.intellij.icons.AllIcons;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.content.Content;
import com.intellij.vcs.log.CommitId;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;

/**
 * Tool window content: the uncommitted changes, or with "Follow Git Log" the commits selected in the main Git log tab.
 * Falls back to the uncommitted changes while no commit is selected.
 */
final class StructureDiffContent extends JPanel implements Disposable {
	private static final String FOLLOW_GIT_LOG_KEY = "structureDiff.followGitLog";
	private static final Icon FOLLOWING_ICON = ExecutionUtil.getLiveIndicator(AllIcons.Vcs.History);
	private final Project project;
	private final Content content;
	private final ToggleAction followAction = new ToggleAction("Follow Git Log",
			"Show the commits selected in the Git log; uncommitted changes while none is selected", AllIcons.Vcs.History) {
		@Override
		public boolean isSelected(@NotNull AnActionEvent e) {
			return commitSource != null;
		}

		@Override
		public void setSelected(@NotNull AnActionEvent e, boolean state) {
			PropertiesComponent.getInstance().setValue(FOLLOW_GIT_LOG_KEY, state, false);
			setFollow(state);
		}

		/** The green dot marks following even while no commit is selected and the uncommitted changes are shown. */
		@Override
		public void update(@NotNull AnActionEvent e) {
			super.update(e);
			e.getPresentation().setIcon(commitSource != null ? FOLLOWING_ICON : AllIcons.Vcs.History);
		}

		@Override
		public @NotNull ActionUpdateThread getActionUpdateThread() {
			return ActionUpdateThread.EDT;
		}
	};
	private final StructureDiffPanel localPanel;
	/** Both null unless following the Git log. */
	private CommitChangeSource commitSource;
	private StructureDiffPanel commitPanel;

	StructureDiffContent(Project project, Content content) {
		super(new BorderLayout());
		this.project = project;
		this.content = content;
		localPanel = new StructureDiffPanel(project, new LocalChangeSource(project), followAction);
		Disposer.register(this, localPanel);
		setFollow(PropertiesComponent.getInstance().getBoolean(FOLLOW_GIT_LOG_KEY, false));
	}

	private void setFollow(boolean follow) {
		if (follow == (commitSource != null)) {
			updateShown();
			return;
		}
		if (follow) {
			commitSource = new CommitChangeSource(project, this);
			Disposer.register(this, commitSource);
			commitPanel = new StructureDiffPanel(project, commitSource, followAction);
			Disposer.register(commitSource, commitPanel);
			commitSource.subscribe(this::updateShown, commitPanel);
		}
		else {
			Disposer.dispose(commitSource);
			commitSource = null;
			commitPanel = null;
		}
		updateShown();
	}

	private void updateShown() {
		CommitId commit = commitSource == null ? null : commitSource.firstCommit();
		JComponent shown = commit == null ? localPanel : commitPanel;
		if (getComponentCount() == 0 || getComponent(0) != shown) {
			removeAll();
			add(shown, BorderLayout.CENTER);
			revalidate();
			repaint();
		}
		content.setDisplayName(commit == null ? null : title(commit, commitSource.commitCount()));
	}

	/** {@code abc1234}, or {@code abc1234 (+2 commits)} for several selected commits. */
	private static String title(CommitId first, int count) {
		String hash = first.getHash().toShortString();
		int more = count - 1;
		return more == 0 ? hash : hash + " (+" + more + (more == 1 ? " commit)" : " commits)");
	}

	@Override
	public void dispose() {
	}
}
