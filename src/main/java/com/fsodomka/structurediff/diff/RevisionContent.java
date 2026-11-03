package com.fsodomka.structurediff.diff;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangesUtil;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.changes.CurrentContentRevision;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Objects;

/**
 * Loads the before and after contents of a change, the VCS I/O of a refresh, with their changed line counts; the
 * members are added by {@link StructureDiffComputer#withMembers}.
 */
public final class RevisionContent {
	private static final Logger LOG = Logger.getInstance(RevisionContent.class);

	private RevisionContent() {
	}

	/**
	 * Loads revision contents; may do VCS I/O, so call outside a read action.
	 * Committed revisions are immutable, so their contents are taken from and added to {@code revisionCache}.
	 * The line counts of {@code previous}, the last load of the file, are reused when its contents did not change.
	 */
	public static FileDiff load(Change change, Map<String, String> revisionCache, @Nullable FileDiff previous) {
		return load(change, change.getFileStatus(), revisionCache, previous);
	}

	/** Unversioned file, shown as added. */
	public static FileDiff loadUnversioned(FilePath path, @Nullable FileDiff previous) {
		return load(new Change(null, CurrentContentRevision.create(path)), FileStatus.ADDED, Map.of(), previous);
	}

	private static FileDiff load(Change change, FileStatus status, Map<String, String> revisionCache, @Nullable FileDiff previous) {
		ContentRevision before = change.getBeforeRevision();
		ContentRevision after = change.getAfterRevision();
		FilePath path = ChangesUtil.getFilePath(change);
		FileType fileType = path.getFileType();
		if (fileType.isBinary()) {
			// no structure; decoding binary revisions to strings would only waste memory and VCS I/O on every refresh
			return new FileDiff(change, path.getPath(), fileType, status, null, null, 0, 0, null);
		}
		String beforeText = cachedContent(before, revisionCache);
		String afterText = cachedContent(after, revisionCache);
		int[] lines = previous != null && Objects.equals(previous.beforeText(), beforeText) && Objects.equals(previous.afterText(), afterText)
				? new int[]{previous.linesAdded(), previous.linesRemoved()}
				: StructureDiffComputer.changedLines(StringUtil.notNullize(beforeText), StringUtil.notNullize(afterText));
		return new FileDiff(change, path.getPath(), fileType, status, beforeText, afterText, lines[0], lines[1], null);
	}

	/** Cache key of a committed revision; null for the working copy, whose content can change. */
	public static String revisionKey(ContentRevision revision) {
		if (revision == null || revision instanceof CurrentContentRevision) {
			return null;
		}
		return revision.getFile().getPath() + "@" + revision.getRevisionNumber().asString();
	}

	/** The revision's content, from {@code revisionCache} when it has a committed revision's; adds what it loads. */
	public static String cachedContent(ContentRevision revision, Map<String, String> revisionCache) {
		String key = revisionKey(revision);
		if (key == null) {
			return content(revision);
		}
		String cached = revisionCache.get(key);
		if (cached != null) {
			return cached;
		}
		String content = content(revision);
		if (content != null) {
			revisionCache.put(key, content);
		}
		return content;
	}

	private static String content(ContentRevision revision) {
		if (revision == null) {
			return null;
		}
		try {
			String content = revision.getContent();
			if (content == null) {
				// FakeRevision: placeholder from change lists restored at startup, replaced after the first VCS refresh
				if (!"FakeRevision".equals(revision.getClass().getSimpleName())) {
					LOG.warn("No content for " + revision.getFile() + " (" + revision.getClass().getName() + ")");
				}
				return null;
			}
			return StringUtil.convertLineSeparators(content);
		}
		catch (VcsException e) {
			LOG.warn("Cannot load " + revision.getFile(), e);
			return null;
		}
	}
}
