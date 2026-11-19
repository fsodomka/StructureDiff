package com.fsodomka.structurediff.reason;

import com.fsodomka.structurediff.review.ReviewsDirs;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Re-reads the edit reasons when an edits file is written, also by the agent outside the IDE, or, while reasons are
 * shown, when files are removed; the files are few and small.
 *
 * <p>The service is EDT only, and this listener does not rely on VFS events arriving there, as the platform happens to
 * deliver them today: the events are classified on the VFS thread, and only that decision is applied on the EDT.
 */
public final class EditReasonsFilesListener implements BulkFileListener {
	private final Project project;

	public EditReasonsFilesListener(Project project) {
		this.project = project;
	}

	@Override
	public void after(@NotNull List<? extends @NotNull VFileEvent> events) {
		if (!EditReasonsService.isEnabled()) {
			return;
		}
		// never created here: the service's constructor wires Swing listeners into the editors, and these events can arrive
		// off the EDT (background write actions, 2025.1+). Before it exists nothing is loaded, and the startup activity
		// creates it on the EDT and reads every edits file from disk.
		EditReasonsService service = project.getServiceIfCreated(EditReasonsService.class);
		if (service == null) {
			return;
		}
		// once per batch, not per event: a checkout or refresh delivers thousands, under the write lock
		List<String> rootPaths = ReviewsDirs.roots(project).stream().map(VirtualFile::getPath).toList();
		List<String> editsPaths = EditReasonsService.editsPaths(rootPaths);
		boolean removed = events.stream().anyMatch(event -> removes(event, rootPaths));
		boolean written = events.stream().anyMatch(event -> isInEdits(event, editsPaths));
		if (!removed && !written) {
			return;
		}
		ApplicationManager.getApplication().invokeLater(
				() -> {
					// reasons are kept while hidden: a removal matters only while they are shown
					if (EditReasonsService.isEnabled() && (written || !service.reasons().isEmpty())) {
						service.load();
					}
				}, project.getDisposed());
	}

	/** A delete or move under one of the roots, where the edits files and the files they name are; other projects' don't count. */
	private static boolean removes(VFileEvent event, List<String> rootPaths) {
		if (event instanceof VFileMoveEvent move) {
			return isUnder(move.getOldPath(), rootPaths) || isUnder(move.getNewPath(), rootPaths);
		}
		return event instanceof VFileDeleteEvent && isUnder(event.getPath(), rootPaths);
	}

	private static boolean isUnder(String path, List<String> rootPaths) {
		return rootPaths.stream().anyMatch(root -> FileUtil.isAncestor(root, path, false));
	}

	/** Whether {@code event} is an edits file, or a directory that holds or contains one. */
	private static boolean isInEdits(VFileEvent event, List<String> editsPaths) {
		VirtualFile file = event.getFile();
		return file != null && file.isValid()
				&& (EditReasonsService.isEditsFile(file, editsPaths) || EditReasonsService.containsEditsDir(file, editsPaths));
	}
}
