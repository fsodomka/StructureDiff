package com.fsodomka.structurediff.history;

import com.fsodomka.structurediff.StructureDiffComputer;
import com.intellij.codeInsight.hint.HintManager;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.AbstractVcs;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vcs.FileStatusManager;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.vcsUtil.VcsUtil;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** Editor popup action: the history of the innermost member enclosing the caret. */
public final class ShowMemberHistoryAction extends AnAction {
	@Override
	public void update(@NotNull AnActionEvent e) {
		Project project = e.getProject();
		VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
		e.getPresentation().setEnabledAndVisible(project != null && e.getData(CommonDataKeys.EDITOR) != null
				&& file != null && hasHistory(project, file));
	}

	/** Versioned, not binary, and its VCS has a history provider. */
	private static boolean hasHistory(Project project, VirtualFile file) {
		if (file.getFileType().isBinary()) {
			return false;
		}
		AbstractVcs vcs = ProjectLevelVcsManager.getInstance(project).getVcsFor(file);
		if (vcs == null || vcs.getVcsHistoryProvider() == null) {
			return false;
		}
		FileStatus status = FileStatusManager.getInstance(project).getStatus(file);
		return status != FileStatus.UNKNOWN && status != FileStatus.ADDED && status != FileStatus.IGNORED;
	}

	@Override
	public @NotNull ActionUpdateThread getActionUpdateThread() {
		return ActionUpdateThread.BGT;
	}

	@Override
	public void actionPerformed(@NotNull AnActionEvent e) {
		Project project = e.getProject();
		Editor editor = e.getData(CommonDataKeys.EDITOR);
		VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
		if (project == null || editor == null || file == null) {
			return;
		}
		CharSequence text = editor.getDocument().getImmutableCharSequence();
		int offset = editor.getCaretModel().getOffset();
		// parsing a large file can take long
		ReadAction.nonBlocking(() -> StructureDiffComputer.memberKeyPaths(project, file.getName(), file.getFileType(), text,
						new int[]{offset}).getFirst())
				.expireWhen(project::isDisposed)
				.finishOnUiThread(ModalityState.defaultModalityState(), (List<String> keyPath) -> {
					if (keyPath.isEmpty()) {
						HintManager.getInstance().showInformationHint(editor, "No member at the caret");
						return;
					}
					// the editor shows the working copy, whose keys may differ from the latest commit's
					MemberHistoryToolWindowFactory.show(project, VcsUtil.getFilePath(file), keyPath,
							ChangeListManager.getInstance(project).getChange(file));
				})
				.submit(AppExecutorUtil.getAppExecutorService());
	}
}
