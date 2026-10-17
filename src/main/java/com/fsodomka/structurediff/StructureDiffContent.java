package com.fsodomka.structurediff;

import com.intellij.execution.runners.ExecutionUtil;
import com.intellij.icons.AllIcons;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.KeepPopupOnPerform;
import com.intellij.openapi.actionSystem.ex.ComboBoxAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbAwareToggleAction;
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
 * Tool window content in one of three modes: the uncommitted changes; the branch since main plus the uncommitted changes;
 * or the commits selected in the main Git log tab, empty while no commit is selected.
 */
final class StructureDiffContent extends JPanel implements Disposable {
	private static final String MODE_KEY = "structureDiff.mode";
	private static final PluginId GIT_PLUGIN = PluginId.getId("Git4Idea");
	private static final Icon FOLLOWING_ICON = ExecutionUtil.getLiveIndicator(AllIcons.Vcs.History);

	enum Mode {
		UNCOMMITTED("Uncommitted", "Uncommitted changes and unversioned files", AllIcons.Vcs.Changelist),
		BRANCH("Branch vs main", "Changes since the branch forked from origin/main, main, origin/master or master, plus uncommitted changes",
				AllIcons.Vcs.Branch),
		GIT_LOG("Follow Git Log", "The commits selected in the Git log", FOLLOWING_ICON);

		final String text;
		final String description;
		final Icon icon;

		Mode(String text, String description, Icon icon) {
			this.text = text;
			this.description = description;
			this.icon = icon;
		}
	}

	private final Project project;
	private final Content content;
	private final ModeAction modeAction = new ModeAction();
	private final StructureDiffPanel localPanel;
	private Mode mode;
	/** Owns the source and panel of the Branch and Git Log modes; null in the Uncommitted mode. */
	private Disposable modeDisposable;
	private StructureDiffPanel modePanel;
	private BranchChangeSource branchSource;
	private CommitChangeSource commitSource;

	StructureDiffContent(Project project, Content content) {
		super(new BorderLayout());
		this.project = project;
		this.content = content;
		localPanel = new StructureDiffPanel(project, new LocalChangeSource(project), modeAction);
		Disposer.register(this, localPanel);
		setMode(savedMode());
	}

	private static Mode savedMode() {
		try {
			Mode saved = Mode.valueOf(PropertiesComponent.getInstance().getValue(MODE_KEY, Mode.UNCOMMITTED.name()));
			return isAvailable(saved) ? saved : Mode.UNCOMMITTED;
		}
		catch (IllegalArgumentException e) {
			return Mode.UNCOMMITTED;
		}
	}

	/**
	 * Branch vs main needs the optional Git plugin: without it, {@link BranchChangeSource} cannot load, so it is only
	 * created after this check.
	 */
	private static boolean isAvailable(Mode mode) {
		return mode != Mode.BRANCH || PluginManagerCore.isPluginInstalled(GIT_PLUGIN) && !PluginManagerCore.isDisabled(GIT_PLUGIN);
	}

	void setMode(Mode newMode) {
		if (newMode == mode) {
			return;
		}
		mode = newMode;
		PropertiesComponent.getInstance().setValue(MODE_KEY, newMode.name());
		if (modeDisposable != null) {
			Disposer.dispose(modeDisposable);
			modeDisposable = null;
			modePanel = null;
			branchSource = null;
			commitSource = null;
		}
		if (newMode == Mode.BRANCH) {
			modeDisposable = Disposer.newDisposable(this, "Structure Diff branch mode");
			branchSource = new BranchChangeSource(project);
			modePanel = new StructureDiffPanel(project, branchSource, modeAction);
			Disposer.register(modeDisposable, modePanel);
			// repository changes come on a pooled thread; they can change the base in the title
			branchSource.subscribe(() -> ApplicationManager.getApplication().invokeLater(this::updateShown, ModalityState.any(), project.getDisposed()),
					modePanel);
		}
		else if (newMode == Mode.GIT_LOG) {
			modeDisposable = Disposer.newDisposable(this, "Structure Diff Git log mode");
			commitSource = new CommitChangeSource(project, this);
			Disposer.register(modeDisposable, commitSource);
			modePanel = new StructureDiffPanel(project, commitSource, modeAction);
			Disposer.register(commitSource, modePanel);
			commitSource.subscribe(this::updateShown, modePanel);
		}
		updateShown();
	}

	private void updateShown() {
		JComponent shown;
		String title;
		switch (mode) {
			case BRANCH -> {
				shown = modePanel;
				title = branchSource.title();
			}
			case GIT_LOG -> {
				CommitId commit = commitSource.firstCommit();
				shown = modePanel;
				title = commit == null ? null : title(commit, commitSource.commitCount());
			}
			default -> {
				shown = localPanel;
				title = null;
			}
		}
		if (getComponentCount() == 0 || getComponent(0) != shown) {
			removeAll();
			add(shown, BorderLayout.CENTER);
			revalidate();
			repaint();
		}
		content.setDisplayName(title);
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

	/** Toolbar dropdown of the modes, shared by the panels. */
	private final class ModeAction extends ComboBoxAction implements DumbAware {
		@Override
		protected @NotNull DefaultActionGroup createPopupActionGroup(@NotNull JComponent button, @NotNull DataContext context) {
			DefaultActionGroup group = new DefaultActionGroup();
			for (Mode item : Mode.values()) {
				if (!isAvailable(item)) {
					continue;
				}
				group.add(new DumbAwareToggleAction(item.text, item.description, item.icon) {
					{
						// a toggle keeps the popup open by default, but choosing a mode swaps the panel owning the popup
						getTemplatePresentation().setKeepPopupOnPerform(KeepPopupOnPerform.Never);
					}

					@Override
					public boolean isSelected(@NotNull AnActionEvent e) {
						return mode == item;
					}

					@Override
					public void setSelected(@NotNull AnActionEvent e, boolean state) {
						setMode(item);
					}

					@Override
					public @NotNull ActionUpdateThread getActionUpdateThread() {
						return ActionUpdateThread.EDT;
					}
				});
			}
			return group;
		}

		@Override
		public void update(@NotNull AnActionEvent e) {
			e.getPresentation().setText(mode.text);
			e.getPresentation().setDescription(mode.description);
			e.getPresentation().setIcon(mode.icon);
		}

		@Override
		public @NotNull ActionUpdateThread getActionUpdateThread() {
			return ActionUpdateThread.EDT;
		}
	}
}
