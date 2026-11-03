package com.fsodomka.structurediff;

import com.fsodomka.structurediff.DiffTreeNodes.RepoNode;
import com.fsodomka.structurediff.diff.FileDiff;
import com.fsodomka.structurediff.settings.StructureDiffSettings;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import javax.swing.tree.DefaultMutableTreeNode;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;

/** Exports the shown changes as diffs for review, one per repository. */
final class DiffExport {
	/** Editor type id of the platform's internal DiffPatchFileEditorProvider, which shows a patch file in the diff viewer. */
	private static final String PATCH_DIFF_EDITOR_TYPE = "DiffPatchFileEditorProvider";

	private DiffExport() {
	}

	/**
	 * The roots of the selected repositories and files, else every root of {@code shownFiles}, in their order; empty
	 * when no shown file is in a repository. A selection of files or repositories restricts the export to those roots.
	 */
	static List<VirtualFile> exportRoots(List<FileDiff> shownFiles, Map<String, VirtualFile> vcsRoots,
										 List<DefaultMutableTreeNode> selected) {
		List<VirtualFile> roots = shownFiles.stream().map(f -> vcsRoots.get(f.path())).filter(Objects::nonNull)
				.distinct().toList();
		if (roots.size() <= 1) {
			return roots;
		}
		Set<VirtualFile> selectedRoots = new HashSet<>();
		for (DefaultMutableTreeNode node : selected) {
			if (node.getUserObject() instanceof RepoNode(VirtualFile root)) {
				selectedRoots.add(root);
			}
			else {
				FileDiff file = DiffTreeNodes.fileOf(node);
				VirtualFile root = file == null ? null : vcsRoots.get(file.path());
				if (root != null) {
					selectedRoots.add(root);
				}
			}
		}
		return selectedRoots.isEmpty() ? roots : roots.stream().filter(selectedRoots::contains).toList();
	}

	/** Name of {@code root} for a diff of its own, safe for a file name and unique among {@code used}. */
	private static String repoLabel(VirtualFile root, Set<String> used) {
		String base = root.getName().replaceAll("[^A-Za-z0-9._-]+", "-");
		if (base.isEmpty() || base.equals(".") || base.equals("..")) {
			base = "repo";
		}
		String label = base;
		for (int i = 2; !used.add(label); i++) {
			label = base + "-" + i;
		}
		return label;
	}

	/** {@code exported} relative to the project base directory when it is below it, else absolute, with {@code /} separators. */
	private static String commandPath(Project project, Path exported) {
		Path file = exported.toAbsolutePath().normalize();
		String base = project.getBasePath();
		if (base != null) {
			Path basePath = Path.of(base).toAbsolutePath().normalize();
			if (file.startsWith(basePath)) {
				return basePath.relativize(file).toString().replace('\\', '/');
			}
		}
		return file.toString().replace('\\', '/');
	}

	/**
	 * Writes the {@code shown} changes of {@code roots} to their reviews directories and copies the review command if
	 * enabled: the selected repository only, or a diff for each of several repositories when none is selected.
	 */
	static void export(Project project, List<VirtualFile> roots, List<FileDiff> shown, Map<String, VirtualFile> vcsRoots) {
		if (roots.isEmpty()) {
			return;
		}
		Map<VirtualFile, List<Change>> changes = new LinkedHashMap<>();
		Map<VirtualFile, String> labels = new LinkedHashMap<>();
		Set<String> usedLabels = new HashSet<>();
		for (VirtualFile root : roots) {
			changes.put(root, shown.stream().filter(f -> root.equals(vcsRoots.get(f.path()))).map(FileDiff::change).toList());
			// a diff per repository needs a name of its own; one selected repository keeps the plain name
			labels.put(root, roots.size() == 1 ? "" : repoLabel(root, usedLabels));
		}
		StructureDiffSettings settings = StructureDiffSettings.getInstance();
		String reviewsDir = settings.reviewsDir();
		LocalDateTime now = LocalDateTime.now();
		new Task.Backgroundable(project, roots.size() == 1 ? "Exporting diff for review" : "Exporting diffs for review", false) {
			private final List<Path> exported = new ArrayList<>();
			private VirtualFile failed;
			private Exception error;

			@Override
			public void run(@NotNull ProgressIndicator indicator) {
				try {
					for (VirtualFile root : roots) {
						failed = root;
						exported.add(DiffExporter.export(project, root.toNioPath(), reviewsDir, changes.get(root), now, labels.get(root)));
					}
				}
				catch (VcsException | IOException e) {
					error = e;
				}
			}

			@Override
			public void onSuccess() {
				if (error != null) {
					notify(NotificationType.ERROR, "Cannot export the diff of " + failed.getName() + ": " + error.getMessage());
					return;
				}
				List<String> paths = exported.stream().map(path -> commandPath(project, path)).toList();
				String saved = paths.size() == 1
						? "Diff saved to " + paths.get(0) + "."
						: "Diffs saved to " + String.join(", ", paths) + ".";
				if (settings.copyCommandToClipboard() && !settings.skillCommand().isEmpty()) {
					CopyPasteManager.getInstance().setContents(new StringSelection(settings.skillCommand() + " " + String.join(" ", paths)));
					notify(NotificationType.INFORMATION, saved + " Review command copied — paste it into your AI agent.");
				} else {
					notify(NotificationType.INFORMATION, saved);
				}
				// DiffExporter already refreshed the files into the VFS
				FileEditorManager editors = FileEditorManager.getInstance(project);
				for (Path path : exported) {
					VirtualFile file = LocalFileSystem.getInstance().findFileByNioFile(path);
					if (file != null) {
						new OpenFileDescriptor(project, file).navigate(true);
						// shows the patch in the diff viewer; does nothing when the IDE has it disabled (registry enable.patch.file.diff.viewer)
						editors.setSelectedEditor(file, PATCH_DIFF_EDITOR_TYPE);
					}
				}
			}

			private void notify(NotificationType type, String content) {
				NotificationGroupManager.getInstance().getNotificationGroup("Structure Diff")
						.createNotification(StringUtil.escapeXmlEntities(content), type).notify(project);
			}
		}.queue();
	}
}
