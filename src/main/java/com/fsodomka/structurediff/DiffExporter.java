package com.fsodomka.structurediff;

import com.intellij.openapi.diff.impl.patch.FilePatch;
import com.intellij.openapi.diff.impl.patch.IdeaTextPatchBuilder;
import com.intellij.openapi.diff.impl.patch.UnifiedDiffWriter;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangesUtil;
import com.intellij.openapi.vfs.LocalFileSystem;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;

/** Writes changes as a unified diff into the reviews directory of their repository root, for the review skill. */
final class DiffExporter {
	/** The timestamp format of the skill's own review files. */
	private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

	private DiffExporter() {
	}

	/**
	 * Writes {@code changes} to {@code root/<reviewsDir>/<timestamp>-changes.diff}, with paths relative to {@code root};
	 * binary files are left out, and so are files in {@code reviewsDir}: unless it is ignored, the earlier diffs and reviews
	 * are unversioned changes, and each review would include them. Loads revision contents, so call on a pooled thread.
	 */
	static Path export(Project project, Path root, String reviewsDir, Collection<Change> changes, LocalDateTime now) throws VcsException, IOException {
		Path dir = root.resolve(reviewsDir);
		List<Change> reviewed = changes.stream().filter(change -> !Path.of(ChangesUtil.getFilePath(change).getPath()).startsWith(dir)).toList();
		List<FilePatch> patches = IdeaTextPatchBuilder.buildPatch(project, reviewed, root, false);
		Path gitignore = dir.resolve(".gitignore");
		// only in a new reviews directory: an existing one may be committed on purpose
		if (!Files.exists(dir)) {
			Files.createDirectories(dir);
			Files.writeString(gitignore, "*\n", StandardCharsets.UTF_8);
		}
		String name = TIMESTAMP.format(now) + "-changes";
		Path file;
		// written next to the target and moved, so a failed export leaves no partial diff behind
		Path temp = Files.createTempFile(dir, "changes", ".tmp");
		try {
			try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
				// no PatchEP extensions: their "IDEA additional info" sections mean nothing to the review
				UnifiedDiffWriter.write(project, root, patches, writer, "\n", null, List.of());
			}
			// a second export in the same second gets a numbered name instead of overwriting the first
			for (int i = 1; ; i++) {
				file = dir.resolve(i == 1 ? name + ".diff" : name + "-" + i + ".diff");
				try {
					Files.move(temp, file);
					break;
				}
				catch (FileAlreadyExistsException e) {
					// try the next number
				}
			}
		}
		finally {
			Files.deleteIfExists(temp);
		}
		// the .gitignore too, so the VCS ignores the diff from the start
		LocalFileSystem.getInstance().refreshNioFiles(Files.exists(gitignore) ? List.of(gitignore, file) : List.of(file));
		return file;
	}
}
