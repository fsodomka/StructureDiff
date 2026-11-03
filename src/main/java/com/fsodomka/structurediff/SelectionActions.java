package com.fsodomka.structurediff;

import com.fsodomka.structurediff.history.MemberHistoryToolWindowFactory;
import com.fsodomka.structurediff.source.ChangeSource;
import com.fsodomka.structurediff.source.LocalChangeSource;
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
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.ui.popup.Balloon;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.changes.CurrentContentRevision;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.PopupHandler;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.diff.Diff;
import com.intellij.util.diff.FilesTooBigForDiffException;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * What the changes tree does with its selection: open the diff or the source, revert a member, show its history, and the
 * popup menu offering them. The treemap shares the tree's selection, so it acts through these too.
 */
final class SelectionActions {
	private final Project project;
	private final ChangeSource source;
	private final JTree tree;
	/** Expires the background steps of the actions. */
	private final Disposable parent;

	SelectionActions(Project project, ChangeSource source, JTree tree, Disposable parent) {
		this.project = project;
		this.source = source;
		this.tree = tree;
		this.parent = parent;
	}

	/** The {@link FileDiff} a diff request was opened from, so a later open of the same file can reuse its tab. */
	private static final Key<FileDiff> OPENED_FILE_DIFF = Key.create("structureDiff.openedFileDiff");

	/** Show Diff (Ctrl+D), Jump to Source (F4) and Copy Path, with the shortcuts of the matching IDE actions. */
	void installPopupMenu(JComponent treemap) {
		DefaultActionGroup popup = new DefaultActionGroup();
		popup.add(treeAction("Show Diff", AllIcons.Actions.Diff, "Diff.ShowDiff", () -> selectedFile() != null, this::openSelected, tree, treemap));
		popup.add(treeAction("Jump to Source", AllIcons.Actions.EditSource, IdeActions.ACTION_EDIT_SOURCE,
				() -> selectedWorkingFile() != null, this::jumpToSource, tree, treemap));
		popup.add(treeAction("Revert Member", AllIcons.Actions.Rollback, null,
				() -> source instanceof LocalChangeSource && selectedMember() != null && selectedWorkingFile() != null,
				this::revertSelectedMember, tree, treemap));
		popup.add(treeAction("Show Member History", AllIcons.Vcs.History, null,
				this::canShowSelectedMemberHistory, this::showSelectedMemberHistory, tree, treemap));
		popup.add(treeAction("Copy Path", AllIcons.Actions.Copy, null, () -> selectedFile() != null, () -> {
			FileDiff file = selectedFile();
			if (file == null) {
				return false;
			}
			CopyPasteManager.getInstance().setContents(new StringSelection(FileUtil.toSystemDependentName(file.path())));
			return true;
		}, tree, treemap));
		PopupHandler.installPopupMenu(tree, popup, ActionPlaces.getPopupPlace("StructureDiffPopup"));
		PopupHandler.installPopupMenu(treemap, popup, ActionPlaces.getPopupPlace("StructureDiffPopup"));
	}

	private static DumbAwareAction treeAction(String text, Icon icon, String shortcutActionId, BooleanSupplier enabled,
												  BooleanSupplier perform, JComponent... targets) {
		DumbAwareAction action = new DumbAwareAction(text, null, icon) {
			@Override
			public void actionPerformed(@NotNull AnActionEvent e) {
				perform.getAsBoolean();
			}

			@Override
			public void update(@NotNull AnActionEvent e) {
				e.getPresentation().setEnabled(enabled.getAsBoolean());
			}

			@Override
			public @NotNull ActionUpdateThread getActionUpdateThread() {
				return ActionUpdateThread.EDT;
			}
		};
		AnAction shortcutSource = shortcutActionId == null ? null : ActionManager.getInstance().getAction(shortcutActionId);
		if (shortcutSource != null) {
			for (JComponent target : targets) {
				action.registerCustomShortcutSet(shortcutSource.getShortcutSet(), target);
			}
		}
		return action;
	}

