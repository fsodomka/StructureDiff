package com.fsodomka.structurediff;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.changes.ChangesUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.stream.Collectors;

/**
 * The files of a refresh with what the panel shows about them: their VCS roots, the unversioned and test files, and
 * their packages relative to their root; and the VCS root holding the project's directory, which may be above it, null
 * when it is in no repository. Looking the roots up can block on the file index, so it is done here, not on the EDT.
 */
record Snapshot(List<FileDiff> files, Map<String, VirtualFile> roots, Set<String> unversioned, Set<String> tests,
						Map<String, String> packages, @Nullable VirtualFile projectRoot) {
	/**
	 * Computes the snapshot of {@code loaded} in a read action, reusing the members of {@code previous} files whose
	 * contents did not change. The read action restarts after every write action; {@code computed} keeps the files
	 * already parsed across the restarts.
	 */
	static Snapshot compute(Project project, List<FileDiff> loaded, Map<String, FileDiff> previous, List<FilePath> unversioned,
							AtomicReferenceArray<FileDiff> computed) {
		ProjectLevelVcsManager vcsManager = ProjectLevelVcsManager.getInstance(project);
		ProjectFileIndex fileIndex = ProjectFileIndex.getInstance(project);
		Map<String, VirtualFile> roots = new HashMap<>();
		Set<String> tests = new HashSet<>();
		Map<String, String> packages = new HashMap<>();
		for (FileDiff file : loaded) {
			VirtualFile virtualFile = LocalFileSystem.getInstance().findFileByPath(file.path());
			if (virtualFile != null && fileIndex.isInTestSourceContent(virtualFile)) {
				tests.add(file.path());
			}
			VirtualFile root = vcsManager.getVcsRootFor(ChangesUtil.getFilePath(file.change()));
			if (root != null) {
				roots.put(file.path(), root);
			}
			// the directory relative to its source root; PackageIndex would need the Java plugin
			VirtualFile dir = virtualFile == null ? null : virtualFile.getParent();
			VirtualFile sourceRoot = dir == null ? null : fileIndex.getSourceRootForFile(dir);
			String packageName = sourceRoot == null ? null : VfsUtilCore.getRelativePath(dir, sourceRoot, '.');
			if (packageName == null) {
				// outside the project's source roots, e.g. another repository or an unmarked src/main/js
				String path = root != null && file.path().startsWith(root.getPath() + "/")
						? file.path().substring(root.getPath().length() + 1) : file.path();
				packageName = PackageNames.conventionalPackage(path);
				if (path.startsWith("src/test/") || path.contains("/src/test/")) {
					tests.add(file.path());
				}
			}
			if (packageName != null) {
				packages.put(file.path(), packageName);
			}
		}
		List<FileDiff> withMembers = new ArrayList<>(loaded.size());
		for (int i = 0; i < loaded.size(); i++) {
			FileDiff file = computed.get(i);
			if (file == null) {
				file = withMembers(project, previous, loaded.get(i));
				computed.set(i, file);
			}
			withMembers.add(file);
		}
		return new Snapshot(withMembers, roots, unversioned.stream().map(FilePath::getPath).collect(Collectors.toSet()), tests,
				PackageNames.relativePackages(packages, roots), projectRoot(project, vcsManager));
	}

	private static @Nullable VirtualFile projectRoot(Project project, ProjectLevelVcsManager vcsManager) {
		VirtualFile projectDir = ProjectUtil.guessProjectDir(project);
		return projectDir == null ? null : vcsManager.getVcsRootFor(projectDir);
	}

	/** Reuses the members of a file whose contents did not change since the last refresh; parsing is the costly part. */
	private static FileDiff withMembers(Project project, Map<String, FileDiff> previous, FileDiff file) {
		FileDiff old = previous.get(file.path());
		if (old != null && old.members() != null && old.status() == file.status() && Objects.equals(old.beforeText(), file.beforeText())
				&& Objects.equals(old.afterText(), file.afterText())) {
			return file.withMembers(old.members());
		}
		return StructureDiffComputer.withMembers(project, file);
	}
}
