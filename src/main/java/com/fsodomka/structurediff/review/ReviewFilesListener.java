package com.fsodomka.structurediff.review;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent;
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Loads a review file when it is written, also by the skill outside the IDE, and shows the tool window. Unloads it when
 * it is deleted, moved or renamed away.
 */
public final class ReviewFilesListener implements BulkFileListener {
	private final Project project;

	public ReviewFilesListener(Project project) {
		this.project = project;
	}

	@Override
	public void after(@NotNull List<? extends @NotNull VFileEvent> events) {
		boolean removed = false;
		for (VFileEvent event : events) {
			boolean renamed = event instanceof VFilePropertyChangeEvent property && VirtualFile.PROP_NAME.equals(property.getPropertyName());
			// also of a reviews directory or a repository root
			removed |= event instanceof VFileDeleteEvent || event instanceof VFileMoveEvent || renamed;
			boolean written = event instanceof VFileCreateEvent || event instanceof VFileContentChangeEvent
					|| event instanceof VFileMoveEvent || renamed;
			VirtualFile file = written ? event.getFile() : null;
			if (file != null && file.isValid() && ReviewFindingsService.reviewName(file.getName()) != null) {
				ReviewFindingsService service = ReviewFindingsService.getInstance(project);
				if (service.isReviewFile(file)) {
					service.load(file, false, true);
				}
			}
		}
		if (removed) {
			ReviewFindingsService.getInstance(project).dropRemoved();
		}
	}
}
