package com.fsodomka.structurediff;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangeListListener;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vfs.VirtualFile;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/** Uncommitted changes of the change list, plus unversioned project files. */
final class LocalChangeSource implements ChangeSource {
	private final Project project;
	/** Whether the change list finished its first update after VCS initialization. */
	private volatile boolean vcsReady;

	LocalChangeSource(Project project) {
		this.project = project;
	}

	@Override
	public Collection<Change> changes() {
		return ChangeListManager.getInstance(project).getAllChanges();
	}

	/**
	 * Unversioned files, except excluded ones and those directly in a content root, which skips scratch files like out.json
	 * in the project root. Files outside the content, e.g. in a repository mapped only as a VCS root, are kept.
	 */
	@Override
	public List<FilePath> unversioned() {
		ChangeListManager changeListManager = ChangeListManager.getInstance(project);
		ProjectFileIndex fileIndex = ProjectFileIndex.getInstance(project);
		return ApplicationManager.getApplication().runReadAction((Computable<List<FilePath>>) () -> changeListManager.getUnversionedFilesPaths().stream()
				.filter(path -> {
					VirtualFile file = path.getVirtualFile();
					return file != null && !fileIndex.isExcluded(file)
							&& !Objects.equals(file.getParent(), fileIndex.getContentRootForFile(file));
				})
				.toList());
	}

	@Override
	public boolean hasUnversioned() {
		return true;
	}

	@Override
	public void subscribe(Runnable onChange, Disposable parent) {
		project.getMessageBus().connect(parent).subscribe(ChangeListListener.TOPIC, new ChangeListListener() {
			@Override
			public void changeListUpdateDone() {
				onChange.run();
			}
		});
		ProjectLevelVcsManager.getInstance(project).runAfterInitialization(
				() -> ChangeListManager.getInstance(project).invokeAfterUpdate(false, () -> {
					vcsReady = true;
					onChange.run();
				}));
	}

	@Override
	public boolean highlightChanges() {
		return vcsReady;
	}
}