	DefaultMutableTreeNode selectedNode() {
		return (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
	}

	/** All selected nodes; the tree allows selecting several rows with Ctrl or Shift. */
	List<DefaultMutableTreeNode> selectedNodes() {
		TreePath[] paths = tree.getSelectionPaths();
		if (paths == null) {
			return List.of();
		}
		return Arrays.stream(paths)
				.map(path -> (DefaultMutableTreeNode) path.getLastPathComponent())
				.filter(Objects::nonNull)
				.toList();
	}

	/** File of the selected node or of its enclosing file node; null for a repository node or no selection. */
	private FileDiff selectedFile() {
		return DiffTreeNodes.fileOf(selectedNode());
	}

	private MemberDiff selectedMember() {
		DefaultMutableTreeNode node = selectedNode();
		return node != null && node.getUserObject() instanceof MemberDiff member ? member : null;
	}

	/** Working copy of the selected file, for a commit the file at its path after the commit; null when it was deleted. */
	private VirtualFile selectedWorkingFile() {
		FileDiff file = selectedFile();
		ContentRevision after = file == null ? null : file.change().getAfterRevision();
		if (after instanceof CurrentContentRevision current) {
			return current.getVirtualFile();
		}
		return after == null ? null : LocalFileSystem.getInstance().findFileByPath(after.getFile().getPath());
	}

	/** Opens the file in the editor at the selected member; a removed member opens the diff instead. */
	boolean jumpToSource() {
		VirtualFile workingFile = selectedWorkingFile();
		if (workingFile == null) {
			return false;
		}
		MemberDiff member = selectedMember();
		if (member != null && member.afterOffset() < 0) {
			return openSelected();
		}
		FileDiff file = Objects.requireNonNull(selectedFile());
		if (member == null) {
			new OpenFileDescriptor(project, workingFile).navigate(true);
			return true;
		}
		boolean current = file.change().getAfterRevision() instanceof CurrentContentRevision;
		CharSequence text = ApplicationManager.getApplication().runReadAction((Computable<CharSequence>) () -> {
			Document document = FileDocumentManager.getInstance().getDocument(workingFile);
			return document == null ? null : document.getImmutableCharSequence();
		});
		if (text == null || current && StringUtil.equals(text, file.afterText())) {
			// the member offsets are still valid; skip reparsing
			new OpenFileDescriptor(project, workingFile, member.afterOffset()).navigate(true);
			return true;
		}
		List<MemberDiff> path = selectedMemberPath();
		// parsing the current text and diffing it against the committed one can take long on large files
		ReadAction.nonBlocking(() -> {
					int offset = MemberLocator.currentOffset(project, workingFile.getName(), workingFile.getFileType(), text, path);
					if (offset >= 0 || current) {
						return new int[]{offset >= 0 ? offset : member.afterOffset(), -1};
					}
					// the member is gone from the working copy: map its line in the committed text onto the current text
					int line = StringUtil.offsetToLineNumber(file.afterText(), member.afterOffset());
					try {
						line = Math.max(0, Diff.translateLine(file.afterText(), text, line, true));
					}
					catch (FilesTooBigForDiffException ignored) {
					}
					return new int[]{-1, line};
				})
				.expireWith(parent)
				.finishOnUiThread(ModalityState.defaultModalityState(), target -> (target[1] < 0
						? new OpenFileDescriptor(project, workingFile, target[0])
						: new OpenFileDescriptor(project, workingFile, target[1], 0)).navigate(true))
				.submit(AppExecutorUtil.getAppExecutorService());
		return true;
	}

	/** The selected member and its enclosing members, outermost first. */
	private List<MemberDiff> selectedMemberPath() {
		return DiffTreeNodes.memberPath(selectedNode());
	}

	/** A member that exists after the change, in a file that has an after revision. */
	private boolean canShowSelectedMemberHistory() {
		MemberDiff member = selectedMember();
		FileDiff file = selectedFile();
		if (member == null || member.afterOffset() < 0 || file == null || file.change().getAfterRevision() == null) {
			return false;
		}
		// uncommitted: an added file or member is in no commit yet; the other modes show committed revisions
		return !(source instanceof LocalChangeSource) || file.status() != FileStatus.ADDED && member.status() != FileStatus.ADDED;
	}

	/** Opens the commit history of the selected member, at the file's path after the change. */
	private boolean showSelectedMemberHistory() {
		FileDiff file = selectedFile();
		ContentRevision after = file == null ? null : file.change().getAfterRevision();
		List<MemberDiff> path = selectedMemberPath();
		if (after == null || path.isEmpty()) {
			return false;
		}
		// local changes are uncommitted: the after keys are working copy keys
		MemberHistoryToolWindowFactory.show(project, after.getFile(), path.stream().map(MemberDiff::key).toList(),
				source instanceof LocalChangeSource ? file.change() : null);
		return true;
	}

	/** Replaces the selected member in the working file with its original version, as one undoable command. */
	private boolean revertSelectedMember() {
		VirtualFile workingFile = selectedWorkingFile();
		FileDiff file = selectedFile();
		List<MemberDiff> path = selectedMemberPath();
		Document document = workingFile == null ? null : FileDocumentManager.getInstance().getDocument(workingFile);
		if (file == null || path.isEmpty() || document == null) {
			return false;
		}
		CharSequence text = document.getImmutableCharSequence();
		long stamp = document.getModificationStamp();
		ReadAction.nonBlocking(() -> MemberRevert.compute(project, file, workingFile.getName(), workingFile.getFileType(), text, path))
				.expireWith(parent)
				.finishOnUiThread(ModalityState.defaultModalityState(), result -> {
					String error = result.error();
					if (error == null && document.getModificationStamp() != stamp) {
						error = "The file changed while reverting. Try again.";
					}
					if (error != null) {
						showWarningBalloon(error);
						return;
					}
					MemberRevert.Edit edit = Objects.requireNonNull(result.edit());
					WriteCommandAction.runWriteCommandAction(project, "Revert " + path.getLast().label(), null,
							() -> document.replaceString(edit.start(), edit.end(), edit.text()));
				})
				.submit(AppExecutorUtil.getAppExecutorService());
		return true;
	}

	/** Shows {@code message} below the selected row, or at the tree's center when nothing is selected. */
	private void showWarningBalloon(String message) {
		Rectangle row = tree.getPathBounds(tree.getSelectionPath());
		RelativePoint at = row == null ? RelativePoint.getCenterOf(tree)
				: new RelativePoint(tree, new Point(row.x + row.width / 2, row.y + row.height));
		JBPopupFactory.getInstance().createHtmlTextBalloonBuilder(message, MessageType.WARNING, null)
				.setFadeoutTime(5000)
				.createBalloon()
				.show(at, Balloon.Position.below);
	}

	boolean openSelected() {
		DefaultMutableTreeNode node = selectedNode();
		FileDiff file = selectedFile();
		if (file == null) {
			return false;
		}
		if (file.fileType().isBinary()) {
			// no text was loaded for binary files; open the working copy (e.g. image viewer) instead
			return jumpToSource();
		}
		Pair<Side, Integer> scrollTo = null;
		if (node.getUserObject() instanceof MemberDiff member) {
			if (member.afterOffset() >= 0) {
				scrollTo = Pair.create(Side.RIGHT, StringUtil.offsetToLineNumber(file.afterText(), member.afterOffset()));
			}
			else if (member.beforeOffset() >= 0) {
				scrollTo = Pair.create(Side.LEFT, StringUtil.offsetToLineNumber(file.beforeText(), member.beforeOffset()));
			}
		}
		if (scrollOpenDiff(file, scrollTo)) {
			return true;
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
		return true;
	}

	/**
	 * Scrolls a diff tab already opened for {@code file} to {@code scrollTo} and selects the tab. Only a tab opened from
	 * the same {@link FileDiff} instance is reused, so a refresh that changed the before-text opens a fresh diff.
	 */
	private boolean scrollOpenDiff(FileDiff file, Pair<Side, Integer> scrollTo) {
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
