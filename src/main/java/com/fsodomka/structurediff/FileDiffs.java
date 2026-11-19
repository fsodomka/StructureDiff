package com.fsodomka.structurediff;

import com.fsodomka.structurediff.diff.FileDiff;
import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.contents.DiffContent;
import com.intellij.diff.editor.DiffEditorViewerFileEditor;
import com.intellij.diff.impl.DiffRequestProcessor;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.diff.tools.fragmented.UnifiedDiffViewer;
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer;
import com.intellij.diff.util.DiffUserDataKeys;
import com.intellij.diff.util.DiffUtil;
import com.intellij.diff.util.Side;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.changes.CurrentContentRevision;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.Nullable;

/** Opens a file of the snapshot in the IDE's diff, the tree's Show Diff and a removed reason's pencil alike. */
public final class FileDiffs {
	/** The snapshot file a diff was opened for, which tells this plugin's diffs from others. */
	public static final Key<FileDiff> OPENED_FILE_DIFF = Key.create("structureDiff.openedFileDiff");

	private FileDiffs() {
	}

	/** Shows {@code file}'s before and after side by side, scrolled to {@code scrollTo}, reusing a tab already open for it. */
	public static void open(Project project, FileDiff file, @Nullable Pair<Side, Integer> scrollTo) {
		if (scrollOpenDiff(project, file, scrollTo)) {
			return;
		}
		DiffContentFactory factory = DiffContentFactory.getInstance();
		DiffContent before = factory.create(project, StringUtil.notNullize(file.beforeText()), file.fileType());
		VirtualFile afterFile = file.change().getAfterRevision() instanceof CurrentContentRevision current
				? current.getVirtualFile() : null;
		DiffContent after = afterFile != null
				? factory.create(project, afterFile)
				: factory.create(project, StringUtil.notNullize(file.afterText()), file.fileType());
		SimpleDiffRequest request = new SimpleDiffRequest(file.path(), before, after, "Before", "After");
		request.putUserData(OPENED_FILE_DIFF, file);
		if (scrollTo != null) {
			request.putUserData(DiffUserDataKeys.SCROLL_TO_LINE, scrollTo);
		}
		DiffManager.getInstance().showDiff(project, request);
	}

	/**
	 * Scrolls a diff tab already opened for {@code file} to {@code scrollTo} and selects the tab. Only a tab opened from
	 * the same {@link FileDiff} instance is reused, so a refresh that changed the before-text opens a fresh diff.
	 */
	private static boolean scrollOpenDiff(Project project, FileDiff file, @Nullable Pair<Side, Integer> scrollTo) {
		FileEditorManager editors = FileEditorManager.getInstance(project);
		for (FileEditor editor : editors.getAllEditors()) {
			if (!(editor instanceof DiffEditorViewerFileEditor diffEditor)
					|| !(diffEditor.getEditorViewer() instanceof DiffRequestProcessor processor)
					|| processor.getActiveRequest().getUserData(OPENED_FILE_DIFF) != file) {
				continue;
			}
			new OpenFileDescriptor(project, diffEditor.getFile()).navigate(true);
			if (scrollTo != null) {
				if (processor.getActiveViewer() instanceof TwosideTextDiffViewer twoside) {
					DiffUtil.scrollEditor(twoside.getEditor(scrollTo.first), scrollTo.second, false);
				}
				else if (processor.getActiveViewer() instanceof UnifiedDiffViewer unified) {
					DiffUtil.scrollEditor(unified.getEditor(),
							unified.transferLineToOneside(scrollTo.first, scrollTo.second), false);
				}
			}
			return true;
		}
		return false;
	}
}
