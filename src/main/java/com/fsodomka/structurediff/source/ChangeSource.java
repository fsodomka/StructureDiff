package com.fsodomka.structurediff.source;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vfs.VirtualFile;

import java.util.Collection;
import java.util.List;

/** Where the Structure Diff panel gets its changes from. */
public interface ChangeSource {
	/** Changes to show; called on a pooled thread. */
	Collection<Change> changes();

	/** Unversioned files to show as added; called on a pooled thread. */
	default List<FilePath> unversioned() {
		return List.of();
	}

	/** Whether {@link #unversioned()} can return files. */
	default boolean hasUnversioned() {
		return false;
	}

	/**
	 * The commits behind the changes of one repository, for the churn fill.
	 *
	 * @param logArgs     {@code git log} arguments that select the commits: a revision range, or {@code --no-walk} and hashes
	 * @param workingCopy whether the shown changes include the working copy, so edits after the last commit count too
	 */
	record CommitRange(VirtualFile root, List<String> logArgs, boolean workingCopy) {
	}

	/**
	 * The commit ranges the changes are made of, one per repository; empty when the changes are not commits (the
	 * Uncommitted mode), where there is no churn to show. Called on a pooled thread.
	 */
	default List<CommitRange> commitRanges() {
		return List.of();
	}

	/** Whether the changes are made of commits, so {@link #commitRanges()} can name them. */
	default boolean hasCommits() {
		return false;
	}

	/** Calls {@code onChange} whenever the changes may have changed, until {@code parent} is disposed. */
	void subscribe(Runnable onChange, Disposable parent);

	/**
	 * Whether the difference to the previous refresh is an edit worth flashing; false while the IDE is still loading,
	 * when files and members appear because of the loading, not because they changed.
	 */
	boolean highlightChanges();

	/**
	 * Calls {@code onReplace}, besides {@code onChange}, on changes that replace what is shown, so the refresh shows a busy
	 * spinner, not just the first load; until {@code parent} is disposed.
	 */
	default void subscribeReplacing(Runnable onReplace, Disposable parent) {
	}
}
