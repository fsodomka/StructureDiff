package com.fsodomka.structurediff.review;

import com.fsodomka.structurediff.settings.StructureDiffSettings;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsRoot;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Where review files, and what else agents leave beside them, are looked for: the reviews directory of each root. */
public final class ReviewsDirs {
	private ReviewsDirs() {
	}

	/** The VCS roots and the project directory. */
	public static List<VirtualFile> roots(Project project) {
		Set<VirtualFile> roots = new LinkedHashSet<>();
		for (VcsRoot root : ProjectLevelVcsManager.getInstance(project).getAllVcsRoots()) {
			roots.add(root.getPath());
		}
		VirtualFile projectDir = ProjectUtil.guessProjectDir(project);
		if (projectDir != null) {
			roots.add(projectDir);
		}
		return List.copyOf(roots);
	}

	/** The reviews directory (see {@link StructureDiffSettings}) of {@code root}, or null when it doesn't exist. */
	public static @Nullable VirtualFile reviewsDir(VirtualFile root) {
		VirtualFile dir = root.findFileByRelativePath(StructureDiffSettings.getInstance().reviewsDir());
		return dir != null && dir.isDirectory() ? dir : null;
	}
}
